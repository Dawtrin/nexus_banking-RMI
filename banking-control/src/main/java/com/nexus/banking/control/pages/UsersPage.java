package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.UserAdminDTO;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everyone who has signed in to either server; no credentials exist, so none are shown. */
public class UsersPage implements AdminPage {
    record Row(String username, String displayName, String status, int sessionsA, int sessionsB, Instant lastSeen) {
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = Ui.table(rows, "No users have signed in yet.");
    private final Label status = new Label();

    public UsersPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Username", 150, Row::username);
        Ui.column(table, "Display name", 170, Row::displayName);
        Ui.column(table, "Status", 90, Row::status);
        Ui.column(table, "Signed in on A", 110, r -> String.valueOf(r.sessionsA()));
        Ui.column(table, "Signed in on B", 110, r -> String.valueOf(r.sessionsB()));
        Ui.column(table, "Last seen", 170, r -> r.lastSeen() == null ? "never" : Ui.DATE_TIME.format(r.lastSeen()));
        Button kick = new Button("Disconnect this user everywhere");
        kick.getStyleClass().add("btn-danger");
        kick.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        kick.setOnAction(e -> disconnect(table.getSelectionModel().getSelectedItem()));
        Button reload = new Button("Reload");
        reload.getStyleClass().add("btn-ghost");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        VBox body = new VBox(12, new HBox(10, kick, reload, status), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Users", "Names that have signed in. NEXUS has no passwords to manage."), body);
    }

    @Override
    public String title() { return "Users"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        status.setText("Loading...");
        session.background().execute(() -> {
            Map<String, OperationResult<List<UserAdminDTO>>> res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().listUsers(session.token()));
            Map<String, Row> merged = new TreeMap<>();
            StringBuilder problems = new StringBuilder();
            int idx = 0;
            for (var e : res.entrySet()) {
                if (!e.getValue().isSuccess()) problems.append(e.getKey()).append(": ").append(e.getValue().getMessage()).append("   ");
                else for (UserAdminDTO u : e.getValue().getData()) {
                    Row old = merged.get(u.getUsername());
                    int a = (idx == 0 ? u.getLiveSessions() : 0) + (old == null ? 0 : old.sessionsA());
                    int b = (idx == 1 ? u.getLiveSessions() : 0) + (old == null ? 0 : old.sessionsB());
                    Instant seen = old == null || (u.getLastSeenAt() != null && u.getLastSeenAt().isAfter(old.lastSeen() == null ? Instant.EPOCH : old.lastSeen()))
                            ? u.getLastSeenAt() : old.lastSeen();
                    String st = a + b > 0 ? u.getStatus().name().charAt(0) + u.getStatus().name().substring(1).toLowerCase() : "Offline";
                    merged.put(u.getUsername(), new Row(u.getUsername(), u.getDisplayName(), st, a, b, seen));
                }
                idx++;
            }
            Platform.runLater(() -> {
                rows.setAll(merged.values());
                status.setText(problems.length() == 0 ? merged.size() + " users" : "Some servers did not answer - " + problems);
            });
        });
    }

    private void disconnect(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Disconnect " + r.username() + "?",
                "All of this user's sessions on both servers end. They can sign in again.")) return;
        List<NodeSnapshot> snaps = session.latest();
        session.background().execute(() -> {
            int done = 0, failed = 0;
            for (NodeSnapshot s : snaps) {
                if (!s.ok()) continue;
                for (var d : s.sessions()) {
                    if (!d.getUsername().equalsIgnoreCase(r.username())) continue;
                    var res = session.monitor().onNode(s.endpoint(), x -> x.admin().disconnectSession(session.token(), session.operator(), d.getSessionId()));
                    if (res.isSuccess()) done++;
                    else failed++;
                }
            }
            final int d2 = done, f2 = failed;
            Platform.runLater(() -> {
                status.setText(d2 + " session(s) disconnected" + (f2 > 0 ? ", " + f2 + " failed" : ""));
                onShow();
            });
        });
    }
}
