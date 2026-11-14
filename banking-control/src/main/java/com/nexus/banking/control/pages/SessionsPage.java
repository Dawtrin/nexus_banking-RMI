package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeEndpoint;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Live sessions of every node that answered, with a real "disconnect" action. */
public class SessionsPage implements AdminPage {
    record Row(NodeEndpoint node, ClientSessionDTO s) {
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table;
    private final Label status = new Label();

    public SessionsPage(AdminSession session) {
        this.session = session;
        FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
        table = Ui.table(filtered, "No active sessions on the servers that answered.");
        Ui.column(table, "Server", 90, r -> r.node().label());
        Ui.column(table, "User", 150, r -> r.s().getUsername());
        Ui.column(table, "Address seen by the server", 190, r -> r.s().getRemoteIp());
        Ui.column(table, "Status", 90, r -> r.s().getStatus().name().charAt(0) + r.s().getStatus().name().substring(1).toLowerCase());
        Ui.column(table, "Connected at", 150, r -> Ui.DATE_TIME.format(r.s().getConnectedAt()));
        Ui.column(table, "Last activity", 110, r -> Ui.time(r.s().getLastSeenAt()));
        TextField filter = new TextField();
        filter.setPromptText("Filter by user or address");
        filter.textProperty().addListener((o, a, b) -> filtered.setPredicate(r -> b == null || b.isBlank()
                || r.s().getUsername().toLowerCase().contains(b.toLowerCase()) || r.s().getRemoteIp().contains(b)));
        Button kick = new Button("Disconnect selected");
        kick.getStyleClass().add("btn-danger");
        kick.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        kick.setOnAction(e -> disconnect(table.getSelectionModel().getSelectedItem()));
        status.getStyleClass().add("text-muted");
        VBox body = new VBox(12, new HBox(10, filter, kick, status), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Sessions", "People currently signed in, per server"), body);
    }

    private void disconnect(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Disconnect " + r.s().getUsername() + "?",
                "The session on " + r.node().label() + " ends. The person can sign in again."
                        + "\nThis is recorded in the audit log as " + session.operator() + ".")) return;
        session.background().execute(() -> {
            var res = session.monitor().onNode(r.node(), x -> x.admin().disconnectSession(session.token(), session.operator(), r.s().getSessionId()));
            Platform.runLater(() -> status.setText(res.isSuccess() ? "Disconnected " + r.s().getUsername() : "Could not disconnect: " + res.getMessage()));
        });
    }

    @Override
    public String title() { return "Sessions"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        List<Row> out = new ArrayList<>();
        for (NodeSnapshot s : snaps) if (s.ok()) s.sessions().forEach(d -> out.add(new Row(s.endpoint(), d)));
        String keep = table.getSelectionModel().getSelectedItem() == null ? null : table.getSelectionModel().getSelectedItem().s().getSessionId();
        rows.setAll(out);
        if (keep != null) out.stream().filter(r -> r.s().getSessionId().equals(keep)).findFirst().ifPresent(r -> table.getSelectionModel().select(r));
        long unavailable = snaps.stream().filter(s -> !s.ok()).count();
        status.setText(unavailable > 0 ? unavailable + " server(s) did not answer; their sessions are not listed." : "");
    }
}
