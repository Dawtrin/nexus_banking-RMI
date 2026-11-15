package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import java.math.BigDecimal;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

/**
 * Risk-control and operations API of ONE node. Every call needs the admin token; {@code actor} is the operator name
 * typed in NEXUS CONTROL and is recorded in the audit ledger (a label, NOT an authenticated identity: the token is
 * what authenticates). Account changes apply to this node only; NEXUS CONTROL applies them to both nodes.
 */
public interface BankingAdminService extends Remote {

    OperationResult<List<AccountDTO>> listAccounts(String adminToken) throws RemoteException;

    /** Opens an account with an opening balance (not a ledger entry); the PIN is stored salted + hashed. */
    OperationResult<Void> createAccount(String adminToken, String actor, String accountNumber, String ownerName,
                                        String branchId, BigDecimal openingBalance, BigDecimal dailyLimit, String pin)
            throws RemoteException;

    /** Locks (rejects all operations, ends live sessions) or unlocks an account. */
    OperationResult<Void> setAccountStatus(String adminToken, String actor, String accountNumber, AccountStatus status,
                                           String reason) throws RemoteException;

    OperationResult<Void> setDailyLimit(String adminToken, String actor, String accountNumber, BigDecimal newLimit)
            throws RemoteException;

    /** Newest first; {@code accountNumber} may be null for all accounts. */
    OperationResult<List<TransactionDTO>> listTransactions(String adminToken, String accountNumber, int limit)
            throws RemoteException;

    /** Ends one live session (the customer may log in again). */
    OperationResult<Void> disconnectSession(String adminToken, String actor, String sessionId) throws RemoteException;

    /** Emergency: ends every session of the account on this node. Returns how many were ended. */
    OperationResult<Integer> disconnectAccount(String adminToken, String actor, String accountNumber, String reason)
            throws RemoteException;

    OperationResult<List<AuditEntryDTO>> getAuditLog(String adminToken, int limit) throws RemoteException;

    /** Re-computes the audit hash chain from the first entry and reports the first broken link, if any. */
    OperationResult<AuditVerificationDTO> verifyAuditChain(String adminToken) throws RemoteException;

    OperationResult<LedgerSummaryDTO> getLedgerSummary(String adminToken) throws RemoteException;

    /** Moves dead-lettered replication items back to PENDING; returns how many were requeued. */
    OperationResult<Integer> requeueDeadLetters(String adminToken, String actor) throws RemoteException;

    /** Runs a replication cycle now instead of waiting for the next poll. */
    OperationResult<Void> syncNow(String adminToken, String actor) throws RemoteException;

    /** Effective, non-secret configuration of the node (secrets are never included). */
    OperationResult<Map<String, String>> getServerConfig(String adminToken) throws RemoteException;
}
