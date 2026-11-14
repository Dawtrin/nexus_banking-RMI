package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.OperationResult;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The persistent audit log of both servers, newest first. */
public class AuditPage implements AdminPage {
    record Row(String node, AuditEntryDTO e) {
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
    private final TableView<Row> table = Ui.table(filtered, "No audit entries yet.");
    private final Label status = new Label();
    private final TextField text = new TextField();
    private final ComboBox<String> result = new ComboBox<>();

    public AuditPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Time", 160, r -> Ui.DATE_TIME.format(r.e().getTimestamp()));
        Ui.column(table, "Server", 80, Row::node);
        Ui.column(table, "Operator", 110, r -> r.e().getActor());
        Ui.column(table, "Action", 170, r -> r.e().getAction());
        Ui.column(table, "Target", 150, r -> r.e().getTarget());
        Ui.column(table, "Result", 90, r -> r.e().getResult());
        Ui.column(table, "Details", 300, r -> r.e().getDetails());
        text.setPromptText("Filter by operator, action or target");
        text.textProperty().addListener((o, a, b) -> apply());
        result.getItems().addAll("All results", "OK", "REJECTED", "FAILED", "DENIED");
        result.getSelectionModel().select(0);
        result.valueProperty().addListener((o, a, b) -> apply());
        Button reload = new Button("Reload");
        reload.getStyleClass().add("btn-secondary");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        VBox body = new VBox(12, new HBox(10, text, result, reload, status), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Audit log", "Administrative actions and rejected admin sign-ins"), body);
    }

    private void apply() {
        String q = text.getText() == null ? "" : text.getText().toLowerCase();
        String res = result.getValue();
        filtered.setPredicate(r -> (q.isBlank() || r.e().getActor().toLowerCase().contains(q) || r.e().getAction().toLowerCase().contains(q)
                || r.e().getTarget().toLowerCase().contains(q)) && (res == null || res.startsWith("All") || r.e().getResult().equals(res)));
    }

    @Override
    public String title() { return "Audit log"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        status.setText("Loading...");
        session.background().execute(() -> {
            Map<String, OperationResult<List<AuditEntryDTO>>> res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().getAuditLog(session.token(), 300));
            List<Row> all = new ArrayList<>();
            StringBuilder problems = new StringBuilder();
            res.forEach((node, r) -> {
                if (r.isSuccess()) r.getData().forEach(e -> all.add(new Row(node.replace("Node ", ""), e)));
                else problems.append(node).append(": ").append(r.getMessage()).append("   ");
            });
            all.sort(Comparator.comparing((Row r) -> r.e().getTimestamp()).reversed());
            Platform.runLater(() -> {
                rows.setAll(all);
                status.setText(problems.length() == 0 ? all.size() + " entries" : "Some servers did not answer - " + problems);
            });
        });
    }
}
