package com.nexus.banking.client.ui;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.core.BankingClientCore;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Objects;

/** Owns the window: switches between sign-in and the main shell and adapts core events to the JavaFX thread. */
public final class BankingUi {
    private final Stage stage;
    private final ClientModel model = new ClientModel();
    private final StackPane host = new StackPane();
    private BankingClientCore core;
    private UiContext ctx;
    private AppShell shell;

    public BankingUi(Stage stage) {
        this.stage = stage;
        Scene scene = new Scene(host, 1240, 780);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/nexus/banking/client/css/banking.css")).toExternalForm());
        stage.setScene(scene);
        stage.setTitle("NEXUS BANKING");
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
    }

    public void bind(BankingClientCore core) {
        this.core = core;
        this.ctx = new UiContext(core, model, host, this::navigate);
    }

    /** Adapter: every core event is moved to the JavaFX thread before it touches the UI. */
    public BankingClientCore.Listener listener() {
        return new BankingClientCore.Listener() {
            @Override
            public void onState(ConnectionState state, String status) {
                Platform.runLater(() -> {
                    model.state.set(state);
                    model.statusText.set(status);
                });
            }

            @Override
            public void onLogin(LoginResultDTO login, boolean pushActive) {
                Platform.runLater(() -> {
                    model.branchName.set(login.getBranchName());
                    model.serverId.set(login.getServerId());
                    model.pushActive.set(pushActive);
                    stage.setTitle("NEXUS BANKING - " + login.getBranchName());
                });
            }

            @Override
            public void onAccount(AccountDTO account, boolean viaPush) {
                Platform.runLater(() -> {
                    AccountDTO old = model.account.get();
                    if (old != null && old.getAccountNumber().equals(account.getAccountNumber()) && old.getBalance().compareTo(account.getBalance()) == 0
                            && old.getStatus() == account.getStatus() && old.getDailyLimit().compareTo(account.getDailyLimit()) == 0
                            && old.getOwnerName().equals(account.getOwnerName())) {
                        return;
                    }
                    model.account.set(account);
                });
            }

            @Override
            public void onTransactions(List<TransactionDTO> list) {
                Platform.runLater(() -> {
                    if (sameLedger(model.transactions, list)) return;
                    model.transactions.setAll(list);
                });
            }

            @Override
            public void onNotification(BalanceNotificationDTO n) {
                Platform.runLater(() -> {
                    if (ctx == null) return;
                    boolean in = n.getDelta().signum() > 0;
                    String who = n.getCounterparty().isBlank() ? "" : (in ? " từ " : " đến ") + n.getCounterparty();
                    String where = n.isFromPeerBranch() ? "Ghi nhận tại chi nhánh còn lại, đã đồng bộ về " : "Ghi nhận tại ";
                    ctx.toasts.show(Money.formatSigned(n.getDelta()), (in ? "Tiền vào tài khoản" : "Tiền ra khỏi tài khoản") + who
                            + "\nSố dư mới: " + Money.format(n.getNewBalance()) + "\n" + where + n.getServedByServerId(), in ? "in" : "out");
                });
            }

            @Override
            public void onEvicted(String reason) {
                Platform.runLater(() -> evicted(reason));
            }
        };
    }

    private static boolean sameLedger(List<TransactionDTO> a, List<TransactionDTO> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getTransactionId().equals(b.get(i).getTransactionId()) || a.get(i).getSyncState() != b.get(i).getSyncState()
                    || a.get(i).getBalanceAfter().compareTo(b.get(i).getBalanceAfter()) != 0) {
                return false;
            }
        }
        return true;
    }

    public void showLogin(String notice) {
        host.getChildren().clear();
        model.reset();
        LoginView lv = new LoginView(core, this::showShell, notice);
        host.getChildren().add(lv.view());
        stage.setTitle("NEXUS BANKING");
    }

    private void showShell() {
        host.getChildren().clear();
        ctx = new UiContext(core, model, host, this::navigate);
        shell = new AppShell(ctx, List.of(new DashboardPage(ctx), new TransferPage(ctx), new HistoryPage(ctx), new ClusterPage(ctx),
                new SettingsPage(ctx)));
        host.getChildren().addAll(shell.view(), ctx.toasts);
        StackPane.setAlignment(ctx.toasts, Pos.TOP_RIGHT);
    }

    private void navigate(String id) {
        if ("logout".equals(id)) {
            core.logout();
            showLogin(null);
        } else if (shell != null) {
            shell.show(id);
        }
    }

    private void evicted(String reason) {
        if (ctx == null || shell == null) return;
        Label icon = new Label("!");
        icon.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 36px; -fx-font-weight: 900;");
        Label title = new Label("Phiên đăng nhập đã bị đóng");
        title.getStyleClass().add("title-lg");
        Label msg = new Label(reason);
        msg.setWrapText(true);
        msg.getStyleClass().add("text-muted");
        Button ok = new Button("Về màn hình đăng nhập");
        ok.getStyleClass().add("btn-primary");
        ok.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(12, icon, title, msg, ok);
        card.getStyleClass().add("dialog-card");
        card.setMaxWidth(460);
        card.setMaxHeight(VBox.USE_PREF_SIZE);
        ctx.closeAllOverlays();
        ctx.overlay(card);
        ok.setOnAction(e -> showLogin("Phiên trước đã bị đóng: " + reason));
        ClientPreferences.lastAccount();
    }
}
