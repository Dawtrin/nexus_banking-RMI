package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.enums.AccountStatus;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Server-to-client push channel, exported by the customer app and registered at login. The server never lets a slow
 * or dead device block a transaction: pushes are fired from a separate executor with bounded timeouts, and a callback
 * that fails is dropped (the app keeps working through polling).
 */
public interface BankingCallback extends Remote {

    /** Money entered or left the account (local or replicated from the other branch). */
    void onBalanceChanged(BalanceNotificationDTO notification) throws RemoteException;

    /** The session was ended by a newer login of the same account on another device/branch, or by an operator. */
    void onSessionEvicted(String reason) throws RemoteException;

    /** An operator locked or unlocked the account. */
    void onAccountStatusChanged(String accountNumber, AccountStatus status, String message) throws RemoteException;

    /** Liveness probe. */
    void ping() throws RemoteException;
}
