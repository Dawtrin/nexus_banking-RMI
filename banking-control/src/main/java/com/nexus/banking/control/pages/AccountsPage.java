package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.AccountStatus;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accounts of both branches side by side, with the emergency tools of the risk-control team. Every change is applied
 * to BOTH nodes (each branch owns its own database) and reported per node; nothing is assumed to have worked.
 */
public class AccountsPage implements AdminPage {
    record Row(String number, String owner, String branch, AccountDTO a, AccountDTO b) {
        String balances() {
            return (a == null ? "-" : Money.format(a.getBalance())) + "  |  " + (b == null ? "-" : Money.format(b.getBalance()));
        }

        boolean balancesMatch() {
            return a != null && b != null && a.getBalance().compareTo(b.getBalance()) == 0;
        }

        String status() {
            if (a == null || b == null) return "Chỉ có trên " + (a == null ? "B" : "A");
            if (a.getStatus() != b.getStatus()) return "LỆCH: A " + label(a.getStatus()) + " / B " + label(b.getStatus());
            return label(a.getStatus());
        }

        private static String label(AccountStatus s) {
            return s == AccountStatus.ACTIVE ? "Hoạt động" : "ĐÃ KHÓA";
        }
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
    private final TableView<Row> table = Ui.table(filtered, "Chưa tải danh sách tài khoản.");
    private final Label status = new Label();

    public AccountsPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Số tài khoản", 120, Row::number);
        Ui.column(table, "Chủ tài khoản", 190, Row::owner);
        Ui.column(table, "CN mở thẻ", 80, Row::branch);
        Ui.column(table, "Số dư CN A", 140, r -> r.a() == null ? "-" : Money.format(r.a().getBalance()));
        Ui.column(table, "Số dư CN B", 140, r -> r.b() == null ? "-" : Money.format(r.b().getBalance()));
        Ui.column(table, "Khớp", 80, r -> r.balancesMatch() ? "Khớp" : "Chưa khớp");
        Ui.column(table, "Trạng thái", 170, Row::status);
        Ui.column(table, "Hạn mức/ngày", 130, r -> Money.format((r.a() != null ? r.a() : r.b()).getDailyLimit()));
        TextField filter = new TextField();
        filter.setPromptText("Lọc theo số tài khoản hoặc tên");
        filter.textProperty().addListener((o, a, b) -> filtered.setPredicate(r -> b == null || b.isBlank()
                || r.number().contains(b) || r.owner().toLowerCase().contains(b.toLowerCase())));
        Button open = btn("Mở tài khoản", "btn-primary", false);
        open.setOnAction(e -> openAccount());
        Button lock = btn("Khóa + ngắt kết nối", "btn-danger", true);
        lock.setOnAction(e -> lockAccount(table.getSelectionModel().getSelectedItem()));
        Button unlock = btn("Mở khóa", "btn-secondary", true);
        unlock.setOnAction(e -> unlock(table.getSelectionModel().getSelectedItem()));
        Button limit = btn("Đổi hạn mức", "btn-secondary", true);
        limit.setOnAction(e -> changeLimit(table.getSelectionModel().getSelectedItem()));
        Button kick = btn("Ngắt kết nối khẩn cấp", "btn-secondary", true);
        kick.setOnAction(e -> disconnect(table.getSelectionModel().getSelectedItem()));
        Button reload = new Button("Tải lại");
        reload.getStyleClass().add("btn-ghost");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);
        VBox body = new VBox(12, new HBox(10, filter, open, lock, unlock, limit, kick, reload), status, table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Tài khoản",
                "Mỗi chi nhánh có cơ sở dữ liệu riêng; thao tác ở đây được áp dụng cho CẢ HAI chi nhánh"), body);
    }

    private Button btn(String text, String style, boolean needsSelection) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        if (needsSelection) b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        return b;
    }

    @Override
    public String title() { return "Tài khoản"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        status.setText("Đang tải...");
        session.background().execute(() -> {
            Map<String, OperationResult<List<AccountDTO>>> res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().listAccounts(session.token()));
            Map<String, AccountDTO[]> merged = new TreeMap<>();
            StringBuilder problems = new StringBuilder();
            int idx = 0;
            for (var e : res.entrySet()) {
                if (!e.getValue().isSuccess()) problems.append(e.getKey()).append(": ").append(e.getValue().getMessage()).append("   ");
                else for (AccountDTO a : e.getValue().getData()) merged.computeIfAbsent(a.getAccountNumber(), k -> new AccountDTO[2])[idx] = a;
                idx++;
            }
            List<Row> list = merged.values().stream().map(p -> {
                AccountDTO ref = p[0] != null ? p[0] : p[1];
                return new Row(ref.getAccountNumber(), ref.getOwnerName(), ref.getBranchId(), p[0], p[1]);
            }).toList();
            Platform.runLater(() -> {
                String keep = table.getSelectionModel().getSelectedItem() == null ? null : table.getSelectionModel().getSelectedItem().number();
                rows.setAll(list);
                if (keep != null) list.stream().filter(r -> r.number().equals(keep)).findFirst().ifPresent(r -> table.getSelectionModel().select(r));
                status.setText(problems.length() == 0 ? list.size() + " tài khoản. Số dư hai chi nhánh có thể lệch tạm thời cho tới khi đồng bộ xong (nhất quán cuối cùng)."
                        : "Có chi nhánh không phản hồi - " + problems);
            });
        });
    }

    private void report(String what, Map<String, OperationResult<Void>> res) {
        StringBuilder sb = new StringBuilder(what + " - ");
        res.forEach((node, r) -> sb.append(node.replace("Node ", "CN ")).append(": ").append(r.isSuccess() ? "xong" : r.getMessage()).append("   "));
        Platform.runLater(() -> {
            status.setText(sb.toString().trim());
            onShow();
        });
    }

    private void openAccount() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Mở tài khoản");
        d.initOwner(root.getScene().getWindow());
        d.getDialogPane().getStylesheets().addAll(root.getScene().getStylesheets());
        TextField number = new TextField();
        number.setPromptText("6-20 chữ số");
        TextField owner = new TextField();
        ComboBox<String> branch = new ComboBox<>();
        branch.getItems().addAll("HN", "HCM");
        branch.getSelectionModel().select(0);
        TextField opening = new TextField("0");
        TextField limit = new TextField("20.000.000");
        TextField pin = new TextField();
        pin.setPromptText("4-8 chữ số");
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        g.addRow(0, new Label("Số tài khoản"), number);
        g.addRow(1, new Label("Chủ tài khoản"), owner);
        g.addRow(2, new Label("Chi nhánh mở"), branch);
        g.addRow(3, new Label("Số dư đầu kỳ (₫)"), opening);
        g.addRow(4, new Label("Hạn mức/ngày (₫)"), limit);
        g.addRow(5, new Label("Mã PIN ban đầu"), pin);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            BigDecimal open = Money.parse(opening.getText());
            BigDecimal lim = Money.parse(limit.getText());
            if (open == null || lim == null) {
                status.setText("Số tiền không hợp lệ (ví dụ 20.000.000).");
                return;
            }
            session.background().execute(() -> report("Mở tài khoản " + number.getText().trim(),
                    session.monitor().onAllNodes(session.nodes(), r -> r.admin().createAccount(session.token(), session.operator(),
                            number.getText().trim(), owner.getText(), branch.getValue(), open, lim, pin.getText().trim()))));
        });
    }

    private void lockAccount(Row r) {
        if (r == null) return;
        Ui.prompt(root.getScene().getWindow(), "Khóa tài khoản " + r.number(),
                "Lý do (ghi vào sổ kiểm toán; tài khoản bị khóa và mọi phiên đang mở bị ngắt ngay):", "Nghi ngờ gian lận").ifPresent(reason ->
                session.background().execute(() -> report("Khóa " + r.number(),
                        session.monitor().onAllNodes(session.nodes(), x -> x.admin().setAccountStatus(session.token(), session.operator(),
                                r.number(), AccountStatus.LOCKED, reason)))));
    }

    private void unlock(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Mở khóa " + r.number() + "?",
                "Khách hàng có thể đăng nhập và giao dịch trở lại.\nThao tác được ghi vào sổ kiểm toán dưới tên " + session.operator() + ".")) return;
        session.background().execute(() -> report("Mở khóa " + r.number(),
                session.monitor().onAllNodes(session.nodes(), x -> x.admin().setAccountStatus(session.token(), session.operator(),
                        r.number(), AccountStatus.ACTIVE, "mở khóa"))));
    }

    private void changeLimit(Row r) {
        if (r == null) return;
        BigDecimal current = (r.a() != null ? r.a() : r.b()).getDailyLimit();
        Ui.prompt(root.getScene().getWindow(), "Đổi hạn mức " + r.number(), "Hạn mức rút/chuyển mỗi ngày (₫):", Money.format(current).replace(" ₫", ""))
                .ifPresent(text -> {
                    BigDecimal v = Money.parse(text);
                    if (v == null || v.signum() <= 0) {
                        status.setText("Hạn mức không hợp lệ.");
                        return;
                    }
                    session.background().execute(() -> report("Đổi hạn mức " + r.number(),
                            session.monitor().onAllNodes(session.nodes(), x -> x.admin().setDailyLimit(session.token(), session.operator(), r.number(), v))));
                });
    }

    private void disconnect(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Ngắt mọi phiên của " + r.number() + "?",
                "Khách bị đăng xuất ngay trên cả hai chi nhánh nhưng tài khoản KHÔNG bị khóa.")) return;
        session.background().execute(() -> {
            var res = session.monitor().onAllNodes(session.nodes(), x -> x.admin().disconnectAccount(session.token(), session.operator(), r.number(), "điều hành ngắt kết nối"));
            StringBuilder sb = new StringBuilder("Ngắt kết nối " + r.number() + " - ");
            res.forEach((node, x) -> sb.append(node.replace("Node ", "CN ")).append(": ").append(x.isSuccess() ? x.getData() + " phiên" : x.getMessage()).append("   "));
            Platform.runLater(() -> status.setText(sb.toString().trim()));
        });
    }
}
