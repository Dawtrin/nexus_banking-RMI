package com.nexus.banking.client.ui;

import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.AccountDTO;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sidebar navigation + page header (title and live connection chips) + the current page. */
final class AppShell {
    private final UiContext ctx;
    private final BorderPane root = new BorderPane();
    private final StackPane pageHolder = new StackPane();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, ToggleButton> nav = new LinkedHashMap<>();
    private final Label pageTitle = new Label();
    private final Label pageSub = new Label();
    private final HBox chips = new HBox(8);
    private final Label userName = new Label();
    private final Label userAcc = new Label();
    private final Label avatar = new Label();
    private String current;

    AppShell(UiContext ctx, List<Page> list) {
        this.ctx = ctx;
        for (Page p : list) pages.put(p.id(), p);
        root.setLeft(sidebar());
        pageTitle.getStyleClass().add("title-lg");
        pageSub.getStyleClass().add("text-muted");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox header = new HBox(14, new VBox(2, pageTitle, pageSub), g, chips);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 32, 16, 32));
        header.getStyleClass().add("page-header");
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(pageHolder);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        for (Page p : pages.values()) {
            Parent v = p.view();
            v.setVisible(false);
            v.setManaged(false);
            pageHolder.getChildren().add(v);
        }
        VBox content = new VBox(header, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.setCenter(content);
        ctx.model.state.addListener((o, a, b) -> renderChips());
        ctx.model.branchName.addListener((o, a, b) -> renderChips());
        ctx.model.pushActive.addListener((o, a, b) -> renderChips());
        ctx.model.account.addListener((o, a, b) -> renderUser());
        renderChips();
        renderUser();
        show(pages.keySet().iterator().next());
    }

    Parent view() {
        return root;
    }

    private VBox sidebar() {
        Label mark = new Label("N");
        mark.getStyleClass().add("brand-mark-text");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("brand-mark");
        badge.setMinSize(36, 36);
        badge.setMaxSize(36, 36);
        Label brand = new Label("NEXUS BANKING");
        brand.getStyleClass().add("title-md");
        HBox brandRow = new HBox(10, badge, brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(22, 20, 18, 20));

        avatar.setMinSize(40, 40);
        avatar.setAlignment(Pos.CENTER);
        avatar.setStyle("-fx-background-radius: 20; -fx-background-color: linear-gradient(to bottom right, #22D3EE, #6366F1); -fx-text-fill: #04101F; -fx-font-weight: 900;");
        userName.getStyleClass().add("title-md");
        userAcc.getStyleClass().addAll("text-faint", "text-mono");
        HBox user = new HBox(10, avatar, new VBox(1, userName, userAcc));
        user.setAlignment(Pos.CENTER_LEFT);
        user.setPadding(new Insets(10, 14, 10, 14));
        user.getStyleClass().add("surface-2");
        VBox.setMargin(user, new Insets(0, 14, 14, 14));

        ToggleGroup group = new ToggleGroup();
        VBox items = new VBox(4);
        items.setPadding(new Insets(0, 14, 0, 14));
        String[][] defs = {{"dashboard", "◉  Tổng quan"}, {"transfer", "⇄  Chuyển khoản"}, {"history", "☰  Lịch sử giao dịch"},
                {"cluster", "◎  Mạng lưới chi nhánh"}, {"settings", "⚙  Cài đặt & bảo mật"}};
        for (String[] d : defs) {
            ToggleButton b = new ToggleButton(d[1]);
            b.getStyleClass().add("nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setToggleGroup(group);
            b.setOnAction(e -> show(d[0]));
            nav.put(d[0], b);
            items.getChildren().add(b);
        }
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) a.setSelected(true);
        });
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Button out = new Button("Đăng xuất");
        out.getStyleClass().add("btn-secondary");
        out.setMaxWidth(Double.MAX_VALUE);
        out.setOnAction(e -> ctx.navigate.accept("logout"));
        VBox foot = new VBox(out);
        foot.setPadding(new Insets(14));
        VBox side = new VBox(brandRow, user, items, grow, foot);
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(250);
        side.setMinWidth(250);
        return side;
    }

    void show(String id) {
        Page p = pages.get(id);
        if (p == null) return;
        if (current != null) {
            Parent old = pages.get(current).view();
            old.setVisible(false);
            old.setManaged(false);
        }
        current = id;
        p.view().setVisible(true);
        p.view().setManaged(true);
        pageTitle.setText(p.title());
        pageSub.setText(p.subtitle());
        ToggleButton b = nav.get(id);
        if (b != null) b.setSelected(true);
        p.onShow();
    }

    private void renderUser() {
        AccountDTO a = ctx.model.account.get();
        if (a == null) return;
        userName.setText(a.getOwnerName());
        userAcc.setText(a.getAccountNumber());
        avatar.setText(Fmt.initials(a.getOwnerName()));
    }

    private void renderChips() {
        chips.getChildren().clear();
        ConnectionState s = ctx.model.state.get();
        String kind = s == ConnectionState.CONNECTED ? "ok" : s == ConnectionState.RECONNECTING || s == ConnectionState.CONNECTING ? "warn" : "danger";
        chips.getChildren().add(Fmt.chip("● " + s.getLabel(), kind));
        if (!ctx.model.branchName.get().isBlank()) chips.getChildren().add(Fmt.chip(ctx.model.branchName.get(), "info"));
        chips.getChildren().add(Fmt.chip(ctx.model.pushActive.get() ? "Realtime (RMI callback)" : "Polling", ctx.model.pushActive.get() ? "ok" : "neutral"));
    }
}
