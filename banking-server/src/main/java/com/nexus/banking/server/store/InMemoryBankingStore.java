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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * RAM-only store for explicit demo/test mode ({@code database.enabled=false}). Same business rules as the JDBC store
 * (shared through {@link LedgerMath}) but NOT durable: all data is lost on restart.
 */
public class InMemoryBankingStore implements BankingStore {

    private static final class Acc {
        final String number;
        String owner;
        BigDecimal balance;
        String branch;
        AccountStatus status;
        BigDecimal limit;
        String pinHash;
        final Instant createdAt;

        Acc(String number, String owner, BigDecimal balance, String branch, AccountStatus status, BigDecimal limit,
            String pinHash, Instant createdAt) {
            this.number = number;
            this.owner = owner;
            this.balance = balance;
            this.branch = branch;
            this.status = status;
            this.limit = limit;
            this.pinHash = pinHash;
            this.createdAt = createdAt;
        }

        AccountDTO dto() {
            return new AccountDTO(number, owner, balance, branch, status, createdAt, limit);
        }
    }

    private static final class OutboxRow {
        final long id;
        final String txId;
        final String peerId;
        String status = "PENDING";
        int attempts;
        Instant nextAttempt;
        final Instant createdAt;

        OutboxRow(long id, String txId, String peerId, Instant now) {
            this.id = id;
            this.txId = txId;
            this.peerId = peerId;
            this.nextAttempt = now;
            this.createdAt = now;
        }
    }

    private final Map<String, Acc> accounts = new LinkedHashMap<>();
    private final List<TransactionDTO> ledger = new ArrayList<>();
    private final Map<String, TransactionDTO> byId = new HashMap<>();
    private final Map<String, TransactionDTO> byOrigin = new HashMap<>();
    private final Map<String, TransactionDTO> byRequest = new HashMap<>();
    private final Map<String, Long> originSeq = new HashMap<>();
    private final List<OutboxRow> outbox = new ArrayList<>();
    private final Map<String, Long> checkpoints = new HashMap<>();
    private final List<AuditEntryDTO> audit = new ArrayList<>();
    private long localSeq = 0;
    private long outboxSeq = 0;

    public InMemoryBankingStore() {
        this(true);
    }

    public InMemoryBankingStore(boolean seedDemoAccounts) {
        if (seedDemoAccounts) {
            String hash = PinHasher.hash(DefaultAccounts.DEMO_PIN);
            for (DefaultAccounts.Seed s : DefaultAccounts.list()) {
                accounts.put(s.accountNumber(), new Acc(s.accountNumber(), s.ownerName(), s.balanceValue(), s.branchId(),
                        s.status(), s.limitValue(), hash, Instant.now()));
            }
        }
    }

    @Override
    public String modeLabel() {
        return "RAM_ONLY_DEMO";
    }

    @Override
    public boolean isDurable() {
        return false;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // -------------------------------------------------------------------------------------------- accounts

    @Override
    public synchronized List<AccountDTO> listAccounts() {
        List<AccountDTO> out = new ArrayList<>();
        for (Acc a : accounts.values()) out.add(a.dto());
        out.sort(Comparator.comparing(AccountDTO::getAccountNumber));
        return out;
    }

    @Override
    public synchronized Optional<AccountDTO> findAccount(String accountNumber) {
        Acc a = accounts.get(accountNumber);
        return a == null ? Optional.empty() : Optional.of(a.dto());
    }

    @Override
    public synchronized Optional<StoredCredential> findCredential(String accountNumber) {
        Acc a = accounts.get(accountNumber);
        return a == null ? Optional.empty() : Optional.of(new StoredCredential(a.dto(), a.pinHash));
    }

    @Override
    public synchronized boolean createAccount(AccountDTO account, String pinHash) {
        if (accounts.containsKey(account.getAccountNumber())) return false;
        accounts.put(account.getAccountNumber(), new Acc(account.getAccountNumber(), account.getOwnerName(),
                account.getBalance(), account.getBranchId(), account.getStatus(), account.getDailyLimit(), pinHash,
                now()));
        return true;
    }

    @Override
    public synchronized boolean setAccountStatus(String accountNumber, AccountStatus status) {
        Acc a = accounts.get(accountNumber);
        if (a == null) return false;
        a.status = status;
        return true;
    }

    @Override
    public synchronized boolean setDailyLimit(String accountNumber, BigDecimal limit) {
        Acc a = accounts.get(accountNumber);
        if (a == null) return false;
        a.limit = limit;
        return true;
    }

    // ---------------------------------------------------------------------------------------- money movement

    private MoneyResult checkRequest(String requestId, String account, TransactionType type, BigDecimal amount) {
        TransactionDTO existing = requestId == null ? null : byRequest.get(requestId);
        if (existing == null) return null;
        boolean same = existing.getAccountNumber().equals(account) && existing.getType() == type
                && existing.getAmount().compareTo(amount) == 0;
        return same ? MoneyResult.duplicate(existing)
                : MoneyResult.rejected(MoneyResult.Outcome.REQUEST_ID_REUSED,
                "Mã yêu cầu đã được dùng cho một giao dịch khác");
    }

    private long allocateSeq(String origin, int count) {
        long first = originSeq.getOrDefault(origin, 0L) + 1;
        originSeq.put(origin, first + count - 1);
        return first;
    }

    private TransactionDTO book(String origin, String peerId, Acc acc, TransactionType type, BigDecimal amount,
                                String ref, String note, String txId, long seq, String requestId, Instant at) {
        TransactionDTO tx = new TransactionDTO(txId, acc.number, type, amount, acc.balance, ref, note, at, origin, seq,
                ++localSeq, requestId, TransactionSyncState.PENDING);
        ledger.add(tx);
        byId.put(txId, tx);
        byOrigin.put(origin + "#" + seq, tx);
        if (requestId != null) byRequest.put(requestId, tx);
        if (peerId != null) outbox.add(new OutboxRow(++outboxSeq, txId, peerId, at));
        return tx;
    }

    @Override
    public synchronized MoneyResult deposit(String origin, String peerId, String account, String requestId,
                                            BigDecimal amount, String note) {
        MoneyResult dup = checkRequest(requestId, account, TransactionType.DEPOSIT, amount);
        if (dup != null) return dup;
        Acc a = accounts.get(account);
        if (a == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, account);
        if (a.status == AccountStatus.LOCKED) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_LOCKED, account);
        if (a.balance.add(amount).compareTo(Money.MAX_BALANCE) > 0) {
            return MoneyResult.rejected(MoneyResult.Outcome.BALANCE_OVERFLOW, "");
        }
        a.balance = a.balance.add(amount);
        TransactionDTO tx = book(origin, peerId, a, TransactionType.DEPOSIT, amount, "", note,
                UUID.randomUUID().toString(), allocateSeq(origin, 1), requestId, now());
        return MoneyResult.ok(tx, null, a.balance);
    }

    private BigDecimal spentToday(String account, Instant dayStart) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TransactionDTO t : ledger) {
            if (t.getAccountNumber().equals(account) && t.getType().isOutgoing() && !t.getCreatedAt().isBefore(dayStart)) {
                sum = sum.add(t.getAmount());
            }
        }
        return sum;
    }

    @Override
    public synchronized MoneyResult withdraw(String origin, String peerId, String account, String requestId,
                                             BigDecimal amount, String note, Instant dayStart) {
        MoneyResult dup = checkRequest(requestId, account, TransactionType.WITHDRAW, amount);
        if (dup != null) return dup;
        Acc a = accounts.get(account);
        if (a == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, account);
        BigDecimal spent = spentToday(account, dayStart);
        MoneyResult.Outcome problem = LedgerMath.debitProblem(a.dto(), amount, spent);
        if (problem != null) {
            return MoneyResult.rejected(problem, problem == MoneyResult.Outcome.LIMIT_EXCEEDED
                    ? LedgerMath.limitDetail(a.dto(), spent) : "");
        }
        a.balance = a.balance.subtract(amount);
        TransactionDTO tx = book(origin, peerId, a, TransactionType.WITHDRAW, amount, "", note,
                UUID.randomUUID().toString(), allocateSeq(origin, 1), requestId, now());
        return MoneyResult.ok(tx, null, a.balance);
    }

    @Override
    public synchronized MoneyResult transfer(String origin, String peerId, String from, String to, String requestId,
                                             BigDecimal amount, String note, Instant dayStart) {
        MoneyResult dup = checkRequest(requestId, from, TransactionType.TRANSFER, amount);
        if (dup != null) return dup;
        Acc src = accounts.get(from);
        if (src == null) return MoneyResult.rejected(MoneyResult.Outcome.ACCOUNT_NOT_FOUND, from);
        Acc dst = accounts.get(to);
        if (dst == null) return MoneyResult.rejected(MoneyResult.Outcome.COUNTERPARTY_NOT_FOUND, to);
        if (dst.status == AccountStatus.LOCKED) return MoneyResult.rejected(MoneyResult.Outcome.COUNTERPARTY_LOCKED, to);
        BigDecimal spent = spentToday(from, dayStart);
        MoneyResult.Outcome problem = LedgerMath.debitProblem(src.dto(), amount, spent);
        if (problem != null) {
            return MoneyResult.rejected(problem, problem == MoneyResult.Outcome.LIMIT_EXCEEDED
                    ? LedgerMath.limitDetail(src.dto(), spent) : "");
        }
        if (dst.balance.add(amount).compareTo(Money.MAX_BALANCE) > 0) {
            return MoneyResult.rejected(MoneyResult.Outcome.BALANCE_OVERFLOW, "");
        }
        src.balance = src.balance.subtract(amount);
        dst.balance = dst.balance.add(amount);
        Instant at = now();
        long seq = allocateSeq(origin, 2);
        String debitId = UUID.randomUUID().toString();
        TransactionDTO debit = book(origin, peerId, src, TransactionType.TRANSFER, amount, to, note, debitId, seq, requestId, at);
        TransactionDTO credit = book(origin, peerId, dst, TransactionType.TRANSFER_IN, amount, from, note,
                debitId + "-C", seq + 1, null, at);
        return MoneyResult.ok(debit, credit, src.balance);
    }

    // ------------------------------------------------------------------------------------------- replication

    @Override
    public synchronized IngestResult ingestReplicated(String sourcePeerId, List<TransactionDTO> entries) {
        List<String> accepted = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<TransactionDTO> applied = new ArrayList<>();
        List<LedgerEvent> events = new ArrayList<>();
        long maxFromPeer = 0;
        for (TransactionDTO e : entries) {
            TransactionDTO atOrigin = byOrigin.get(e.getOriginServerId() + "#" + e.getOriginSequence());
            if (atOrigin != null) {
                if (atOrigin.getTransactionId().equals(e.getTransactionId())) {
                    accepted.add(e.getTransactionId());
                    if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
                } else {
                    rejected.put(e.getTransactionId(), "origin sequence " + e.getOriginServerId() + "#"
                            + e.getOriginSequence() + " already used by a different entry");
                }
                continue;
            }
            if (byId.containsKey(e.getTransactionId())) {
                rejected.put(e.getTransactionId(), "transaction id already exists with a different origin sequence");
                continue;
            }
            if (e.getClientRequestId() != null && byRequest.containsKey(e.getClientRequestId())) {
                TransactionDTO local = byRequest.get(e.getClientRequestId());
                events.add(new LedgerEvent(LedgerEvent.Kind.DUPLICATE_SUPPRESSED, e.getAccountNumber(),
                        "request " + e.getClientRequestId() + " already executed locally as " + local.getTransactionId()
                                + "; peer entry " + e.getTransactionId() + " not applied"));
                accepted.add(e.getTransactionId());
                if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
                continue;
            }
            Acc a = accounts.get(e.getAccountNumber());
            if (a == null) {
                rejected.put(e.getTransactionId(), "unknown account " + e.getAccountNumber() + " on this node");
                continue;
            }
            a.balance = e.getType().isCredit() ? a.balance.add(e.getAmount()) : a.balance.subtract(e.getAmount());
            TransactionDTO stored = new TransactionDTO(e.getTransactionId(), e.getAccountNumber(), e.getType(),
                    e.getAmount(), a.balance, e.getReferenceAccount(), e.getNote(), e.getCreatedAt(),
                    e.getOriginServerId(), e.getOriginSequence(), ++localSeq, e.getClientRequestId(),
                    TransactionSyncState.FROM_PEER);
            ledger.add(stored);
            byId.put(stored.getTransactionId(), stored);
            byOrigin.put(e.getOriginServerId() + "#" + e.getOriginSequence(), stored);
            if (e.getClientRequestId() != null) byRequest.put(e.getClientRequestId(), stored);
            applied.add(stored);
            accepted.add(e.getTransactionId());
            if (a.balance.signum() < 0 && !e.getType().isCredit()) {
                events.add(new LedgerEvent(LedgerEvent.Kind.OVERDRAFT_DETECTED, e.getAccountNumber(),
                        "balance " + a.balance.toPlainString() + " after applying " + e.getTransactionId()
                                + " from " + e.getOriginServerId()));
            }
            if (e.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, e.getOriginSequence());
        }
        long cp = Math.max(checkpoints.getOrDefault(sourcePeerId, 0L), maxFromPeer);
        checkpoints.put(sourcePeerId, cp);
        return new IngestResult(accepted, rejected, getInboundCheckpoint(sourcePeerId), applied, events);
    }

    // ----------------------------------------------------------------------------------------------- queries

    private TransactionDTO tagged(TransactionDTO t, String localServerId, String peerId) {
        boolean local = t.getOriginServerId().equals(localServerId);
        String status = null;
        if (local && peerId != null) {
            for (OutboxRow r : outbox) {
                if (r.txId.equals(t.getTransactionId()) && r.peerId.equals(peerId)) {
                    status = r.status;
                    break;
                }
            }
        }
        return t.withSyncState(LedgerMath.syncStateFor(local, status, peerId != null));
    }

    private List<TransactionDTO> newestFirst(String account, int limit, String localServerId, String peerId) {
        List<TransactionDTO> out = new ArrayList<>();
        for (int i = ledger.size() - 1; i >= 0 && out.size() < limit; i--) {
            TransactionDTO t = ledger.get(i);
            if (account == null || t.getAccountNumber().equals(account)) out.add(tagged(t, localServerId, peerId));
        }
        out.sort(Comparator.comparing(TransactionDTO::getCreatedAt).thenComparingLong(TransactionDTO::getLocalSequence).reversed());
        return out;
    }

    @Override
    public synchronized List<TransactionDTO> recentTransactions(String account, int limit, String localServerId, String peerId) {
        return newestFirst(account, limit, localServerId, peerId);
    }

    @Override
    public synchronized List<TransactionDTO> recentTransactionsAll(int limit, String localServerId, String peerId) {
        return newestFirst(null, limit, localServerId, peerId);
    }

    @Override
    public synchronized List<TransactionDTO> findByOriginAfter(String origin, long after, int limit) {
        List<TransactionDTO> out = new ArrayList<>();
        for (TransactionDTO t : ledger) {
            if (t.getOriginServerId().equals(origin) && t.getOriginSequence() > after) out.add(t);
        }
        out.sort(Comparator.comparingLong(TransactionDTO::getOriginSequence));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    @Override
    public synchronized LedgerSummaryDTO ledgerSummary(String serverId) {
        List<AccountDTO> all = listAccounts();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int locked = 0;
        List<String> overdrawn = new ArrayList<>();
        for (AccountDTO a : all) {
            total = total.add(a.getBalance());
            if (a.getStatus() == AccountStatus.LOCKED) locked++;
            if (a.getBalance().signum() < 0) overdrawn.add(a.getAccountNumber());
        }
        Map<String, Long> last = new TreeMap<>();
        for (TransactionDTO t : ledger) last.merge(t.getOriginServerId(), t.getOriginSequence(), Math::max);
        return new LedgerSummaryDTO(serverId, all.size(), locked, total, ledger.size(), last,
                LedgerMath.balanceDigest(all), overdrawn, Instant.now());
    }

    // ------------------------------------------------------------------------------------------ checkpoints

    @Override
    public synchronized long getInboundCheckpoint(String peerId) {
        long fromMessages = 0;
        for (TransactionDTO t : ledger) if (t.getOriginServerId().equals(peerId)) fromMessages = Math.max(fromMessages, t.getOriginSequence());
        return Math.max(checkpoints.getOrDefault(peerId, 0L), fromMessages);
    }

    @Override
    public void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) {
        // sync state is kept by the replication manager in memory for this store
    }

    @Override
    public synchronized List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) {
        List<OutboxEntry> out = new ArrayList<>();
        for (OutboxRow r : outbox) {
            if (out.size() >= limit) break;
            if (r.peerId.equals(peerId) && r.status.equals("PENDING") && !r.nextAttempt.isAfter(now)) {
                out.add(new OutboxEntry(r.id, r.attempts, byId.get(r.txId)));
            }
        }
        return out;
    }

    @Override
    public synchronized void markDelivered(Collection<Long> ids) {
        for (OutboxRow r : outbox) if (ids.contains(r.id) && r.status.equals("PENDING")) r.status = "DELIVERED";
    }

    @Override
    public synchronized boolean rejectOutbox(long id, String reason, Instant now, long base, long max, int maxAttempts) {
        for (OutboxRow r : outbox) {
            if (r.id != id) continue;
            r.attempts++;
            boolean dead = r.attempts >= maxAttempts;
            long delay = Math.min(max, base * (1L << Math.min(r.attempts - 1, 20)));
            r.status = dead ? "DEAD" : "PENDING";
            r.nextAttempt = now.plusMillis(delay);
            return dead;
        }
        return false;
    }

    @Override
    public synchronized OutboxStats outboxStats(String peerId) {
        long pending = 0, dead = 0;
        Instant oldest = null;
        for (OutboxRow r : outbox) {
            if (!r.peerId.equals(peerId)) continue;
            if (r.status.equals("PENDING")) {
                pending++;
                if (oldest == null || r.createdAt.isBefore(oldest)) oldest = r.createdAt;
            } else if (r.status.equals("DEAD")) dead++;
        }
        return new OutboxStats(pending, dead, oldest);
    }

    @Override
    public synchronized int requeueDead(String peerId, Instant now) {
        int n = 0;
        for (OutboxRow r : outbox) {
            if (r.peerId.equals(peerId) && r.status.equals("DEAD")) {
                r.status = "PENDING";
                r.attempts = 0;
                r.nextAttempt = now;
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------- audit

    @Override
    public synchronized void appendAudit(Instant at, String serverId, String actor, String action, String target,
                                         String result, String details) {
        Instant t = at.truncatedTo(ChronoUnit.MILLIS);
        String det = details == null ? "" : details.substring(0, Math.min(1000, details.length()));
        String prev = audit.isEmpty() ? AuditChain.GENESIS : audit.get(audit.size() - 1).getHash();
        String hash = AuditChain.hash(prev, t, serverId, actor, action, target, result, det);
        audit.add(new AuditEntryDTO(audit.size() + 1L, t, serverId, actor, action, target, result, det, prev, hash));
    }

    @Override
    public synchronized List<AuditEntryDTO> recentAudit(int limit) {
        List<AuditEntryDTO> out = new ArrayList<>();
        for (int i = audit.size() - 1; i >= 0 && out.size() < limit; i--) out.add(audit.get(i));
        return out;
    }

    @Override
    public synchronized AuditVerificationDTO verifyAuditChain() {
        return AuditChain.verify(new ArrayList<>(audit));
    }

    /** Test hook: rewrites one audit entry's details WITHOUT fixing its hash, as an attacker with DB access would. */
    synchronized void tamperAuditForTest(int index, String newDetails) {
        AuditEntryDTO e = audit.get(index);
        audit.set(index, new AuditEntryDTO(e.getId(), e.getTimestamp(), e.getServerId(), e.getActor(), e.getAction(),
                e.getTarget(), e.getResult(), newDetails, e.getPrevHash(), e.getHash()));
    }
}
