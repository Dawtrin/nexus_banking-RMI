package com.nexus.banking.client.ui;

import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionSyncState;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** The full ledger of the account with replication state per entry. */
final class HistoryPage implements Page {
    private final VBox root = new VBox(14);
    private final FilteredList<TransactionDTO> filtered;
    private final Label count = new Label();
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();

    HistoryPage(UiContext ctx) {
        filtered = new FilteredList<>(ctx.model.transactions, t -> true);
        TableView<TransactionDTO> table = new TableView<>(filtered);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Chưa có giao dịch nào"));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().add(col("Thời gian", 140, t -> Fmt.dateTime(t.getCreatedAt())));
        TableColumn<TransactionDTO, TransactionDTO> type = new TableColumn<>("Giao dịch");
        type.setPrefWidth(230);
        type.setMinWidth(200);
        type.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        type.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(TransactionDTO t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label ic = new Label(Fmt.typeIcon(t.getType()));
                ic.setStyle("-fx-text-fill: " + Fmt.typeColor(t.getType()) + "; -fx-font-weight: 900; -fx-font-size: 16px;");
                Label tx = new Label(Fmt.title(t));
                HBox h = new HBox(10, ic, tx);
                h.setAlignment(Pos.CENTER_LEFT);
                setGraphic(h);
                setText(null);
            }
        });
        table.getColumns().add(type);
        TableColumn<TransactionDTO, TransactionDTO> amount = new TableColumn<>("Số tiền");
        amount.setPrefWidth(130);
        amount.setMinWidth(120);
        amount.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        amount.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(TransactionDTO t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setText(null);
                    getStyleClass().removeAll("amount-in", "amount-out");
                    return;
                }
                setText(Money.formatSigned(t.getSignedAmount()));
                getStyleClass().removeAll("amount-in", "amount-out");
                getStyleClass().add(t.getType().isCredit() ? "amount-in" : "amount-out");
            }
        });
        table.getColumns().add(amount);
        table.getColumns().add(col("Số dư sau", 130, t -> Money.format(t.getBalanceAfter())));
        table.getColumns().add(col("Ghi chú", 150, TransactionDTO::getNote));
        TableColumn<TransactionDTO, TransactionDTO> sync = new TableColumn<>("Trạng thái");
        sync.setPrefWidth(175);
        sync.setMinWidth(165);
        sync.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        sync.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(TransactionDTO t, boolean empty) {
                super.updateItem(t, empty);
                setGraphic(empty || t == null ? null : Fmt.syncChip(t.getSyncState()));
                setText(null);
            }
        });
        table.getColumns().add(sync);
        table.getColumns().add(col("Mã giao dịch", 120, t -> t.getTransactionId().length() > 13 ? t.getTransactionId().substring(0, 13) + "…" : t.getTransactionId()));

        search.setPromptText("Tìm theo nội dung, tài khoản đối ứng, mã giao dịch");
        HBox.setHgrow(search, Priority.ALWAYS);
        filter.getItems().addAll("Tất cả", "Tiền vào", "Tiền ra", "Chờ đồng bộ");
        filter.getSelectionModel().select(0);
        search.textProperty().addListener((o, a, b) -> apply());
        filter.valueProperty().addListener((o, a, b) -> apply());
        count.getStyleClass().add("text-muted");
        Region g = new Region();
        HBox.setHgrow(g, Priority.SOMETIMES);
        HBox bar = new HBox(10, search, filter, count);
        bar.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(26, 32, 26, 32));
        root.getChildren().addAll(bar, table);
        ctx.model.transactions.addListener((javafx.collections.ListChangeListener<TransactionDTO>) c -> updateCount());
        updateCount();
    }

    private static TableColumn<TransactionDTO, String> col(String title, double w, java.util.function.Function<TransactionDTO, String> f) {
        TableColumn<TransactionDTO, String> c = new TableColumn<>(title);
        c.setPrefWidth(w);
        c.setCellValueFactory(x -> new javafx.beans.property.ReadOnlyStringWrapper(f.apply(x.getValue())));
        return c;
    }

    private void apply() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase();
        String f = filter.getValue() == null ? "Tất cả" : filter.getValue();
        filtered.setPredicate(t -> (q.isBlank() || t.getNote().toLowerCase().contains(q) || t.getReferenceAccount().contains(q)
                || t.getTransactionId().toLowerCase().contains(q) || Fmt.title(t).toLowerCase().contains(q))
                && (f.equals("Tất cả") || (f.equals("Tiền vào") && t.getType().isCredit()) || (f.equals("Tiền ra") && !t.getType().isCredit())
                || (f.equals("Chờ đồng bộ") && t.getSyncState() == TransactionSyncState.PENDING)));
        updateCount();
    }

    private void updateCount() {
        count.setText(filtered.size() + " giao dịch");
    }

    @Override public String id() { return "history"; }
    @Override public String title() { return "Lịch sử giao dịch"; }
    @Override public String subtitle() { return "Sổ cái của tài khoản, kèm trạng thái đồng bộ giữa hai chi nhánh"; }
    @Override public Parent view() { return root; }
}
