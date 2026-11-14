package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.ServerEventDTO;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recent server events (memory buffer of each server, newest first). */
public class LogsPage implements AdminPage {
    record Row(String node, ServerEventDTO e) {
    }

    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
    private final TableView<Row> table = Ui.table(filtered, "No events.");
    private final TextField text = new TextField();
    private final ComboBox<String> severity = new ComboBox<>();

    public LogsPage(AdminSession session) {
        Ui.column(table, "Time", 100, r -> Ui.time(r.e().getTimestamp()));
        Ui.column(table, "Server", 80, Row::node);
        Ui.column(table, "Level", 80, r -> r.e().getSeverity().name());
        Ui.column(table, "Part", 120, r -> r.e().getSourceComponent());
        Ui.column(table, "Message", 520, r -> r.e().getMessage());
        text.setPromptText("Filter by text");
        severity.getItems().addAll("All levels", "INFO", "WARN", "ERROR");
        severity.getSelectionModel().select(0);
        text.textProperty().addListener((o, a, b) -> apply());
        severity.valueProperty().addListener((o, a, b) -> apply());
        VBox body = new VBox(12, new HBox(10, text, severity, Ui.muted("Events are kept in memory per server and are lost when it restarts.")), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Logs", "What the servers report about themselves"), body);
    }

    private void apply() {
        String q = text.getText() == null ? "" : text.getText().toLowerCase();
        String sev = severity.getValue();
        filtered.setPredicate(r -> (q.isBlank() || r.e().getMessage().toLowerCase().contains(q) || r.e().getSourceComponent().toLowerCase().contains(q))
                && (sev == null || sev.startsWith("All") || r.e().getSeverity().name().equals(sev)));
    }

    @Override
    public String title() { return "Logs"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        List<Row> all = new ArrayList<>();
        for (NodeSnapshot s : snaps) s.events().forEach(e -> all.add(new Row(s.endpoint().label().replace("Node ", ""), e)));
        all.sort(Comparator.comparing((Row r) -> r.e().getTimestamp()).reversed());
        rows.setAll(all);
    }
}
