package com.nexus.banking.client.ui;

import com.nexus.banking.client.core.BankingClientCore.Endpoint;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionSyncState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Shows the two active/active branches, which one serves this device, and lets the customer fail over by hand. */
final class ClusterPage implements Page {
    private final UiContext ctx;
    private final VBox root = new VBox(20);
    private final MeshCanvas mesh = new MeshCanvas();
    private final HBox cards = new HBox(18);
    private final Label info = new Label();
    private final Label result = new Label();

    ClusterPage(UiContext ctx) {
        this.ctx = ctx;
        VBox meshBox = new VBox(mesh);
        meshBox.getStyleClass().add("surface");
        meshBox.setPadding(new Insets(14));
        info.setWrapText(true);
        info.getStyleClass().add("text-muted");
        result.setWrapText(true);
        result.getStyleClass().add("text-muted");
        root.setPadding(new Insets(26, 32, 26, 32));
        root.getChildren().addAll(meshBox, cards, info, result);
        ctx.model.branchName.addListener((o, a, b) -> render());
        ctx.model.state.addListener((o, a, b) -> render());
        ctx.model.transactions.addListener((javafx.collections.ListChangeListener<TransactionDTO>) c -> render());
        render();
    }

    private void render() {
        List<Endpoint> eps = ctx.core.getEndpoints();
        int serving = ctx.core.getEndpointIndex();
        boolean connected = ctx.model.state.get() == ConnectionState.CONNECTED;
        long pending = ctx.model.transactions.stream().filter(t -> t.getSyncState() == TransactionSyncState.PENDING).count();
        mesh.update(serving, connected && pending == 0 || connected, (int) pending);
        cards.getChildren().clear();
        for (int i = 0; i < eps.size(); i++) {
            Endpoint e = eps.get(i);
            boolean me = i == serving && connected;
            Label name = new Label(e.label());
            name.getStyleClass().add("title-md");
            Label addr = new Label(e.host() + ":" + e.port());
            addr.getStyleClass().addAll("text-muted", "text-mono");
            Label chip = me ? Fmt.chip("Đang phục vụ bạn", "ok") : Fmt.chip("Sẵn sàng thay thế", "neutral");
            VBox card = new VBox(8, name, addr, chip);
            if (!me) {
                Button sw = new Button("Chuyển sang chi nhánh này");
                sw.getStyleClass().add("btn-secondary");
                sw.setDisable(!connected);
                sw.setOnAction(ev -> switchBranch(sw));
                card.getChildren().add(sw);
            }
            card.getStyleClass().add("surface");
            card.setPadding(new Insets(20));
            HBox.setHgrow(card, Priority.ALWAYS);
            card.setMaxWidth(Double.MAX_VALUE);
            cards.getChildren().add(card);
        }
        info.setText("Cả hai chi nhánh đều nhận giao dịch độc lập (active-active) và đồng bộ sổ cái cho nhau. Nếu chi nhánh đang phục vụ ngừng hoạt động, "
                + "ứng dụng tự chuyển sang chi nhánh còn lại và gửi lại giao dịch dở dang với cùng mã yêu cầu, nên không mất và không trừ tiền 2 lần. "
                + (pending > 0 ? "Hiện có " + pending + " bút toán đang chờ đồng bộ sang chi nhánh kia." : "Hiện không có bút toán nào chờ đồng bộ."));
    }

    private void switchBranch(Button b) {
        b.setDisable(true);
        result.setText("Đang chuyển chi nhánh...");
        ctx.core.switchBranch().thenAccept(r -> Platform.runLater(() -> {
            result.setText(r.isSuccess() ? "✓ " + r.getMessage() : "Không chuyển được: " + r.getMessage());
            render();
        }));
    }

    @Override public String id() { return "cluster"; }
    @Override public String title() { return "Mạng lưới chi nhánh"; }
    @Override public String subtitle() { return "Hai chi nhánh Hà Nội - TP.HCM chạy song song, đồng bộ sổ cái 2 chiều"; }
    @Override public Parent view() { return root; }
    @Override public void onShow() { render(); }
}
