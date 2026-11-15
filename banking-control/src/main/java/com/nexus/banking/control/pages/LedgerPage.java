package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionSyncState;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciliation panel + the combined ledger of both branches. "Có ở" shows replication at work: an entry that exists on
 * one branch only has not been replicated yet.
 */
public class LedgerPage implements AdminPage {
    record Row(TransactionDTO tx, boolean onA, boolean onB) {
        String presence() {
            return onA && onB ? "A + B" : onA ? "chỉ A (chờ đồng bộ)" : "chỉ B (chờ đồng bộ)";
        }
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final VBox recon = new VBox(8);
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
    private final TableView<Row> table = Ui.table(filtered, "Chưa có bút toán nào.");
    private final Label status = new Label();

    public LedgerPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Thời gian", 160, r -> Ui.DATE_TIME.format(r.tx().getCreatedAt()));
        Ui.column(table, "Tài khoản", 110, r -> r.tx().getAccountNumber());
        Ui.column(table, "Loại", 170, r -> r.tx().getType().getLabel());
        Ui.column(table, "Số tiền", 130, r -> Money.formatSigned(r.tx().getSignedAmount()));
        Ui.column(table, "Đối ứng", 110, r -> r.tx().getReferenceAccount());
        Ui.column(table, "Ghi nhận tại", 90, r -> r.tx().getOriginServerId());
        Ui.column(table, "Có ở", 150, Row::presence);
        Ui.column(table, "Mã giao dịch", 130, r -> r.tx().getTransactionId().length() > 13 ? r.tx().getTransactionId().substring(0, 13) + "…" : r.tx().getTransactionId());
        Ui.column(table, "Ghi chú", 180, r -> r.tx().getNote());
        TextField filter = new TextField();
        filter.setPromptText("Lọc theo số tài khoản, loại hoặc ghi chú");
        filter.textProperty().addListener((o, a, b) -> filtered.setPredicate(r -> b == null || b.isBlank()
                || r.tx().getAccountNumber().contains(b) || r.tx().getType().getLabel().toLowerCase().contains(b.toLowerCase())
                || r.tx().getNote().toLowerCase().contains(b.toLowerCase())));
        Button reload = new Button("Tải lại sổ cái");
        reload.getStyleClass().add("btn-secondary");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);
        VBox reconBox = new VBox(8, sectionTitle("Đối soát hai chi nhánh"), recon);
        reconBox.getStyleClass().add("stat-tile");
        VBox body = new VBox(14, reconBox, new HBox(10, filter, reload, status), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Sổ cái & đối soát",
                "Bút toán của cả hai chi nhánh; số dư hội tụ khi hai bên có cùng dấu vân tay (digest)"), body);
    }

    private static Label sectionTitle(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("title-md");
        return l;
    }

    @Override
    public String title() { return "Sổ cái"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        recon.getChildren().clear();
        if (snaps.size() < 2) {
            recon.getChildren().add(Ui.muted("Chưa có dữ liệu."));
            return;
        }
        LedgerSummaryDTO a = snaps.get(0).ok() ? snaps.get(0).ledger() : null;
        LedgerSummaryDTO b = snaps.get(1).ok() ? snaps.get(1).ledger() : null;
        recon.getChildren().add(line("Chi nhánh A", snaps.get(0), a));
        recon.getChildren().add(line("Chi nhánh B", snaps.get(1), b));
        if (a == null || b == null) {
            recon.getChildren().add(Ui.chip("Không đối soát được: có chi nhánh không phản hồi", "warn"));
            return;
        }
        boolean same = a.getBalanceDigest().equals(b.getBalanceDigest());
        long pendingA = snaps.get(0).replication() == null ? 0 : snaps.get(0).replication().getPendingCount();
        long pendingB = snaps.get(1).replication() == null ? 0 : snaps.get(1).replication().getPendingCount();
        if (same) recon.getChildren().add(Ui.chip("SỐ DƯ MỌI TÀI KHOẢN KHỚP NHAU - nhất quán", "ok"));
        else if (pendingA + pendingB > 0) recon.getChildren().add(Ui.chip("Đang đồng bộ: còn " + (pendingA + pendingB) + " bút toán chờ chuyển - nhất quán cuối cùng", "warn"));
        else recon.getChildren().add(Ui.chip("LỆCH SỐ DƯ dù không còn bút toán chờ - cần điều tra", "danger"));
        List<String> over = new ArrayList<>(a.getOverdrawnAccounts());
        b.getOverdrawnAccounts().stream().filter(x -> !over.contains(x)).forEach(over::add);
        if (!over.isEmpty()) recon.getChildren().add(Ui.chip("Tài khoản âm sau khi hợp nhất (hai chi nhánh cùng chi tiêu): " + String.join(", ", over), "danger"));
    }

    private HBox line(String name, NodeSnapshot snap, LedgerSummaryDTO l) {
        Label n = new Label(name + (snap.status() == null ? "" : " (" + snap.status().getBranchName() + ")"));
        n.setMinWidth(260);
        Label v = new Label(l == null ? "không có dữ liệu" : l.getAccountCount() + " tài khoản (" + l.getLockedCount() + " khóa)  |  tổng số dư "
                + Money.format(l.getTotalBalance()) + "  |  " + l.getTransactionCount() + " bút toán  |  digest " + l.getBalanceDigest().substring(0, 10));
        v.getStyleClass().add("text-mono");
        return new HBox(10, n, v);
    }

    @Override
    public void onShow() {
        status.setText("Đang tải...");
        session.background().execute(() -> {
            Map<String, OperationResult<List<TransactionDTO>>> res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().listTransactions(session.token(), null, 300));
            Map<String, boolean[]> presence = new LinkedHashMap<>();
            Map<String, TransactionDTO> byId = new LinkedHashMap<>();
            StringBuilder problems = new StringBuilder();
            int idx = 0;
            for (var e : res.entrySet()) {
                if (!e.getValue().isSuccess()) problems.append(e.getKey()).append(": ").append(e.getValue().getMessage()).append("   ");
                else for (TransactionDTO t : e.getValue().getData()) {
                    byId.putIfAbsent(t.getTransactionId(), t);
                    presence.computeIfAbsent(t.getTransactionId(), k -> new boolean[2])[idx] = true;
                }
                idx++;
            }
            List<Row> list = new ArrayList<>();
            byId.forEach((id, t) -> list.add(new Row(t, presence.get(id)[0], presence.get(id)[1])));
            list.sort(Comparator.comparing((Row r) -> r.tx().getCreatedAt()).reversed());
            long unsynced = list.stream().filter(r -> !(r.onA() && r.onB())).count();
            Platform.runLater(() -> {
                rows.setAll(list);
                status.setText(problems.length() == 0 ? list.size() + " bút toán (" + unsynced + " chưa có ở cả hai chi nhánh)"
                        : "Có chi nhánh không phản hồi - " + problems);
            });
        });
    }
}
