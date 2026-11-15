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
        interval.getItems().addAll("2 giây", "3 giây", "5 giây", "10 giây");
        Button save = new Button("Lưu thay đổi");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String a = nodeA.getText().trim(), b = nodeB.getText().trim(), op = operator.getText().trim();
            if (op.isEmpty() || op.length() > 40 || AdminSession.parse("A", a).port() < 1 || AdminSession.parse("B", b).port() < 1) {
                saved.setText("Kiểm tra tên điều hành viên (1-40 ký tự) và địa chỉ hai chi nhánh (host:port).");
                return;
            }
            AdminPreferences.save(a, b, op);
            int secs = Integer.parseInt(interval.getValue().split(" ")[0]);
            AdminPreferences.setRefreshSeconds(secs);
            session.setOperator(op);
            session.updateNodes(a, b);
            onInterval.accept(secs);
            saved.setText("Đã lưu và áp dụng.");
            onApplied.run();
        });
        Button out = new Button("Đăng xuất");
        out.getStyleClass().add("btn-secondary");
        out.setOnAction(e -> onSignOut.run());
        saved.getStyleClass().add("text-muted");
        VBox form = new VBox(14,
                field("Tên điều hành viên", operator, "Được ghi cạnh mọi thao tác của bạn trong sổ kiểm toán."),
                field("Địa chỉ chi nhánh A", nodeA, "host:port của RMI registry"),
                field("Địa chỉ chi nhánh B", nodeB, "host:port của RMI registry"),
                field("Làm mới mỗi", interval, null),
                new HBox(12, save, out, saved),
                Ui.muted("Admin token không bao giờ được lưu. Đăng xuất sẽ xóa token khỏi bộ nhớ."));
        form.setMaxWidth(480);
        form.setPadding(new Insets(24, 28, 22, 28));
        root.getChildren().addAll(Ui.header("Cài đặt", "Chỉ lưu trên máy này"), form);
    }

    private static VBox field(String label, javafx.scene.Node n, String hint) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        VBox v = new VBox(6, l, n);
        if (hint != null) v.getChildren().add(Ui.muted(hint));
        return v;
    }

    @Override
    public String title() { return "Cài đặt"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        operator.setText(session.operator());
        nodeA.setText(session.nodes().get(0).address());
        nodeB.setText(session.nodes().get(1).address());
        interval.getSelectionModel().select(AdminPreferences.refreshSeconds() + " giây");
        if (interval.getValue() == null) interval.getSelectionModel().select(1);
        saved.setText("");
    }
}
