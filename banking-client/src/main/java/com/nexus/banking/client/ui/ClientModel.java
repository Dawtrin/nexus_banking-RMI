package com.nexus.banking.client.ui;

import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Observable state of the logged-in customer. Only ever touched on the JavaFX thread. */
final class ClientModel {
    final ObjectProperty<AccountDTO> account = new SimpleObjectProperty<>();
    final ObservableList<TransactionDTO> transactions = FXCollections.observableArrayList();
    final ObjectProperty<ConnectionState> state = new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);
    final StringProperty statusText = new SimpleStringProperty("");
    final StringProperty branchName = new SimpleStringProperty("");
    final StringProperty serverId = new SimpleStringProperty("");
    final BooleanProperty pushActive = new SimpleBooleanProperty(false);

    void reset() {
        account.set(null);
        transactions.clear();
        branchName.set("");
        serverId.set("");
        pushActive.set(false);
    }
}
