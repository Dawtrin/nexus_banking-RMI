package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.PeerSyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Single persistence boundary for accounts, the ledger, the replication outbox and the audit chain.
 * Every method either completes durably (for the durable implementation) or throws {@link StorageException};
 * nothing is ever silently dropped or reported as stored when it was not.
 *
 * <p>Each money movement is ONE atomic unit: idempotency check, account checks (status, balance >= amount, daily
 * limit), balance update, origin-sequence allocation, ledger entry and (when {@code peerId != null}) outbox row either
 * all commit or none do.</p>
 */
public interface BankingStore {

    /** Honest label: {@code MYSQL_DURABLE} or {@code RAM_ONLY_DEMO}. */
    String modeLabel();

    boolean isDurable();

    // ----------------------------------------------------------------------------------------------- accounts

    List<AccountDTO> listAccounts() throws StorageException;

    Optional<AccountDTO> findAccount(String accountNumber) throws StorageException;

    Optional<StoredCredential> findCredential(String accountNumber) throws StorageException;

    /** False when the account number already exists. */
    boolean createAccount(AccountDTO account, String pinHash) throws StorageException;

    /** False when the account does not exist. */
    boolean setAccountStatus(String accountNumber, AccountStatus status) throws StorageException;

    /** False when the account does not exist. */
    boolean setDailyLimit(String accountNumber, BigDecimal limit) throws StorageException;

    // --------------------------------------------------------------------------------------- money movement

    MoneyResult deposit(String originServerId, String peerId, String account, String requestId, BigDecimal amount,
                        String note) throws StorageException;

    /** {@code dayStart} is the beginning of the current calendar day (daily limit window). */
    MoneyResult withdraw(String originServerId, String peerId, String account, String requestId, BigDecimal amount,
                         String note, Instant dayStart) throws StorageException;

    MoneyResult transfer(String originServerId, String peerId, String from, String to, String requestId,
                         BigDecimal amount, String note, Instant dayStart) throws StorageException;

    /**
     * Atomically applies a replicated batch (balances + ledger entries) and advances the inbound checkpoint for
     * {@code sourcePeerId}. Applying the same entry twice is a no-op.
     */
    IngestResult ingestReplicated(String sourcePeerId, List<TransactionDTO> entries) throws StorageException;

    // ------------------------------------------------------------------------------------------------ queries

    /** Newest first, tagged with their replication state relative to {@code peerId}. */
    List<TransactionDTO> recentTransactions(String accountNumber, int limit, String localServerId, String peerId)
            throws StorageException;

    /** Newest first across all accounts (administrators). */
    List<TransactionDTO> recentTransactionsAll(int limit, String localServerId, String peerId) throws StorageException;

    List<TransactionDTO> findByOriginAfter(String originServerId, long afterOriginSequence, int limit)
            throws StorageException;

    LedgerSummaryDTO ledgerSummary(String serverId) throws StorageException;

    // -------------------------------------------------------------------------------------- replication state

    long getInboundCheckpoint(String peerId) throws StorageException;

    void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) throws StorageException;

    List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) throws StorageException;

    void markDelivered(Collection<Long> outboxIds) throws StorageException;

    /** Counts a peer rejection: exponential backoff, and DEAD after {@code maxAttempts}. Returns true when dead-lettered. */
    boolean rejectOutbox(long outboxId, String reason, Instant now, long baseMillis, long maxMillis, int maxAttempts)
            throws StorageException;

    OutboxStats outboxStats(String peerId) throws StorageException;

    int requeueDead(String peerId, Instant now) throws StorageException;

    // ------------------------------------------------------------------------------------------------- audit

    /** Appends to the hash-chained, append-only audit ledger. */
    void appendAudit(Instant at, String serverId, String actor, String action, String target, String result,
                     String details) throws StorageException;

    /** Newest first. */
    List<AuditEntryDTO> recentAudit(int limit) throws StorageException;

    AuditVerificationDTO verifyAuditChain() throws StorageException;
}
