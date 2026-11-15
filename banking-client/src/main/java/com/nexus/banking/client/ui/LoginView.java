package com.nexus.banking.client.ui;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.core.BankingClientCore;
import com.nexus.banking.client.core.BankingClientCore.Endpoint;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Sign-in screen: account + PIN, preferred branch, endpoints, and demo shortcuts. */
final class LoginView {
    private record Demo(String name, String account) {
    }

    private static final List<Demo> DEMOS = List.of(new Demo("An (HN)", "1001000001"), new Demo("Bình (HN)", "1001000002"),
            new Demo("Cường (HCM)", "2002000001"), new Demo("Dũng (HCM)", "2002000002"), new Demo("Em", "3003000001"),
            new Demo("Bị khóa", "4004000001"));

    private final HBox root = new HBox();
    private final TextField account = new TextField();
    private final PasswordField pin = new PasswordField();
    private final TextField nodeA = new TextField();
    private final TextField nodeB = new TextField();
    private final CheckBox ssl = new CheckBox("Mã hóa TLS / SSL");
    private final ToggleGroup branch = new ToggleGroup();
    private final Label error = new Label();
    private final VBox errorBox = new VBox(error);
    private final Button login = new Button("Đăng nhập");
    private final BankingClientCore core;
    private final Runnable onSuccess;

    LoginView(BankingClientCore core, Runnable onSuccess, String notice) {
        this.core = core;
        this.onSuccess = onSuccess;
        root.getChildren().addAll(brandPanel(), formPanel());
        if (notice != null && !notice.isBlank()) showError(notice, true);
    }

    Parent view() {
        return root;
    }

    private Region brandPanel() {
        Label mark = new Label("N");
        mark.getStyleClass().add("brand-mark-text");
        mark.setStyle("-fx-font-size: 30px;");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("brand-mark");
        badge.setMinSize(58, 58);
        badge.setMaxSize(58, 58);
        Label title = new Label("NEXUS BANKING");
        title.getStyleClass().add("title-xl");
        Label tag = new Label("Ngân hàng điện tử phân tán.\nHai chi nhánh, một sổ cái, không bao giờ trừ tiền hai lần.");
        tag.getStyleClass().add("text-muted");
        tag.setStyle("-fx-font-size: 16px;");
        tag.setWrapText(true);
        MeshCanvas mesh = new MeshCanvas();
        mesh.update(0, true, 0);
        mesh.setPrefHeight(230);
        VBox features = new VBox(8, feature("⇄", "Active-Active: cả hai chi nhánh cùng nhận giao dịch"),
                feature("✓", "Idempotency: gửi lại yêu cầu không trừ tiền lần 2"),
                feature("◔", "Mất mạng giữa hai chi nhánh? Tự động bù sổ cái khi nối lại"),
                feature("🔒", "Một tài khoản - một phiên đăng nhập trên toàn cụm"));
        VBox left = new VBox(22, badge, title, tag, mesh, features);
        left.setPadding(new Insets(48, 44, 40, 52));
        left.setStyle("-fx-background-color: linear-gradient(to bottom right, #0B1530, #101B3D 60%, #162250);");
        left.setMinWidth(480);
        left.setPrefWidth(560);
        HBox.setHgrow(left, Priority.SOMETIMES);
        return left;
    }

    private static HBox feature(String icon, String text) {
        Label i = new Label(icon);
        i.setMinWidth(26);
        i.setStyle("-fx-text-fill: #22D3EE; -fx-font-weight: 800;");
        Label t = new Label(text);
        t.getStyleClass().add("text-muted");
        t.setWrapText(true);
        return new HBox(8, i, t);
    }

    private Region formPanel() {
        Label h = new Label("Đăng nhập");
        h.getStyleClass().add("title-lg");
        Label sub = new Label("Nhập số tài khoản và mã PIN của bạn");
        sub.getStyleClass().add("text-muted");
        account.setPromptText("Số tài khoản (ví dụ 1001000001)");
        account.setText(ClientPreferences.lastAccount());
        account.textProperty().addListener((o, a, b) -> {
            String d = b == null ? "" : b.replaceAll("[^0-9]", "");
            if (!d.equals(b)) account.setText(d);
        });
        pin.setPromptText("Mã PIN");
        pin.setOnAction(e -> doLogin());
        account.setOnAction(e -> pin.requestFocus());

        ToggleButton hn = new ToggleButton("Hà Nội");
        ToggleButton hcm = new ToggleButton("TP.HCM");
        for (ToggleButton b : List.of(hn, hcm)) {
            b.setToggleGroup(branch);
            b.getStyleClass().add("toggle-chip");
        }
        (ClientPreferences.preferredBranch() == 1 ? hcm : hn).setSelected(true);
        branch.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) a.setSelected(true);
        });
        HBox branchRow = new HBox(8, hn, hcm);

        nodeA.setText(ClientPreferences.nodeA());
        nodeB.setText(ClientPreferences.nodeB());
        ssl.setSelected(ClientPreferences.sslEnabled());
        VBox adv = new VBox(8, label("Chi nhánh A - Hà Nội (host:port)"), nodeA, label("Chi nhánh B - TP.HCM (host:port)"), nodeB, ssl);
        TitledPane advanced = new TitledPane("Kết nối nâng cao", adv);
        advanced.setExpanded(false);
        advanced.setAnimated(false);

        login.getStyleClass().add("btn-primary");
        login.setMaxWidth(Double.MAX_VALUE);
        login.setDefaultButton(true);
        login.setOnAction(e -> doLogin());

        errorBox.setVisible(false);
        errorBox.setManaged(false);
        error.setWrapText(true);

        VBox demo = new VBox(8);
        if (ClientPreferences.showDemoAccounts()) {
            Label dl = new Label("TÀI KHOẢN DEMO (PIN 123456)");
            dl.getStyleClass().add("section-title");
            FlowPane chips = new FlowPane(8, 8);
            for (Demo d : DEMOS) {
                Button b = new Button(d.name());
                b.getStyleClass().add("amount-chip");
                b.setOnAction(e -> {
                    account.setText(d.account());
                    pin.setText("123456");
                    pin.requestFocus();
                });
                chips.getChildren().add(b);
            }
            demo.getChildren().addAll(dl, chips);
        }

        VBox form = new VBox(12, h, sub, label("Số tài khoản"), account, label("Mã PIN"), pin, label("Chi nhánh ưu tiên"), branchRow,
                advanced, errorBox, login, demo);
        form.setMaxWidth(420);
        form.setPadding(new Insets(10));
        StackPane holder = new StackPane(form);
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(30));
        HBox.setHgrow(holder, Priority.ALWAYS);
        return holder;
    }

    private static Label label(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("form-label");
        return l;
    }

    private void showError(String msg, boolean warn) {
        error.setText(msg);
        errorBox.getStyleClass().setAll(warn ? "banner-warn" : "banner-error");
        errorBox.setVisible(true);
        errorBox.setManaged(true);
    }

    private Endpoint parse(String label, String text) {
        String[] p = text.trim().split(":");
        int port;
        try {
            port = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1099;
        } catch (NumberFormatException e) {
            port = -1;
        }
        return new Endpoint(label, p[0].trim(), port);
    }

    private void doLogin() {
        errorBox.setVisible(false);
        errorBox.setManaged(false);
        String acc = account.getText() == null ? "" : account.getText().trim();
        String p = pin.getText() == null ? "" : pin.getText();
        if (acc.length() < 6) {
            showError("Vui lòng nhập số tài khoản (6-20 chữ số).", false);
            return;
        }
        if (p.length() < 4) {
            showError("Mã PIN gồm 4-8 chữ số.", false);
            return;
        }
        Endpoint a = parse("Hà Nội", nodeA.getText());
        Endpoint b = parse("TP.HCM", nodeB.getText());
        if (a.port() < 1 || b.port() < 1 || a.host().isEmpty() || b.host().isEmpty()) {
            showError("Địa chỉ chi nhánh phải có dạng host:port, ví dụ 192.168.1.10:1099.", false);
            return;
        }
        int preferred = branch.getToggles().indexOf(branch.getSelectedToggle());
        ClientPreferences.setSslEnabled(ssl.isSelected());
        login.setDisable(true);
        login.setText("Đang kết nối...");
        core.login(List.of(a, b), preferred, acc, p).whenComplete((r, err) -> Platform.runLater(() -> {
            login.setDisable(false);
            login.setText("Đăng nhập");
            if (err != null || r == null) {
                showError("Không đăng nhập được: " + (err == null ? "không có phản hồi" : err.getMessage()), false);
            } else if (!r.isSuccess()) {
                showError(r.getMessage(), false);
                pin.clear();
            } else {
                ClientPreferences.saveConnection(nodeA.getText().trim(), nodeB.getText().trim(), preferred, acc);
                pin.clear();
                onSuccess.run();
            }
        }));
    }
}
