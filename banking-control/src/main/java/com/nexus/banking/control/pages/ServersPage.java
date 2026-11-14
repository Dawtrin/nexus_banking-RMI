package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeEndpoint;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.OperationResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** Per-server facts, effective configuration (no secrets) and the safe operations that exist. */
public class ServersPage implements AdminPage {
    private final AdminSession session;
    private final VBox root = new VBox();
    private final HBox panels = new HBox(28);
    private final Label status = new Label();
    private List<NodeSnapshot> last = List.of();

    public ServersPage(AdminSession session) {
        this.session = session;
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);
        Label note = Ui.muted("NEXUS CONTROL can ask a server to run replication now and to retry dead-lettered messages. "
                + "It cannot start, stop or reconfigure a server: do that on the machine itself.");
        VBox body = new VBox(14, note, status, panels);
        body.setPadding(new Insets(18, 28, 22, 28));
        root.getChildren().addAll(Ui.header("Servers", "Configuration and operations of each server"), body);
    }

    @Override
    public String title() { return "Servers"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        render();
    }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        last = snaps;
        if (root.isVisible() && root.getScene() != null && panels.getChildren().isEmpty()) render();
    }

    private void render() {
        panels.getChildren().clear();
        for (NodeEndpoint n : session.nodes()) {
            NodeSnapshot snap = last.stream().filter(s -> s.endpoint().label().equals(n.label())).findFirst().orElse(null);
            Label title = new Label(n.label() + "  " + n.address());
            title.getStyleClass().add("title-md");
            GridPane cfg = new GridPane();
            cfg.setHgap(16);
            cfg.setVgap(6);
            cfg.add(Ui.muted("Loading configuration..."), 0, 0);
            long dead = snap == null || snap.replication() == null ? 0 : Math.max(0, snap.replication().getDeadLetterCount());
            Button sync = new Button("Run replication now");
            sync.getStyleClass().add("btn-secondary");
            sync.setOnAction(e -> run(n, "Replication cycle", x -> x.admin().syncNow(session.token(), session.operator())));
            Button requeue = new Button("Retry " + dead + " dead-lettered message" + (dead == 1 ? "" : "s"));
            requeue.getStyleClass().add("btn-secondary");
            requeue.setDisable(dead == 0);
            requeue.setOnAction(e -> runInt(n));
            VBox panel = new VBox(12, title, cfg, new HBox(10, sync, requeue));
            HBox.setHgrow(panel, Priority.ALWAYS);
            panels.getChildren().add(panel);
            session.background().execute(() -> {
                OperationResult<Map<String, String>> r = session.monitor().onNode(n, x -> x.admin().getServerConfig(session.token()));
                Platform.runLater(() -> {
                    cfg.getChildren().clear();
                    if (!r.isSuccess()) {
                        Label err = new Label(r.getErrorCode() + ": " + r.getMessage());
                        err.getStyleClass().add("banner-error");
                        cfg.add(err, 0, 0);
                        return;
                    }
                    int i = 0;
                    for (var en : r.getData().entrySet()) {
                        Label k = new Label(en.getKey());
                        k.getStyleClass().addAll("text-muted", "text-mono");
                        Label v = new Label(en.getValue());
                        v.getStyleClass().add("text-mono");
                        cfg.addRow(i++, k, v);
                    }
                });
            });
        }
    }

    private void run(NodeEndpoint n, String what, ClusterMonitorCall call) {
        session.background().execute(() -> {
            var r = session.monitor().onNode(n, x -> call.run(x));
            Platform.runLater(() -> status.setText(n.label() + ": " + what + (r.isSuccess() ? " requested" : " failed - " + r.getMessage())));
        });
    }

    private void runInt(NodeEndpoint n) {
        session.background().execute(() -> {
            var r = session.monitor().onNode(n, x -> x.admin().requeueDeadLetters(session.token(), session.operator()));
            Platform.runLater(() -> status.setText(n.label() + (r.isSuccess() ? ": " + r.getData() + " message(s) queued for another attempt" : ": retry failed - " + r.getMessage())));
        });
    }

    @FunctionalInterface
    private interface ClusterMonitorCall {
        OperationResult<Void> run(com.nexus.banking.control.core.ClusterMonitor.NodeRemotes r) throws Exception;
    }
}
