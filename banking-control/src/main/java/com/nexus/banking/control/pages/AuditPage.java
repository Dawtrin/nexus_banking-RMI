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
    private final TableView<Row> table = Ui.table(filtered, "Chưa có bút toán kiểm toán.");
    private final Label status = new Label();
    private final TextField text = new TextField();
    private final ComboBox<String> result = new ComboBox<>();
    private final VBox verdictBox = new VBox(6);
    private final Label verdict = new Label();

    public AuditPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Thời gian", 150, r -> Ui.DATE_TIME.format(r.e().getTimestamp()));
        Ui.column(table, "Nhánh", 60, Row::node);
        Ui.column(table, "#", 50, r -> String.valueOf(r.e().getId()));
        Ui.column(table, "Người thực hiện", 110, r -> r.e().getActor());
        Ui.column(table, "Hành động", 150, r -> r.e().getAction());
        Ui.column(table, "Đối tượng", 120, r -> r.e().getTarget());
        Ui.column(table, "Kết quả", 80, r -> r.e().getResult());
        Ui.column(table, "Chi tiết", 280, r -> r.e().getDetails());
        Ui.column(table, "Hash (SHA-256)", 120, r -> r.e().getHash().length() > 12 ? r.e().getHash().substring(0, 12) + "…" : r.e().getHash());
        text.setPromptText("Lọc theo người thực hiện, hành động hoặc đối tượng");
        text.textProperty().addListener((o, a, b) -> apply());
        result.getItems().addAll("Mọi kết quả", "OK", "REJECTED", "FAILED", "DENIED", "WARN");
        result.getSelectionModel().select(0);
        result.valueProperty().addListener((o, a, b) -> apply());
        Button reload = new Button("Tải lại");
        reload.getStyleClass().add("btn-secondary");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        Button verify = new Button("Xác minh toàn vẹn (hash-chain)");
        verify.getStyleClass().add("btn-primary");
        verify.setOnAction(e -> verifyChain());
        verdict.setWrapText(true);
        VBox body = new VBox(12, new HBox(10, text, result, reload, verify), status, verdictBox, table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Sổ kiểm toán bất biến", "Mỗi bút toán liên kết SHA-256 với bút toán trước: sửa hoặc xóa bất kỳ dòng nào đều bị phát hiện"), body);
    }

    private void apply() {
        String q = text.getText() == null ? "" : text.getText().toLowerCase();
        String res = result.getValue();
        filtered.setPredicate(r -> (q.isBlank() || r.e().getActor().toLowerCase().contains(q) || r.e().getAction().toLowerCase().contains(q)
                || r.e().getTarget().toLowerCase().contains(q)) && (res == null || res.startsWith("Mọi") || r.e().getResult().equals(res)));
    }

    @Override
    public String title() { return "Kiểm toán"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        status.setText("Đang tải...");
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
                status.setText(problems.length() == 0 ? all.size() + " bút toán" : "Có chi nhánh không phản hồi - " + problems);
            });
        });
    }

    /** Asks each node to re-compute its hash chain from the first entry and shows a verdict per node. */
    private void verifyChain() {
        verdictBox.getChildren().clear();
        status.setText("Đang tính lại chuỗi băm trên cả hai chi nhánh...");
        session.background().execute(() -> {
            var res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().verifyAuditChain(session.token()));
            Platform.runLater(() -> {
                status.setText("");
                res.forEach((node, r) -> {
                    String name = node.replace("Node ", "Chi nhánh ");
                    Label l;
                    if (!r.isSuccess()) {
                        l = Ui.chip(name + ": không kiểm tra được - " + r.getMessage(), "warn");
                    } else if (r.getData().isValid()) {
                        l = Ui.chip(name + ": TOÀN VẸN - " + r.getData().getMessage(), "ok");
                    } else {
                        l = Ui.chip(name + ": BỊ PHÁ VỠ - " + r.getData().getMessage(), "danger");
                    }
                    verdictBox.getChildren().add(l);
                });
            });
        });
    }
}
