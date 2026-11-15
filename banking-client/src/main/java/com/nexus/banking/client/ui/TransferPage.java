package com.nexus.banking.client.ui;

import com.nexus.banking.client.core.BankingClientCore;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionType;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/** Transfer form with beneficiary name lookup, a confirmation step and the safe money flow. */
final class TransferPage implements Page {
    private final UiContext ctx;
    private final VBox root = new VBox(18);
    private final TextField to = new TextField();
    private final TextField amount = new TextField();
    private final TextField note = new TextField();
    private final Label holder = new Label(" ");
    private final Label preview = new Label(" ");
    private final Label error = new Label();
    private final FlowPane recents = new FlowPane(8, 8);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(350));
    private volatile String resolvedFor = "";
    private volatile String resolvedName = null;

    TransferPage(UiContext ctx) {
        this.ctx = ctx;
        to.setPromptText("Số tài khoản người nhận");
        amount.setPromptText("0");
        amount.getStyleClass().add("input-big");
        note.setPromptText("Nội dung chuyển khoản");
        holder.getStyleClass().add("text-muted");
        preview.getStyleClass().add("text-muted");
        error.setStyle("-fx-text-fill: #FB7185;");
        error.setWrapText(true);
        to.textProperty().addListener((o, a, b) -> {
            String d = b == null ? "" : b.replaceAll("[^0-9]", "");
            if (!d.equals(b)) {
                to.setText(d);
                return;
            }
            resolvedName = null;
            holder.setText(" ");
            holder.setStyle("");
            error.setText("");
            debounce.playFromStart();
        });
        debounce.setOnFinished(e -> lookup());
        amount.textProperty().addListener((o, a, b) -> {
            BigDecimal v = Money.parse(b);
            preview.setText(v == null ? (b == null || b.isBlank() ? " " : "Số tiền không hợp lệ") : "= " + Money.format(v));
            error.setText("");
        });
        Button next = new Button("Tiếp tục");
        next.getStyleClass().add("btn-primary");
        next.setOnAction(e -> review());
        Button clear = new Button("Xóa");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> reset());
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox buttons = new HBox(10, g, clear, next);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label recentTitle = new Label("NGƯỜI NHẬN GẦN ĐÂY");
        recentTitle.getStyleClass().add("section-title");
        VBox form = new VBox(10, field("Tài khoản người nhận"), to, holder, recentTitle, recents, field("Số tiền (₫)"), amount, preview,
                field("Nội dung"), note, error, buttons);
        form.getStyleClass().add("surface");
        form.setPadding(new Insets(26));
        form.setMaxWidth(640);

        Label tip = new Label("Chuyển khoản là một bút toán kép: ghi nợ tài khoản của bạn và ghi có tài khoản người nhận trong CÙNG một giao dịch nguyên tử, "
                + "rồi tự động đồng bộ sang chi nhánh còn lại. Nếu mạng gián đoạn, hệ thống gửi lại với cùng mã yêu cầu nên không bao giờ trừ tiền 2 lần.");
        tip.setWrapText(true);
        tip.getStyleClass().add("text-muted");
        tip.setMaxWidth(640);
        root.setPadding(new Insets(26, 32, 26, 32));
        root.getChildren().addAll(form, tip);
        ctx.model.transactions.addListener((javafx.collections.ListChangeListener<TransactionDTO>) c -> rebuildRecents());
    }

    private static Label field(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("form-label");
        return l;
    }

    private void rebuildRecents() {
        Set<String> seen = new LinkedHashSet<>();
        for (TransactionDTO t : ctx.model.transactions) {
            if (t.getType() == TransactionType.TRANSFER && !t.getReferenceAccount().isBlank()) seen.add(t.getReferenceAccount());
            if (seen.size() >= 4) break;
        }
        recents.getChildren().clear();
        if (seen.isEmpty()) {
            Label none = new Label("Chưa có");
            none.getStyleClass().add("text-faint");
            recents.getChildren().add(none);
        }
        for (String acc : seen) {
            Button b = new Button(acc);
            b.getStyleClass().add("amount-chip");
            b.setOnAction(e -> to.setText(acc));
            recents.getChildren().add(b);
        }
    }

    private void lookup() {
        String acc = to.getText();
        if (acc == null || acc.length() < 6) return;
        AccountDTO me = ctx.model.account.get();
        if (me != null && me.getAccountNumber().equals(acc)) {
            holder.setText("Không thể chuyển cho chính mình");
            holder.setStyle("-fx-text-fill: #FB7185;");
            return;
        }
        holder.setText("Đang tra cứu...");
        ctx.core.lookupHolder(acc).thenAccept(r -> Platform.runLater(() -> {
            if (!acc.equals(to.getText())) return;                 // the user kept typing: stale answer
            if (r.isSuccess()) {
                resolvedFor = acc;
                resolvedName = r.getData();
                holder.setText("✓ " + r.getData().toUpperCase());
                holder.setStyle("-fx-text-fill: #34D399; -fx-font-weight: 700;");
            } else {
                resolvedName = null;
                holder.setText(r.getMessage());
                holder.setStyle("-fx-text-fill: #FB7185;");
            }
        }));
    }

    private void reset() {
        to.clear();
        amount.clear();
        note.clear();
        holder.setText(" ");
        error.setText("");
    }

    private void review() {
        String acc = to.getText() == null ? "" : to.getText().trim();
        BigDecimal v = Money.parse(amount.getText());
        AccountDTO me = ctx.model.account.get();
        String problem = null;
        if (acc.length() < 6) problem = "Nhập số tài khoản người nhận (6-20 chữ số)";
        else if (resolvedName == null || !acc.equals(resolvedFor)) problem = "Chưa xác nhận được tên người nhận - kiểm tra lại số tài khoản";
        else if (v == null) problem = "Vui lòng nhập số tiền hợp lệ";
        else if (Money.validateAmount(v) != null) problem = Money.validateAmount(v);
        else if (me != null && v.compareTo(me.getBalance()) > 0) problem = "Số dư không đủ: bạn chỉ có " + Money.format(me.getBalance());
        if (problem != null) {
            error.setText(problem);
            return;
        }
        String n = note.getText() == null ? "" : note.getText().trim();
        confirm(acc, resolvedName, v, n);
    }

    private void confirm(String acc, String name, BigDecimal v, String n) {
        Label title = new Label("Xác nhận chuyển khoản");
        title.getStyleClass().add("title-lg");
        VBox lines = new VBox(0, line("Đến tài khoản", acc), line("Người nhận", name.toUpperCase()), line("Số tiền", Money.format(v)),
                line("Phí giao dịch", "Miễn phí"), line("Nội dung", n.isBlank() ? "-" : n));
        Button ok = new Button("Xác nhận chuyển");
        ok.getStyleClass().add("btn-primary");
        Button cancel = new Button("Quay lại");
        cancel.getStyleClass().add("btn-secondary");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox buttons = new HBox(10, g, cancel, ok);
        VBox card = new VBox(14, title, lines, buttons);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(470);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable close = ctx.overlay(card);
        cancel.setOnAction(e -> close.run());
        ok.setOnAction(e -> {
            close.run();
            MoneyDialogs.run(ctx, "Chuyển khoản", () -> ctx.core.transfer(acc, v, n));
            reset();
        });
    }

    private static HBox line(String k, String v) {
        Label a = new Label(k);
        a.getStyleClass().add("text-muted");
        Label b = new Label(v);
        b.getStyleClass().add("title-md");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox h = new HBox(a, g, b);
        h.getStyleClass().add("receipt-line");
        return h;
    }

    @Override public String id() { return "transfer"; }
    @Override public String title() { return "Chuyển khoản"; }
    @Override public String subtitle() { return "Chuyển tiền nội bộ giữa các tài khoản NEXUS BANK, hiệu lực tức thì"; }
    @Override public Parent view() { return root; }
    @Override public void onShow() { rebuildRecents(); }
}
