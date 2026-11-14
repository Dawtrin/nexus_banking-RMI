package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminPreferences;
import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.ui.Ui;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

/** Operator name, server addresses, refresh interval. Stored on this computer; the token never is. */
public class SettingsPage implements AdminPage {
    private final AdminSession session;
    private final VBox root = new VBox();
    private final TextField operator = new TextField();
    private final TextField nodeA = new TextField();
    private final TextField nodeB = new TextField();
    private final ComboBox<String> interval = new ComboBox<>();
    private final Label saved = new Label();

    public SettingsPage(AdminSession session, IntConsumer onInterval, Runnable onSignOut, Runnable onApplied) {
        this.session = session;
        interval.getItems().addAll("2 seconds", "3 seconds", "5 seconds", "10 seconds");
        Button save = new Button("Save changes");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String a = nodeA.getText().trim(), b = nodeB.getText().trim(), op = operator.getText().trim();
            if (op.isEmpty() || op.length() > 40 || AdminSession.parse("A", a).port() < 1 || AdminSession.parse("B", b).port() < 1) {
                saved.setText("Check the operator name (1-40 characters) and both server addresses (host:port).");
                return;
            }
            AdminPreferences.save(a, b, op);
            int secs = Integer.parseInt(interval.getValue().split(" ")[0]);
            AdminPreferences.setRefreshSeconds(secs);
            session.setOperator(op);
            session.updateNodes(a, b);
            onInterval.accept(secs);
            saved.setText("Saved and applied.");
            onApplied.run();
        });
        Button out = new Button("Sign out");
        out.getStyleClass().add("btn-secondary");
        out.setOnAction(e -> onSignOut.run());
        saved.getStyleClass().add("text-muted");
        VBox form = new VBox(14,
                field("Operator name", operator, "Shown next to everything you do in the audit log."),
                field("Node A address", nodeA, "host:port of its RMI registry"),
                field("Node B address", nodeB, "host:port of its RMI registry"),
                field("Refresh every", interval, null),
                new HBox(12, save, out, saved),
                Ui.muted("The admin token is never saved. Sign out clears it from memory."));
        form.setMaxWidth(480);
        form.setPadding(new Insets(24, 28, 22, 28));
        root.getChildren().addAll(Ui.header("Settings", "Stored on this computer only"), form);
    }

    private static VBox field(String label, javafx.scene.Node n, String hint) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        VBox v = new VBox(6, l, n);
        if (hint != null) v.getChildren().add(Ui.muted(hint));
        return v;
    }

    @Override
    public String title() { return "Settings"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        operator.setText(session.operator());
        nodeA.setText(session.nodes().get(0).address());
        nodeB.setText(session.nodes().get(1).address());
        interval.getSelectionModel().select(AdminPreferences.refreshSeconds() + " seconds");
        if (interval.getValue() == null) interval.getSelectionModel().select(1);
        saved.setText("");
    }
}
