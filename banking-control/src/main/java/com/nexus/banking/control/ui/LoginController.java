package com.nexus.banking.control.ui;

import com.nexus.banking.control.core.AdminPreferences;
import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** NEXUS CONTROL sign-in: operator name, admin token, TLS/SSL and the two server addresses. */
public class LoginController {
    private final AdminSession session;
    private final Runnable onSuccess;

    @FXML private StackPane hero;
    @FXML private TextField operatorField;
    @FXML private PasswordField tokenField;
    @FXML private TextField nodeAField;
    @FXML private TextField nodeBField;
    @FXML private CheckBox sslCheck;
    @FXML private HBox errorBanner;
    @FXML private Label errorLabel;
    @FXML private Button connectButton;
    @FXML private Label statusLabel;
    @FXML private Label securityNoticeLabel;

    public LoginController(AdminSession session, Runnable onSuccess) {
        this.session = session;
        this.onSuccess = onSuccess;
    }

    @FXML
    public void initialize() {
        Label mark = new Label("N");
        mark.getStyleClass().add("brand-mark-text");
        mark.setStyle("-fx-font-size: 28px;");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("brand-mark");
        badge.setMinSize(52, 52);
        badge.setMaxSize(52, 52);
        Label title = new Label("NEXUS // CONTROL");
        title.getStyleClass().add("title-xl");
        Label sub = new Label("Trung tâm Điều hành & Giám sát Ngân hàng phân tán: sức khỏe cụm, phiên giao dịch, sổ cái và kiểm toán.");
        sub.getStyleClass().add("text-muted");
        sub.setWrapText(true);
        VBox brand = new VBox(10, badge, title, sub);
        brand.setMaxWidth(420);
        brand.setPadding(new Insets(44, 24, 24, 48));
        StackPane.setAlignment(brand, javafx.geometry.Pos.TOP_LEFT);
        hero.getChildren().addAll(new MeshPane(), brand);
        operatorField.setText(AdminPreferences.operator());
        nodeAField.setText(AdminPreferences.nodeA());
        nodeBField.setText(AdminPreferences.nodeB());
        sslCheck.setSelected(AdminPreferences.sslEnabled());
        sslCheck.selectedProperty().addListener((o, was, now) -> updateSecurityNotice(now));
        updateSecurityNotice(sslCheck.isSelected());
        statusLabel.setText("");
    }

    private void updateSecurityNotice(boolean ssl) {
        if (securityNoticeLabel != null) {
            if (ssl) {
                securityNoticeLabel.setText("Thông tin quản trị và số liệu được mã hóa bằng TLS/SSL.");
            } else {
                securityNoticeLabel.setText("Token chỉ giữ trong bộ nhớ. Đường truyền KHÔNG mã hóa: chỉ dùng trong mạng tin cậy.");
            }
        }
    }

    private void error(String m) {
        errorLabel.setText(m);
        errorBanner.setVisible(true);
        errorBanner.setManaged(true);
    }

    @FXML
    public void onConnect() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
        String op = operatorField.getText() == null ? "" : operatorField.getText().trim();
        String token = tokenField.getText() == null ? "" : tokenField.getText();
        if (op.isEmpty() || op.length() > 40) {
            error("Nhập tên điều hành viên (tối đa 40 ký tự). Tên này được ghi cạnh mọi thao tác trong sổ kiểm toán.");
            return;
        }
        if (token.isBlank()) {
            error("Nhập admin token đã cấu hình trên máy chủ.");
            return;
        }
        if (AdminSession.parse("A", nodeAField.getText()).port() < 1 || AdminSession.parse("B", nodeBField.getText()).port() < 1) {
            error("Địa chỉ máy chủ phải có dạng host:port, ví dụ 192.168.1.10:1099.");
            return;
        }

        final boolean useSsl = sslCheck.isSelected();
        AdminPreferences.setSslEnabled(useSsl);

        session.configure(nodeAField.getText(), nodeBField.getText(), op, token);
        connectButton.setDisable(true);
        statusLabel.setText("Đang hỏi hai chi nhánh" + (useSsl ? " (TLS/SSL)..." : "..."));
        var started = session.monitor().refresh(session.nodes(), token);
        if (started.isEmpty()) {
            connectButton.setDisable(false);
            return;
        }
        started.get().whenComplete((snaps, err) -> Platform.runLater(() -> {
            connectButton.setDisable(false);
            statusLabel.setText("");
            if (err != null || snaps == null) {
                error("Không truy vấn được máy chủ: " + (err == null ? "không phản hồi" : err.getMessage()));
                return;
            }
            List<NodeSnapshot> ok = snaps.stream().filter(NodeSnapshot::ok).toList();
            if (ok.isEmpty()) {
                StringBuilder sb = new StringBuilder("Không máy chủ nào chấp nhận kết nối:\n");
                snaps.forEach(s -> sb.append(s.endpoint().label()).append(" (").append(s.endpoint().address()).append("): ")
                        .append(s.errorCode()).append(" - ").append(s.error()).append("\n"));
                error(sb.toString().trim());
                session.signOut();
                return;
            }
            AdminPreferences.save(nodeAField.getText().trim(), nodeBField.getText().trim(), op);
            session.setLatest(snaps);
            tokenField.clear();
            onSuccess.run();
        }));
    }
}
