package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.PeerSyncStatus;
import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;
import com.nexus.banking.server.database.DatabaseManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MySQL/MariaDB implementation. All writes run under one writer lock and ONE database transaction, so commit order
 * equals local_sequence order and a money movement is all-or-nothing: account rows are locked with
 * {@code SELECT ... FOR UPDATE}, the debit is a guarded {@code UPDATE ... WHERE balance >= amount}, and the ledger entry
 * and outbox row commit together with the balance change. Failures roll back and surface as {@link StorageException}.
 */
public class JdbcBankingStore implements BankingStore {
    private static final String TX_COLS = "t.local_sequence, t.transaction_id, t.account_number, t.transaction_type, "
            + "t.amount, t.balance_after, t.reference_account, t.note, t.created_at, t.origin_server_id, "
            + "t.origin_sequence, t.client_request_id";

    private final DatabaseManager db;
    private final ReentrantLock writeLock = new ReentrantLock();

    public JdbcBankingStore(DatabaseManager db) {
        this.db = db;
    }

    @FunctionalInterface
    private interface SqlBody<T> {
        T run(Connection c) throws SQLException;
    }

    @Override
    public String modeLabel() {
        return db.isConnected() ? "MYSQL_DURABLE" : "DB_UNAVAILABLE";
    }

    @Override
    public boolean isDurable() {
        return true;
    }

    private <T> T read(SqlBody<T> body) throws StorageException {
        try (Connection c = db.getConnection()) {
            return body.run(c);
        } catch (SQLException e) {
            db.reportFailure(e);
            throw new StorageException("Storage unavailable: " + e.getMessage(), e);
        }
    }

    private <T> T write(SqlBody<T> body) throws StorageException {
        writeLock.lock();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = body.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // connection unusable anyway; the original failure is reported below
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // pool discards broken connections
                }
            }
        } catch (SQLException e) {
            db.reportFailure(e);
            throw new StorageException("Write failed and was rolled back: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new StorageException("Write failed and was rolled back: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    private static LocalDateTime ts(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        LocalDateTime ldt = rs.getObject(col, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static Instant nowMillis() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static TransactionDTO mapTx(ResultSet rs, TransactionSyncState sync) throws SQLException {
        return new TransactionDTO(rs.getString("transaction_id"), rs.getString("account_number"),
                TransactionType.valueOf(rs.getString("transaction_type")), rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"), rs.getString("reference_account"), rs.getString("note"),
                instant(rs, "created_at"), rs.getString("origin_server_id"), rs.getLong("origin_sequence"),
                rs.getLong("local_sequence"), rs.getString("client_request_id"), sync);
    }

    private static AccountDTO mapAccount(ResultSet rs) throws SQLException {
        return new AccountDTO(rs.getString("account_number"), rs.getString("owner_name"), rs.getBigDecimal("balance"),
                rs.getString("branch_id"), AccountStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                rs.getBigDecimal("daily_limit"));
    }

    // -------------------------------------------------------------------------------------------- accounts

    @Override
    public List<AccountDTO> listAccounts() throws StorageException {
        return read(c -> {
            List<AccountDTO> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM bank_accounts ORDER BY account_number")) {
                while (rs.next()) out.add(mapAccount(rs));
            }
            return out;
        });
    }

    @Override
    public Optional<AccountDTO> findAccount(String accountNumber) throws StorageException {
        return read(c -> Optional.ofNullable(loadAccount(c, accountNumber, false)));
    }

    private static AccountDTO loadAccount(Connection c, String number, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bank_accounts WHERE account_number = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapAccount(rs) : null;
            }
        }
    }

    @Override
    public Optional<StoredCredential> findCredential(String accountNumber) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM bank_accounts WHERE account_number = ?")) {
                ps.setString(1, accountNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new StoredCredential(mapAccount(rs), rs.getString("pin_hash")))
                            : Optional.<StoredCredential>empty();
                }
            }
        });
    }

    @Override
    public boolean createAccount(AccountDTO a, String pinHash) throws StorageException {
        return write(c -> {
            if (loadAccount(c, a.getAccountNumber(), true) != null) return false;
            LocalDateTime now = ts(nowMillis());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_accounts (account_number, owner_name, "
                    + "balance, branch_id, status, daily_limit, pin_hash, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, a.getAccountNumber());
                ps.setString(2, a.getOwnerName());
                ps.setBigDecimal(3, a.getBalance());
                ps.setString(4, a.getBranchId());
                ps.setString(5, a.getStatus().name());
                ps.setBigDecimal(6, a.getDailyLimit());
                ps.setString(7, pinHash);
                ps.setObject(8, now);
                ps.setObject(9, now);
                ps.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public boolean setAccountStatus(String accountNumber, AccountStatus status) throws StorageException {
        return write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bank_accounts SET status = ?, updated_at = ? WHERE account_number = ?")) {
                ps.setString(1, status.name());
                ps.setObject(2, ts(nowMillis()));
                ps.setString(3, accountNumber);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean setDailyLimit(String accountNumber, BigDecimal limit) throws StorageException {
        return write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bank_accounts SET daily_limit = ?, updated_at = ? WHERE account_number = ?")) {
                ps.setBigDecimal(1, limit);
                ps.setObject(2, ts(nowMillis()));
                ps.setString(3, accountNumber);
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ---------------------------------------------------------------------------------------- money movement

    /** Idempotency: null when the request id is new, DUPLICATE when identical, REQUEST_ID_REUSED when it differs. */
    private static MoneyResult checkRequest(Connection c, String requestId, String account, TransactionType type,
                                            BigDecimal amount) throws SQLException {
        if (requestId == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT " + TX_COLS
                + " FROM bank_transactions t WHERE t.client_request_id = ?")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                TransactionDTO existing = mapTx(rs, TransactionSyncState.PENDING);
                boolean same = existing.getAccountNumber().equals(account) && existing.getType() == type
                        && existing.getAmount().compareTo(amount) == 0;
                return same ? MoneyResult.duplicate(existing)
                        : MoneyResult.rejected(MoneyResult.Outcome.REQUEST_ID_REUSED,
                        "Mã yêu cầu đã được dùng cho một giao dịch khác");
            }
        }
    }

    private static long allocateSeq(Connection c, String origin, int count) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO origin_sequences (origin_server_id, last_seq) VALUES (?, 0)")) {
            ps.setString(1, origin);
            ps.executeUpdate();
        }
        long last;
        try (PreparedStatement ps = c.prepareStatement("SELECT last_seq FROM origin_sequences WHERE origin_server_id = ? FOR UPDATE")) {
            ps.setString(1, origin);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("origin sequence row missing for " + origin);
                last = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE origin_sequences SET last_seq = ? WHERE origin_server_id = ?")) {
            ps.setLong(1, last + count);
            ps.setString(2, origin);
            ps.executeUpdate();
        }
        return last + 1;
    }

    private static BigDecimal spentToday(Connection c, String account, Instant dayStart) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM bank_transactions "
                + "WHERE account_number = ? AND transaction_type IN ('WITHDRAW','TRANSFER') AND created_at >= ?")) {
            ps.setString(1, account);
            ps.setObject(2, ts(dayStart));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Inserts one ledger entry (+ outbox row) and returns it with its generated local sequence. */
    private static TransactionDTO insertEntry(Connection c, String origin, String peerId, String txId, String account,
                                              TransactionType type, BigDecimal amount, BigDecimal balanceAfter, String ref,
                                              String note, Instant at, long originSeq, String requestId,
                                              TransactionSyncState sync, boolean outbox) throws SQLException {
        long local;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_transactions (transaction_id, account_number, "
                + "transaction_type, amount, balance_after, reference_account, note, created_at, origin_server_id, "
                + "origin_sequence, client_request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, txId);
            ps.setString(2, account);
            ps.setString(3, type.name());
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, balanceAfter);
            ps.setString(6, ref == null || ref.isEmpty() ? null : ref);
            ps.setString(7, note == null ? "" : note);
            ps.setObject(8, ts(at));
            ps.setString(9, origin);
            ps.setLong(10, originSeq);
            ps.setString(11, requestId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No local_sequence generated");
                local = keys.getLong(1);
            }
        }
        if (outbox && peerId != null) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_outbox (transaction_id, target_peer_id, "
                    + "status, attempt_count, next_attempt_at, created_at, updated_at) VALUES (?, ?, 'PENDING', 0, ?, ?, ?)")) {
                ps.setString(1, txId);
                ps.setString(2, peerId);
                ps.setObject(3, ts(at));
                ps.setObject(4, ts(at));
                ps.setObject(5, ts(at));
                ps.executeUpdate();
            }
        }
        return new TransactionDTO(txId, account, type, amount, balanceAfter, ref, note, at, origin, originSeq, local,
                requestId, sync);
    }

    private static void addToBalance(Connection c, String account, BigDecimal delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE bank_accounts SET balance = balance + ?, updated_at = ? WHERE account_number = ?")) {
            ps.setBigDecimal(1, delta);
            ps.setObject(2, ts(nowMillis()));
            ps.setString(3, account);
            ps.executeUpdate();
        }
    }

    /** Guarded debit: applies only while the account is ACTIVE and balance >= amount (the atomic balance check). */
    private static boolean guardedDebit(Connection c, String account, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE bank_accounts SET balance = balance - ?, updated_at = ? "
                + "WHERE account_number = ? AND balance >= ? AND status = 'ACTIVE'")) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, ts(nowMillis()));
            ps.setString(3, account);
            ps.setBigDecimal(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public MoneyResult deposit(String origin, String peerId, String account, String requestId, BigDecimal amount,
                               String note) throws StorageException {
        return write(c -> {
            MoneyResult dup = checkRequest(c, requestId, account, TransactionType.DEPOSIT, amount);
            if (dup != null) return dup;
            AccountDTO a = loadAccount(c, account, true);
            if (a == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, account);
            if (a.getStatus() == AccountStatus.LOCKED) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_LOCKED, account);
            if (a.getBalance().add(amount).compareTo(Money.MAX_BALANCE) > 0) {
                return MoneyResult.rejected(MoneyResult.Outcome.BALANCE_OVERFLOW, "");
            }
            addToBalance(c, account, amount);
            BigDecimal after = a.getBalance().add(amount);
            long seq = allocateSeq(c, origin, 1);
            TransactionDTO tx = insertEntry(c, origin, peerId, UUID.randomUUID().toString(), account,
                    TransactionType.DEPOSIT, amount, after, "", note, nowMillis(), seq, requestId,
                    TransactionSyncState.PENDING, true);
            return MoneyResult.ok(tx, null, after);
        });
    }

    @Override
    public MoneyResult withdraw(String origin, String peerId, String account, String requestId, BigDecimal amount,
                                String note, Instant dayStart) throws StorageException {
        return write(c -> {
            MoneyResult dup = checkRequest(c, requestId, account, TransactionType.WITHDRAW, amount);
            if (dup != null) return dup;
            AccountDTO a = loadAccount(c, account, true);
            if (a == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, account);
            BigDecimal spent = spentToday(c, account, dayStart);
            MoneyResult.Outcome problem = LedgerMath.debitProblem(a, amount, spent);
            if (problem != null) {
                return MoneyResult.rejected(problem, problem == MoneyResult.Outcome.LIMIT_EXCEEDED
                        ? LedgerMath.limitDetail(a, spent) : "");
            }
            if (!guardedDebit(c, account, amount)) {
                return MoneyResult.rejected(MoneyResult.Outcome.INSUFFICIENT_FUNDS, "");
            }
            BigDecimal after = a.getBalance().subtract(amount);
            long seq = allocateSeq(c, origin, 1);
            TransactionDTO tx = insertEntry(c, origin, peerId, UUID.randomUUID().toString(), account,
                    TransactionType.WITHDRAW, amount, after, "", note, nowMillis(), seq, requestId,
                    TransactionSyncState.PENDING, true);
            return MoneyResult.ok(tx, null, after);
        });
    }

    @Override
    public MoneyResult transfer(String origin, String peerId, String from, String to, String requestId,
                                BigDecimal amount, String note, Instant dayStart) throws StorageException {
        return write(c -> {
            MoneyResult dup = checkRequest(c, requestId, from, TransactionType.TRANSFER, amount);
            if (dup != null) return dup;
            // lock both rows in a fixed order so two transfers can never deadlock each other
            boolean fromFirst = from.compareTo(to) < 0;
            AccountDTO first = loadAccount(c, fromFirst ? from : to, true);
            AccountDTO second = loadAccount(c, fromFirst ? to : from, true);
            AccountDTO src = fromFirst ? first : second;
            AccountDTO dst = fromFirst ? second : first;
            if (src == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, from);
            if (dst == null) return MoneyResult.rejected(MoneyResult.Outcome.COUNTERPARTY_NOT_FOUND, to);
            if (dst.getStatus() == AccountStatus.LOCKED) return MoneyResult.rejected(MoneyResult.Outcome.COUNTERPARTY_LOCKED, to);
            BigDecimal spent = spentToday(c, from, dayStart);
            MoneyResult.Outcome problem = LedgerMath.debitProblem(src, amount, spent);
            if (problem != null) {
                return MoneyResult.rejected(problem, problem == MoneyResult.Outcome.LIMIT_EXCEEDED
                        ? LedgerMath.limitDetail(src, spent) : "");
            }
            if (dst.getBalance().add(amount).compareTo(Money.MAX_BALANCE) > 0) {
                return MoneyResult.rejected(MoneyResult.Outcome.BALANCE_OVERFLOW, "");
            }
            if (!guardedDebit(c, from, amount)) return MoneyResult.rejected(MoneyResult.Outcome.INSUFFICIENT_FUNDS, "");
            addToBalance(c, to, amount);
            BigDecimal srcAfter = src.getBalance().subtract(amount);
            BigDecimal dstAfter = dst.getBalance().add(amount);
            Instant at = nowMillis();
            long seq = allocateSeq(c, origin, 2);
            String debitId = UUID.randomUUID().toString();
            TransactionDTO debit = insertEntry(c, origin, peerId, debitId, from, TransactionType.TRANSFER, amount,
                    srcAfter, to, note, at, seq, requestId, TransactionSyncState.PENDING, true);
            TransactionDTO credit = insertEntry(c, origin, peerId, debitId + "-C", to, TransactionType.TRANSFER_IN,
                    amount, dstAfter, from, note, at, seq + 1, null, TransactionSyncState.PENDING, true);
            return MoneyResult.ok(debit, credit, srcAfter);
        });
    }

    // ------------------------------------------------------------------------------------------- replication

    @Override
    public IngestResult ingestReplicated(String sourcePeerId, List<TransactionDTO> entries) throws StorageException {
        return write(c -> {
            List<String> accepted = new ArrayList<>();
            Map<String, String> rejected = new LinkedHashMap<>();
            List<TransactionDTO> applied = new ArrayList<>();
            List<LedgerEvent> events = new ArrayList<>();
            long maxFromPeer = 0;
            for (TransactionDTO e : entries) {
                String existingId = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT transaction_id FROM bank_transactions WHERE origin_server_id = ? AND origin_sequence = ?")) {
                    ps.setString(1, e.getOriginServerId());
                    ps.setLong(2, e.getOriginSequence());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) existingId = rs.getString(1);
                    }
                }
                if (existingId != null) {
                    if (existingId.equals(e.getTransactionId())) {
                        accepted.add(e.getTransactionId());
                        if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
                    } else {
                        rejected.put(e.getTransactionId(), "origin sequence " + e.getOriginServerId() + "#"
                                + e.getOriginSequence() + " already used by a different entry");
                    }
                    continue;
                }
                if (exists(c, "SELECT 1 FROM bank_transactions WHERE transaction_id = ?", e.getTransactionId())) {
                    rejected.put(e.getTransactionId(), "transaction id already exists with a different origin sequence");
                    continue;
                }
                if (e.getClientRequestId() != null) {
                    String local = scalar(c, "SELECT transaction_id FROM bank_transactions WHERE client_request_id = ?",
                            e.getClientRequestId());
                    if (local != null) {
                        events.add(new LedgerEvent(LedgerEvent.Kind.DUPLICATE_SUPPRESSED, e.getAccountNumber(),
                                "request " + e.getClientRequestId() + " already executed locally as " + local
                                        + "; peer entry " + e.getTransactionId() + " not applied"));
                        accepted.add(e.getTransactionId());
                        if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
                        continue;
                    }
                }
                AccountDTO a = loadAccount(c, e.getAccountNumber(), true);
                if (a == null) {
                    rejected.put(e.getTransactionId(), "unknown account " + e.getAccountNumber() + " on this node");
                    continue;
                }
                BigDecimal delta = e.getType().isCredit() ? e.getAmount() : e.getAmount().negate();
                addToBalance(c, a.getAccountNumber(), delta);
                BigDecimal after = a.getBalance().add(delta);
                TransactionDTO stored = insertEntry(c, e.getOriginServerId(), null, e.getTransactionId(),
                        e.getAccountNumber(), e.getType(), e.getAmount(), after, e.getReferenceAccount(), e.getNote(),
                        e.getCreatedAt().truncatedTo(ChronoUnit.MILLIS), e.getOriginSequence(), e.getClientRequestId(),
                        TransactionSyncState.FROM_PEER, false);
                applied.add(stored);
                accepted.add(e.getTransactionId());
                if (after.signum() < 0 && !e.getType().isCredit()) {
                    events.add(new LedgerEvent(LedgerEvent.Kind.OVERDRAFT_DETECTED, e.getAccountNumber(),
                            "balance " + after.toPlainString() + " after applying " + e.getTransactionId() + " from "
                                    + e.getOriginServerId()));
                }
                if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
            }
            LocalDateTime now = ts(nowMillis());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_state (peer_server_id, "
                    + "last_replicated_origin_seq, last_success_at, last_sync_status, updated_at) VALUES (?, ?, ?, 'SYNCED', ?) "
                    + "ON DUPLICATE KEY UPDATE last_replicated_origin_seq = GREATEST(last_replicated_origin_seq, "
                    + "VALUES(last_replicated_origin_seq)), last_success_at = VALUES(last_success_at), updated_at = VALUES(updated_at)")) {
                ps.setString(1, sourcePeerId);
                ps.setLong(2, maxFromPeer);
                ps.setObject(3, now);
                ps.setObject(4, now);
                ps.executeUpdate();
            }
            long checkpoint = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_replicated_origin_seq FROM replication_state WHERE peer_server_id = ?")) {
                ps.setString(1, sourcePeerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) checkpoint = rs.getLong(1);
                }
            }
            return new IngestResult(accepted, rejected, checkpoint, applied, events);
        });
    }

    private static boolean exists(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String scalar(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- queries

    private List<TransactionDTO> recent(String account, int limit, String localServerId, String peerId) throws StorageException {
        return read(c -> {
            String sql = "SELECT " + TX_COLS + ", o.status AS outbox_status FROM bank_transactions t "
                    + "LEFT JOIN replication_outbox o ON o.transaction_id = t.transaction_id AND o.target_peer_id = ? "
                    + (account == null ? "" : "WHERE t.account_number = ? ")
                    + "ORDER BY t.created_at DESC, t.local_sequence DESC LIMIT ?";
            List<TransactionDTO> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, peerId == null ? "" : peerId);
                if (account != null) ps.setString(i++, account);
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean local = rs.getString("origin_server_id").equals(localServerId);
                        out.add(mapTx(rs, LedgerMath.syncStateFor(local, rs.getString("outbox_status"), peerId != null)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<TransactionDTO> recentTransactions(String account, int limit, String localServerId, String peerId)
            throws StorageException {
        return recent(account, limit, localServerId, peerId);
    }

    @Override
    public List<TransactionDTO> recentTransactionsAll(int limit, String localServerId, String peerId) throws StorageException {
        return recent(null, limit, localServerId, peerId);
    }

    @Override
    public List<TransactionDTO> findByOriginAfter(String origin, long after, int limit) throws StorageException {
        return read(c -> {
            List<TransactionDTO> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + TX_COLS + " FROM bank_transactions t "
                    + "WHERE t.origin_server_id = ? AND t.origin_sequence > ? ORDER BY t.origin_sequence ASC LIMIT ?")) {
                ps.setString(1, origin);
                ps.setLong(2, after);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapTx(rs, TransactionSyncState.PENDING));
                }
            }
            return out;
        });
    }

    @Override
    public LedgerSummaryDTO ledgerSummary(String serverId) throws StorageException {
        return read(c -> {
            List<AccountDTO> all = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM bank_accounts ORDER BY account_number")) {
                while (rs.next()) all.add(mapAccount(rs));
            }
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            int locked = 0;
            List<String> overdrawn = new ArrayList<>();
            for (AccountDTO a : all) {
                total = total.add(a.getBalance());
                if (a.getStatus() == AccountStatus.LOCKED) locked++;
                if (a.getBalance().signum() < 0) overdrawn.add(a.getAccountNumber());
            }
            Map<String, Long> last = new TreeMap<>();
            long count = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT origin_server_id, MAX(origin_sequence), COUNT(*) FROM bank_transactions GROUP BY origin_server_id")) {
                while (rs.next()) {
                    last.put(rs.getString(1), rs.getLong(2));
                    count += rs.getLong(3);
                }
            }
            return new LedgerSummaryDTO(serverId, all.size(), locked, total, count, last, LedgerMath.balanceDigest(all),
                    overdrawn, Instant.now());
        });
    }

    // ------------------------------------------------------------------------------------------ checkpoints

    @Override
    public long getInboundCheckpoint(String peerId) throws StorageException {
        return read(c -> {
            long fromState = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_replicated_origin_seq FROM replication_state WHERE peer_server_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fromState = rs.getLong(1);
                }
            }
            long fromEntries = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(origin_sequence), 0) FROM bank_transactions WHERE origin_server_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fromEntries = rs.getLong(1);
                }
            }
            return Math.max(fromState, fromEntries);
        });
    }

    @Override
    public void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) throws StorageException {
        read(c -> {
            Instant now = nowMillis();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_state (peer_server_id, "
                    + "last_replicated_origin_seq, last_success_at, last_sync_status, last_error, updated_at) "
                    + "VALUES (?, 0, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                    + "last_success_at = COALESCE(VALUES(last_success_at), last_success_at), "
                    + "last_sync_status = VALUES(last_sync_status), last_error = VALUES(last_error), "
                    + "updated_at = VALUES(updated_at)")) {
                ps.setString(1, peerId);
                ps.setObject(2, success ? ts(now) : null);
                ps.setString(3, status.name());
                ps.setString(4, error == null ? null : error.substring(0, Math.min(500, error.length())));
                ps.setObject(5, ts(now));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) throws StorageException {
        return read(c -> {
            List<OutboxEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT o.outbox_id, o.attempt_count, " + TX_COLS
                    + " FROM replication_outbox o JOIN bank_transactions t ON t.transaction_id = o.transaction_id "
                    + "WHERE o.target_peer_id = ? AND o.status = 'PENDING' AND o.next_attempt_at <= ? "
                    + "ORDER BY o.outbox_id ASC LIMIT ?")) {
                ps.setString(1, peerId);
                ps.setObject(2, ts(now));
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new OutboxEntry(rs.getLong("outbox_id"), rs.getInt("attempt_count"),
                                mapTx(rs, TransactionSyncState.PENDING)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void markDelivered(Collection<Long> outboxIds) throws StorageException {
        if (outboxIds.isEmpty()) return;
        read(c -> {
            Instant now = nowMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE replication_outbox SET status = 'DELIVERED', updated_at = ? WHERE outbox_id = ? AND status = 'PENDING'")) {
                for (Long id : outboxIds) {
                    ps.setObject(1, ts(now));
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public boolean rejectOutbox(long outboxId, String reason, Instant now, long baseMillis, long maxMillis, int maxAttempts)
            throws StorageException {
        return read(c -> {
            int attempts;
            try (PreparedStatement ps = c.prepareStatement("SELECT attempt_count FROM replication_outbox WHERE outbox_id = ?")) {
                ps.setLong(1, outboxId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    attempts = rs.getInt(1) + 1;
                }
            }
            boolean dead = attempts >= maxAttempts;
            long delay = Math.min(maxMillis, baseMillis * (1L << Math.min(attempts - 1, 20)));
            try (PreparedStatement ps = c.prepareStatement("UPDATE replication_outbox SET status = ?, attempt_count = ?, "
                    + "next_attempt_at = ?, last_error = ?, updated_at = ? WHERE outbox_id = ?")) {
                ps.setString(1, dead ? "DEAD" : "PENDING");
                ps.setInt(2, attempts);
                ps.setObject(3, ts(now.plusMillis(delay).truncatedTo(ChronoUnit.MILLIS)));
                ps.setString(4, reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
                ps.setObject(5, ts(now.truncatedTo(ChronoUnit.MILLIS)));
                ps.setLong(6, outboxId);
                ps.executeUpdate();
            }
            return dead;
        });
    }

    @Override
    public OutboxStats outboxStats(String peerId) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT "
                    + "COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END), 0), "
                    + "MIN(CASE WHEN status = 'PENDING' THEN created_at END) AS oldest "
                    + "FROM replication_outbox WHERE target_peer_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new OutboxStats(rs.getLong(1), rs.getLong(2), instant(rs, "oldest"));
                }
            }
        });
    }

    @Override
    public int requeueDead(String peerId, Instant now) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE replication_outbox SET status = 'PENDING', attempt_count = 0, "
                    + "next_attempt_at = ?, last_error = NULL, updated_at = ? WHERE status = 'DEAD' AND target_peer_id = ?")) {
                LocalDateTime t = ts(now.truncatedTo(ChronoUnit.MILLIS));
                ps.setObject(1, t);
                ps.setObject(2, t);
                ps.setString(3, peerId);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- audit

    @Override
    public void appendAudit(Instant at, String serverId, String actor, String action, String target, String result,
                            String details) throws StorageException {
        write(c -> {
            Instant t = at.truncatedTo(ChronoUnit.MILLIS);
            String det = details == null ? "" : details.substring(0, Math.min(1000, details.length()));
            String prev = AuditChain.GENESIS;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT entry_hash FROM audit_log ORDER BY audit_id DESC LIMIT 1 FOR UPDATE")) {
                if (rs.next()) prev = rs.getString(1);
            }
            String hash = AuditChain.hash(prev, t, serverId, actor, action, target, result, det);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log (occurred_at, server_id, actor, action, "
                    + "target, result, details, prev_hash, entry_hash) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, ts(t));
                ps.setString(2, serverId);
                ps.setString(3, actor);
                ps.setString(4, action);
                ps.setString(5, target);
                ps.setString(6, result);
                ps.setString(7, det);
                ps.setString(8, prev);
                ps.setString(9, hash);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static AuditEntryDTO mapAudit(ResultSet rs) throws SQLException {
        return new AuditEntryDTO(rs.getLong("audit_id"), instant(rs, "occurred_at"), rs.getString("server_id"),
                rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("result"),
                rs.getString("details"), rs.getString("prev_hash"), rs.getString("entry_hash"));
    }

    @Override
    public List<AuditEntryDTO> recentAudit(int limit) throws StorageException {
        return read(c -> {
            List<AuditEntryDTO> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapAudit(rs));
                }
            }
            return out;
        });
    }

    @Override
    public AuditVerificationDTO verifyAuditChain() throws StorageException {
        return read(c -> {
            List<AuditEntryDTO> all = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM audit_log ORDER BY audit_id ASC")) {
                while (rs.next()) all.add(mapAudit(rs));
            }
            return AuditChain.verify(all);
        });
    }
}
