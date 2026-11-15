package com.nexus.banking.client.ui;

import com.nexus.banking.client.core.BankingClientCore.Kind;
import com.nexus.banking.client.core.BankingClientCore.Pending;
import com.nexus.banking.client.core.BankingClientCore.Status;
import com.nexus.banking.client.core.BankingClientCore.TxOutcome;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Deposit/withdraw dialogs and the shared "processing -> receipt / error / safe retry" flow for every money movement. */
final class MoneyDialogs {
    private static final long[] QUICK = {100_000, 500_000, 1_000_000, 2_000_000, 5_000_000};

    private MoneyDialogs() {
    }

    // ---------------------------------------------------------------------------------- amount dialog

    static void amountDialog(UiContext ctx, Kind kind) {
        boolean deposit = kind == Kind.DEPOSIT;
        AccountDTO acc = ctx.model.account.get();
        Label title = new Label(deposit ? "Nạp tiền vào tài khoản" : "Rút tiền");
        title.getStyleClass().add("title-lg");
        Label sub = new Label(acc == null ? "" : "Số dư hiện tại: " + Money.format(acc.getBalance()));
        sub.getStyleClass().add("text-muted");
        TextField amount = new TextField();
        amount.setPromptText("0");
        amount.getStyleClass().add("input-big");
        Label preview = new Label(" ");
        preview.getStyleClass().add("text-muted");
        TextField note = new TextField();
        note.setPromptText("Ghi chú (không bắt buộc)");
        Label error = new Label();
        error.setStyle("-fx-text-fill: #FB7185;");
        error.setWrapText(true);
        FlowPane chips = new FlowPane(8, 8);
        for (long q : QUICK) {
            Button c = new Button(Money.format(BigDecimal.valueOf(q)).replace(" ₫", ""));
            c.getStyleClass().add("amount-chip");
            c.setOnAction(e -> amount.setText(String.valueOf(q)));
            chips.getChildren().add(c);
        }
        amount.textProperty().addListener((o, a, b) -> {
            BigDecimal v = Money.parse(b);
            preview.setText(v == null ? (b == null || b.isBlank() ? " " : "Số tiền không hợp lệ") : "= " + Money.format(v));
            error.setText("");
        });
        Button ok = new Button(deposit ? "Xác nhận nạp tiền" : "Xác nhận rút tiền");
        ok.getStyleClass().add("btn-primary");
        Button cancel = new Button("Hủy");
        cancel.getStyleClass().add("btn-secondary");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(12, title, sub, label("Số tiền (₫)"), amount, preview, chips, label("Ghi chú"), note, error, buttons);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(480);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable close = ctx.overlay(card);
        cancel.setOnAction(e -> close.run());
        Runnable submit = () -> {
            BigDecimal v = Money.parse(amount.getText());
            String problem = v == null ? "Vui lòng nhập số tiền hợp lệ (ví dụ 500.000)" : Money.validateAmount(v);
            if (problem != null) {
                error.setText(problem);
                return;
            }
            if (!deposit && acc != null && v.compareTo(acc.getBalance()) > 0) {
                error.setText("Số dư không đủ: bạn chỉ có " + Money.format(acc.getBalance()));
                return;
            }
            close.run();
            String n = note.getText() == null ? "" : note.getText().trim();
            run(ctx, deposit ? "Nạp tiền" : "Rút tiền",
                    () -> deposit ? ctx.core.deposit(v, n) : ctx.core.withdraw(v, n));
        };
        ok.setOnAction(e -> submit.run());
        amount.setOnAction(e -> submit.run());
        Platform.runLater(amount::requestFocus);
    }

    private static Label label(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("form-label");
        return l;
    }

    // ----------------------------------------------------------------------------------- shared flow

    /** Runs a money operation with a progress overlay, then shows a receipt, a refusal, or the safe-retry dialog. */
    static void run(UiContext ctx, String what, Supplier<CompletableFuture<TxOutcome>> start) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(46, 46);
        Label l = new Label(what + ": đang xử lý...");
        l.getStyleClass().add("title-md");
        Label hint = new Label("Đừng đóng ứng dụng. Nếu mạng chập chờn, hệ thống tự gửi lại với cùng mã yêu cầu - tiền không bị trừ 2 lần.");
        hint.getStyleClass().add("text-muted");
        hint.setWrapText(true);
        hint.setMaxWidth(340);
        VBox card = new VBox(14, spin, l, hint);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(420);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable close = ctx.overlay(card);
        start.get().whenComplete((o, err) -> Platform.runLater(() -> {
            close.run();
            if (err != null || o == null) {
                failure(ctx, what, new TxOutcome(Status.UNKNOWN, null, "ERROR", err == null ? "Lỗi không xác định" : String.valueOf(err.getMessage()), false, null));
            } else if (o.status() == Status.SUCCESS) {
                receipt(ctx, what, o);
            } else {
                failure(ctx, what, o);
            }
        }));
    }

    static void receipt(UiContext ctx, String what, TxOutcome o) {
        TransactionDTO t = o.transaction();
        Label ok = new Label("✓");
        ok.setStyle("-fx-text-fill: #34D399; -fx-font-size: 34px; -fx-font-weight: 900;");
        Label title = new Label(o.replayed() ? what + " - đã được ghi nhận trước đó" : what + " thành công");
        title.getStyleClass().add("title-lg");
        title.setWrapText(true);
        VBox lines = new VBox(0);
        lines.getChildren().add(line("Số tiền", Money.format(t.getAmount()), true));
        if (!t.getReferenceAccount().isBlank()) lines.getChildren().add(line("Tài khoản đối ứng", t.getReferenceAccount(), false));
        lines.getChildren().add(line("Số dư sau giao dịch", Money.format(t.getBalanceAfter()), false));
        lines.getChildren().add(line("Thời gian", Fmt.dateTime(t.getCreatedAt()), false));
        lines.getChildren().add(line("Ghi nhận tại", ctx.core.getBranchName().isBlank() ? t.getOriginServerId() : t.getOriginServerId() + " · " + ctx.core.getBranchName(), false));
        lines.getChildren().add(line("Mã giao dịch", t.getTransactionId().length() > 18 ? t.getTransactionId().substring(0, 18) + "…" : t.getTransactionId(), false));
        lines.getChildren().add(line("Đồng bộ chi nhánh kia", t.getSyncState().getLabel(), false));
        Label note = new Label(o.replayed()
                ? "Yêu cầu này đã được thực hiện từ lần gửi trước (mạng bị gián đoạn). Hệ thống KHÔNG thực hiện lần 2 - số dư chỉ thay đổi một lần."
                : "Biên lai điện tử. Giao dịch được ghi vào sổ cái và tự động đồng bộ sang chi nhánh còn lại.");
        note.getStyleClass().add("text-muted");
        note.setWrapText(true);
        Button done = new Button("Hoàn tất");
        done.getStyleClass().add("btn-primary");
        done.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(12, ok, title, lines, note, done);
        card.setAlignment(Pos.TOP_LEFT);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(470);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable close = ctx.overlay(card);
        done.setOnAction(e -> close.run());
    }

    private static HBox line(String k, String v, boolean strong) {
        Label a = new Label(k);
        a.getStyleClass().add("text-muted");
        Label b = new Label(v);
        if (strong) b.getStyleClass().add("title-md");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox h = new HBox(a, g, b);
        h.getStyleClass().add("receipt-line");
        return h;
    }

    static void failure(UiContext ctx, String what, TxOutcome o) {
        boolean unknown = o.status() == Status.UNKNOWN;
        Label icon = new Label(unknown ? "?" : "✕");
        icon.setStyle("-fx-text-fill: " + (unknown ? "#FBBF24" : "#FB7185") + "; -fx-font-size: 34px; -fx-font-weight: 900;");
        Label title = new Label(unknown ? what + ": chưa xác định được kết quả" : what + " không thành công");
        title.getStyleClass().add("title-lg");
        title.setWrapText(true);
        Label msg = new Label(o.message());
        msg.setWrapText(true);
        msg.getStyleClass().add("text-muted");
        Button close = new Button("Đóng");
        close.getStyleClass().add("btn-secondary");
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(12, icon, title, msg, buttons);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(470);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        Runnable closer = ctx.overlay(card);
        close.setOnAction(e -> closer.run());
        if (unknown && o.pending() != null) {
            Pending p = o.pending();
            Label ref = new Label("Mã yêu cầu: " + p.requestId());
            ref.getStyleClass().addAll("text-faint", "text-mono");
            card.getChildren().add(card.getChildren().size() - 1, ref);
            Button retry = new Button("Thử lại an toàn");
            retry.getStyleClass().add("btn-primary");
            retry.setOnAction(e -> {
                closer.run();
                run(ctx, what, () -> ctx.core.retry(p));
            });
            buttons.getChildren().addAll(close, retry);
        } else {
            buttons.getChildren().add(close);
        }
    }

    static List<Kind> kinds() {
        return List.of(Kind.DEPOSIT, Kind.WITHDRAW, Kind.TRANSFER);
    }
}
