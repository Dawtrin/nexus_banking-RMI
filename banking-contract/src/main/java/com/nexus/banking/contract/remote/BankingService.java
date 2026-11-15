package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import java.math.BigDecimal;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Customer-facing core-banking API of ONE branch node (both nodes expose the same API and accept money movements
 * independently - active/active). Every method except {@link #login} and {@link #getServerId} requires a valid
 * session, and a session may only operate on its own account.
 *
 * <p><b>Idempotency:</b> deposit, withdraw and transfer take a client generated {@code requestId} (1-64 chars,
 * e.g. a UUID). Repeating a request with the same id returns the ORIGINAL transaction with the message
 * "idempotent retry" and never moves money twice, so a client may safely retry after a network failure.
 * Reusing an id for a different operation is rejected with REQUEST_ID_REUSED.</p>
 *
 * <p>All failures are reported through {@link OperationResult#getErrorCode()} (see ErrorCodes).</p>
 */
public interface BankingService extends Remote {

    /**
     * Authenticates with account number + PIN. A newer login of the same account ends every older session
     * cluster-wide (single session). {@code callback} is optional: when given, the server pushes balance changes.
     */
    OperationResult<LoginResultDTO> login(String accountNumber, String pin, String clientInstanceId,
                                          BankingCallback callback) throws RemoteException;

    OperationResult<AccountDTO> getAccount(String sessionId, String accountNumber) throws RemoteException;

    /** Masked holder name of any active account, so the customer can confirm the beneficiary before a transfer. */
    OperationResult<String> lookupAccountHolder(String sessionId, String accountNumber) throws RemoteException;

    OperationResult<TransactionDTO> deposit(String sessionId, String accountNumber, String requestId,
                                            BigDecimal amount, String note) throws RemoteException;

    /** Atomic: succeeds only when balance >= amount and the daily limit is not exceeded. */
    OperationResult<TransactionDTO> withdraw(String sessionId, String accountNumber, String requestId,
                                             BigDecimal amount, String note) throws RemoteException;

    /** Atomic double entry (debit {@code fromAccount}, credit {@code toAccount}); returns the debit leg. */
    OperationResult<TransactionDTO> transfer(String sessionId, String fromAccount, String toAccount, String requestId,
                                             BigDecimal amount, String note) throws RemoteException;

    /** Newest first, at most {@code limit} (1-200), each entry tagged with its replication state. */
    OperationResult<List<TransactionDTO>> getRecentTransactions(String sessionId, String accountNumber, int limit)
            throws RemoteException;

    OperationResult<Void> heartbeat(String sessionId) throws RemoteException;

    OperationResult<Void> logout(String sessionId) throws RemoteException;

    String getServerId() throws RemoteException;
}
