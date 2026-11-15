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
        table = Ui.table(filtered, "Không có phiên làm việc nào trên các chi nhánh đã phản hồi.");
        Ui.column(table, "Chi nhánh", 100, r -> r.node().label().replace("Node ", "Chi nhánh "));
        Ui.column(table, "Tài khoản", 120, r -> r.s().getAccountNumber());
        Ui.column(table, "Chủ tài khoản", 170, r -> r.s().getOwnerName());
        Ui.column(table, "Thiết bị / địa chỉ IP", 170, r -> r.s().getRemoteIp());
        Ui.column(table, "Trạng thái", 90, r -> switch (r.s().getStatus()) {
            case ACTIVE -> "Hoạt động"; case IDLE -> "Rảnh"; case STALE -> "Mất tín hiệu"; default -> "Đã ngắt"; });
        Ui.column(table, "Đẩy số dư", 90, r -> r.s().isPushEnabled() ? "RMI callback" : "Polling");
        Ui.column(table, "Đăng nhập lúc", 150, r -> Ui.DATE_TIME.format(r.s().getConnectedAt()));
        Ui.column(table, "Hoạt động cuối", 110, r -> Ui.time(r.s().getLastSeenAt()));
        TextField filter = new TextField();
        filter.setPromptText("Lọc theo số tài khoản, tên hoặc địa chỉ");
        filter.textProperty().addListener((o, a, b) -> filtered.setPredicate(r -> b == null || b.isBlank()
                || r.s().getAccountNumber().contains(b) || r.s().getOwnerName().toLowerCase().contains(b.toLowerCase()) || r.s().getRemoteIp().contains(b)));
        Button kick = new Button("Ngắt phiên đã chọn");
        kick.getStyleClass().add("btn-danger");
        kick.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        kick.setOnAction(e -> disconnect(table.getSelectionModel().getSelectedItem()));
        status.getStyleClass().add("text-muted");
        VBox body = new VBox(12, new HBox(10, filter, kick, status), table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Phiên làm việc", "Khách hàng / giao dịch viên đang đăng nhập, theo từng chi nhánh (mỗi tài khoản chỉ 1 phiên trên toàn cụm)"), body);
    }

    private void disconnect(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Ngắt phiên của " + r.s().getAccountNumber() + "?",
                "Phiên trên " + r.node().label() + " sẽ kết thúc; khách có thể đăng nhập lại."
                        + "\nThao tác được ghi vào sổ kiểm toán dưới tên " + session.operator() + ".")) return;
        session.background().execute(() -> {
            var res = session.monitor().onNode(r.node(), x -> x.admin().disconnectSession(session.token(), session.operator(), r.s().getSessionId()));
            Platform.runLater(() -> status.setText(res.isSuccess() ? "Đã ngắt phiên " + r.s().getAccountNumber() : "Không ngắt được: " + res.getMessage()));
        });
    }

    @Override
    public String title() { return "Phiên làm việc"; }

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
        status.setText(unavailable > 0 ? unavailable + " chi nhánh không phản hồi; phiên của chi nhánh đó không được liệt kê." : "");
    }
}
