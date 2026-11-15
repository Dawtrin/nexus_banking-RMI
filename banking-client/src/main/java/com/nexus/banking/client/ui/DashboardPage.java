package com.nexus.banking.client.ui;

import com.nexus.banking.client.core.BankingClientCore.Kind;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Home screen: bank card, live balance, daily limit meter, quick actions and the latest ledger entries. */
final class DashboardPage implements Page {
    private final UiContext ctx;
    private final VBox root = new VBox(22);
    private final BankCard card = new BankCard();
    private final AnimatedAmount balance = new AnimatedAmount();
    private final Label limitText = new Label();
    private final ProgressBar limitBar = new ProgressBar(0);
    private final Label statusLine = new Label();
    private final VBox recent = new VBox();

    DashboardPage(UiContext ctx) {
        this.ctx = ctx;
        Label avail = new Label("SỐ DƯ KHẢ DỤNG");
        avail.getStyleClass().add("section-title");
        Label limitTitle = new Label("HẠN MỨC RÚT / CHUYỂN HÔM NAY");
        limitTitle.getStyleClass().add("section-title");
        limitBar.setMaxWidth(Double.MAX_VALUE);
        limitBar.setPrefHeight(14);
        limitText.getStyleClass().add("text-muted");
        statusLine.getStyleClass().add("text-faint");
        statusLine.setWrapText(true);
        VBox balanceBox = new VBox(8, avail, balance, statusLine, spacer(8), limitTitle, limitBar, limitText);
        balanceBox.getStyleClass().add("surface");
        balanceBox.setPadding(new Insets(24));
        HBox.setHgrow(balanceBox, Priority.ALWAYS);
        balanceBox.setMinHeight(220);
        HBox top = new HBox(22, card, balanceBox);

        HBox actions = new HBox(16,
                action("↓", "Nạp tiền", "Gửi tiền vào tài khoản", "deposit", () -> MoneyDialogs.amountDialog(ctx, Kind.DEPOSIT)),
                action("↑", "Rút tiền", "Rút tại quầy / ATM", "withdraw", () -> MoneyDialogs.amountDialog(ctx, Kind.WITHDRAW)),
                action("⇄", "Chuyển khoản", "Đến tài khoản khác", "transfer", () -> ctx.navigate.accept("transfer")));
        for (var n : actions.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        Label rt = new Label("Giao dịch gần đây");
        rt.getStyleClass().add("title-md");
        Button all = new Button("Xem tất cả");
        all.getStyleClass().add("btn-ghost");
        all.setOnAction(e -> ctx.navigate.accept("history"));
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox rh = new HBox(rt, g, all);
        rh.setAlignment(Pos.CENTER_LEFT);
        VBox recentBox = new VBox(6, rh, recent);
        recentBox.getStyleClass().add("surface");
        recentBox.setPadding(new Insets(20, 24, 12, 24));

        root.setPadding(new Insets(26, 32, 26, 32));
        root.getChildren().addAll(top, actions, recentBox);

        ctx.model.account.addListener((o, a, b) -> refresh(true));
        ctx.model.transactions.addListener((javafx.collections.ListChangeListener<TransactionDTO>) c -> refresh(false));
        ctx.model.branchName.addListener((o, a, b) -> refresh(false));
        refresh(false);
    }

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    private Button action(String glyph, String title, String sub, String style, Runnable r) {
        Label g = new Label(glyph);
        g.getStyleClass().addAll("action-icon", "action-icon-" + style);
        g.setStyle("-fx-font-size: 22px; -fx-font-weight: 900;");
        StackPane icon = new StackPane(g);
        Label t = new Label(title);
        t.getStyleClass().add("title-md");
        Label s = new Label(sub);
        s.getStyleClass().add("text-muted");
        VBox text = new VBox(2, t, s);
        HBox h = new HBox(14, icon, text);
        h.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(h);
        b.getStyleClass().add("quick-action");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> r.run());
        return b;
    }

    private void refresh(boolean animate) {
        AccountDTO a = ctx.model.account.get();
        if (a != null) {
            card.show(a);
            balance.set(a.getBalance(), animate);
            BigDecimal spent = spentToday(ctx.model.transactions);
            BigDecimal limit = a.getDailyLimit();
            double frac = limit.signum() == 0 ? 0 : Math.min(1.0, spent.doubleValue() / limit.doubleValue());
            limitBar.setProgress(frac);
            limitBar.getStyleClass().remove("limit-high");
            if (frac > 0.8) limitBar.getStyleClass().add("limit-high");
            BigDecimal left = limit.subtract(spent).max(BigDecimal.ZERO);
            limitText.setText("Đã dùng " + Money.format(spent) + " / " + Money.format(limit) + "  ·  còn lại " + Money.format(left));
            statusLine.setText((a.isActive() ? "Tài khoản đang hoạt động" : "Tài khoản đang bị khóa") + " · phục vụ bởi " + ctx.model.branchName.get());
        }
        List<TransactionDTO> txs = ctx.model.transactions;
        recent.getChildren().clear();
        if (txs.isEmpty()) {
            Label none = new Label("Chưa có giao dịch nào. Hãy thử nạp tiền hoặc chuyển khoản.");
            none.getStyleClass().add("text-muted");
            none.setPadding(new Insets(14, 0, 14, 0));
            recent.getChildren().add(none);
        }
        for (int i = 0; i < Math.min(5, txs.size()); i++) recent.getChildren().add(row(txs.get(i)));
    }

    static BigDecimal spentToday(List<TransactionDTO> txs) {
        LocalDate today = LocalDate.now(Fmt.ZONE);
        BigDecimal sum = BigDecimal.ZERO;
        for (TransactionDTO t : txs) {
            if (t.getType().isOutgoing() && t.getCreatedAt().atZone(Fmt.ZONE).toLocalDate().equals(today)) sum = sum.add(t.getAmount());
        }
        return sum;
    }

    private HBox row(TransactionDTO t) {
        Label ic = new Label(Fmt.typeIcon(t.getType()));
        ic.getStyleClass().add("tx-icon");
        ic.setStyle("-fx-background-color: " + Fmt.typeTint(t.getType()) + "; -fx-text-fill: " + Fmt.typeColor(t.getType()) + "; -fx-font-weight: 900; -fx-font-size: 16px;");
        Label title = new Label(Fmt.title(t));
        title.getStyleClass().add("title-md");
        Label sub = new Label(Fmt.shortTime(t.getCreatedAt()) + (t.getNote().isBlank() ? "" : "  ·  " + t.getNote()));
        sub.getStyleClass().add("text-muted");
        VBox mid = new VBox(2, title, sub);
        Label amount = new Label(Money.formatSigned(t.getSignedAmount()));
        amount.getStyleClass().add(t.getType().isCredit() ? "amount-in" : "amount-out");
        VBox right = new VBox(4, amount, Fmt.syncChip(t.getSyncState()));
        right.setAlignment(Pos.CENTER_RIGHT);
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox h = new HBox(14, ic, mid, g, right);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("tx-row");
        return h;
    }

    @Override public String id() { return "dashboard"; }
    @Override public String title() { return "Tổng quan"; }
    @Override public String subtitle() { return "Số dư cập nhật theo thời gian thực qua RMI callback"; }
    @Override public Parent view() { return root; }
}
