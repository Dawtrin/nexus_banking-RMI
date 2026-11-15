package com.nexus.banking.client.ui;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.model.ConnectionState;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Connection and security information, plus sign-out. */
final class SettingsPage implements Page {
    private final UiContext ctx;
    private final VBox root = new VBox(18);
    private final GridPane grid = new GridPane();

    SettingsPage(UiContext ctx) {
        this.ctx = ctx;
        grid.setHgap(24);
        grid.setVgap(12);
        CheckBox auto = new CheckBox("Tự động kết nối lại / chuyển chi nhánh khi mất mạng");
        auto.setSelected(ClientPreferences.autoReconnect());
        auto.selectedProperty().addListener((o, a, b) -> {
            ClientPreferences.setAutoReconnect(b);
            ctx.core.setAutoReconnect(b);
        });
        Label sec = new Label("Bảo mật");
        sec.getStyleClass().add("title-md");
        Label secText = new Label("• Mỗi tài khoản chỉ được đăng nhập trên MỘT thiết bị tại một thời điểm trên toàn cụm: đăng nhập mới sẽ đóng phiên cũ ngay lập tức.\n"
                + "• Mã PIN chỉ lưu trong bộ nhớ khi đang đăng nhập (để tự kết nối lại), không bao giờ ghi ra đĩa; máy chủ chỉ lưu bản băm PBKDF2.\n"
                + "• Nhập sai PIN nhiều lần sẽ bị tạm khóa đăng nhập.");
        secText.setWrapText(true);
        secText.getStyleClass().add("text-muted");
        Button logout = new Button("Đăng xuất");
        logout.getStyleClass().add("btn-danger");
        logout.setOnAction(e -> ctx.navigate.accept("logout"));
        VBox card = new VBox(16, grid, auto, sec, secText, logout);
        card.getStyleClass().add("surface");
        card.setPadding(new Insets(26));
        card.setMaxWidth(720);
        root.setPadding(new Insets(26, 32, 26, 32));
        root.getChildren().add(card);
        ctx.model.branchName.addListener((o, a, b) -> render());
        ctx.model.pushActive.addListener((o, a, b) -> render());
        ctx.model.state.addListener((o, a, b) -> render());
        render();
    }

    private void row(int r, String k, javafx.scene.Node v) {
        Label l = new Label(k);
        l.getStyleClass().add("form-label");
        grid.add(l, 0, r);
        grid.add(v, 1, r);
    }

    private static Label text(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        return l;
    }

    private void render() {
        grid.getChildren().clear();
        var a = ctx.model.account.get();
        row(0, "Tài khoản", text(a == null ? "-" : a.getAccountNumber() + " · " + a.getOwnerName()));
        row(1, "Chi nhánh phục vụ", text(ctx.model.branchName.get() + " (" + ctx.model.serverId.get() + ")"));
        row(2, "Kết nối", Fmt.chip(ctx.model.state.get().getLabel(), ctx.model.state.get() == ConnectionState.CONNECTED ? "ok" : "warn"));
        row(3, "Cập nhật số dư", ctx.model.pushActive.get() ? Fmt.chip("RMI callback (thời gian thực)", "ok") : Fmt.chip("Polling mỗi 2 giây (callback không khả dụng)", "warn"));
        row(4, "Mã hóa đường truyền", Fmt.chip(ClientPreferences.sslEnabled() ? "TLS bật" : "TLS tắt (mạng demo)", ClientPreferences.sslEnabled() ? "ok" : "neutral"));
        row(5, "Mã thiết bị", text(ctx.core.getInstanceId()));
    }

    @Override public String id() { return "settings"; }
    @Override public String title() { return "Cài đặt & bảo mật"; }
    @Override public String subtitle() { return "Thông tin kết nối và phiên đăng nhập của thiết bị này"; }
    @Override public Parent view() { return root; }
    @Override public void onShow() { render(); }
}
