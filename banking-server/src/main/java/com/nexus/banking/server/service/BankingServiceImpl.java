package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.remote.BankingService;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.rmi.RemoteClientContext;
import com.nexus.banking.server.service.ClientSessionManager.Session;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.MoneyResult;
import com.nexus.banking.server.store.PinHasher;
import com.nexus.banking.server.store.StorageException;
import com.nexus.banking.server.store.StoredCredential;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Customer-facing core-banking service of this branch. Authorization is enforced here for every call: a valid session
 * that belongs to the account being operated on. Money is reported as moved only after the store has committed it.
 */
public class BankingServiceImpl implements BankingService {
    /** Movements at or above this amount are written to the audit ledger (who/when/how much). */
    public static final BigDecimal LARGE_TRANSACTION = new BigDecimal("10000000");

    private final ServerConfig config;
    private final BankingStore store;
    private final ClientSessionManager sessions;
    private final PeerReplicationManager replication;
    private final ServerMetrics metrics;
    private final SystemLogService logService;
    private final CallbackNotifier notifier;
    private final AuditService audit;
    private final LoginThrottle throttle;
    private final Supplier<Instant> clock;

    public BankingServiceImpl(ServerConfig config, BankingStore store, ClientSessionManager sessions,
                              PeerReplicationManager replication, ServerMetrics metrics, SystemLogService logService,
                              CallbackNotifier notifier, AuditService audit, LoginThrottle throttle) {
        this(config, store, sessions, replication, metrics, logService, notifier, audit, throttle, Instant::now);
    }

    public BankingServiceImpl(ServerConfig config, BankingStore store, ClientSessionManager sessions,
                              PeerReplicationManager replication, ServerMetrics metrics, SystemLogService logService,
                              CallbackNotifier notifier, AuditService audit, LoginThrottle throttle, Supplier<Instant> clock) {
        this.config = config;
        this.store = store;
        this.sessions = sessions;
        this.replication = replication;
        this.metrics = metrics;
        this.logService = logService;
        this.notifier = notifier;
        this.audit = audit;
        this.throttle = throttle;
        this.clock = clock;
    }

    private static <T> OperationResult<T> fail(String code, String message) {
        return OperationResult.failure(code, message);
    }

    private <T> OperationResult<T> invalidSession(String sessionId) {
        Session s = sessions.findAny(sessionId);
        if (s != null && s.isEvicted()) return fail(ErrorCodes.SESSION_EVICTED, s.getDisconnectReason());
        return fail(ErrorCodes.INVALID_SESSION, "Phiên làm việc không hợp lệ hoặc đã hết hạn");
    }

    private <T> OperationResult<T> storageFailure(StorageException e) {
        logService.logEvent(EventSeverity.ERROR, "STORAGE", "Operation failed: " + e.getMessage());
        return fail(ErrorCodes.STORAGE_UNAVAILABLE,
                "Hệ thống lưu trữ tạm thời không khả dụng; giao dịch CHƯA được thực hiện. Vui lòng thử lại.");
    }

    /** Start of "today" in the bank's business time zone: the daily limit window. */
    private Instant dayStart() {
        ZoneId zone = config.getBusinessZone();
        return ZonedDateTime.ofInstant(clock.get(), zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    // ------------------------------------------------------------------------------------------------ login

    @Override
    public OperationResult<LoginResultDTO> login(String accountNumber, String pin, String clientInstanceId,
                                                 BankingCallback callback) {
        String e = Validation.accountNumber(accountNumber);
        if (e == null) e = Validation.pin(pin);
        if (e == null) e = Validation.instanceId(clientInstanceId);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        long wait = throttle.secondsBlocked(accountNumber);
        if (wait > 0) {
            return fail(ErrorCodes.TOO_MANY_ATTEMPTS, "Nhập sai PIN quá nhiều lần. Vui lòng thử lại sau " + wait + " giây.");
        }
        try {
            Optional<StoredCredential> cred = store.findCredential(accountNumber);
            if (cred.isEmpty() || !PinHasher.verify(pin, cred.get().pinHash())) {
                boolean blocked = throttle.recordFailure(accountNumber);
                audit.record(accountNumber, "LOGIN_FAILED", accountNumber, "DENIED",
                        (cred.isEmpty() ? "unknown account" : "wrong PIN") + (blocked ? "; temporarily blocked" : "")
                                + " from " + RemoteClientContext.getObservedClientHost());
                return fail(ErrorCodes.AUTH_FAILED, "Số tài khoản hoặc mã PIN không đúng");
            }
            throttle.recordSuccess(accountNumber);
            AccountDTO account = cred.get().account();
            if (account.getStatus() == AccountStatus.LOCKED) {
                audit.record(accountNumber, "LOGIN_DENIED", accountNumber, "DENIED", "account is locked");
                return fail(ErrorCodes.ACCOUNT_LOCKED, "Tài khoản đang bị khóa. Vui lòng liên hệ chi nhánh.");
            }
            String host = RemoteClientContext.getObservedClientHost();
            if (config.isSingleSessionEnabled()) {
                String reason = "Tài khoản của bạn vừa được đăng nhập trên một thiết bị khác (" + host + ", "
                        + config.getBranchName() + "). Phiên này đã bị đóng vì lý do bảo mật.";
                sessions.evictByAccount(accountNumber, clientInstanceId, reason);
                if (replication != null) replication.evictPeerSession(accountNumber, clientInstanceId, reason);
            }
            Session s = sessions.register(accountNumber, account.getOwnerName(), clientInstanceId, host, callback);
            if (s == null) return fail(ErrorCodes.VALIDATION, "Hệ thống đang quá tải, vui lòng thử lại sau");
            audit.record(accountNumber, "LOGIN", accountNumber, "OK", "from " + host + " at " + config.getServerId());
            return OperationResult.success("Đăng nhập thành công tại " + config.getBranchName(),
                    new LoginResultDTO(sessions.toDto(s), account, config.getServerId(), config.getBranchName(), callback != null));
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    /** The usable session owning {@code accountNumber}, or the failure to return. */
    private <T> Object authorize(String sessionId, String accountNumber) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return this.<T>invalidSession(sessionId);
        String e = Validation.accountNumber(accountNumber);
        if (e != null) return OperationResult.<T>failure(ErrorCodes.VALIDATION, e);
        if (!s.getAccountNumber().equals(accountNumber)) {
            return OperationResult.<T>failure(ErrorCodes.NOT_ACCOUNT_OWNER, "Bạn chỉ được thao tác trên tài khoản của chính mình");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T> OperationResult<T> asFailure(Object o) {
        return (OperationResult<T>) o;
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Override
    public OperationResult<AccountDTO> getAccount(String sessionId, String accountNumber) {
        Object a = authorize(sessionId, accountNumber);
        if (a instanceof OperationResult) return asFailure(a);
        try {
            Optional<AccountDTO> acc = store.findAccount(accountNumber);
            return acc.<OperationResult<AccountDTO>>map(OperationResult::success)
                    .orElseGet(() -> fail(ErrorCodes.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản"));
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    @Override
    public OperationResult<String> lookupAccountHolder(String sessionId, String accountNumber) {
        if (sessions.getUsable(sessionId) == null) return invalidSession(sessionId);
        String e = Validation.accountNumber(accountNumber);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        try {
            Optional<AccountDTO> acc = store.findAccount(accountNumber);
            if (acc.isEmpty()) return fail(ErrorCodes.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản " + accountNumber);
            if (acc.get().getStatus() == AccountStatus.LOCKED) {
                return fail(ErrorCodes.ACCOUNT_LOCKED, "Tài khoản thụ hưởng đang bị khóa");
            }
            return OperationResult.success(acc.get().getOwnerName());
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    @Override
    public OperationResult<List<TransactionDTO>> getRecentTransactions(String sessionId, String accountNumber, int limit) {
        Object a = authorize(sessionId, accountNumber);
        if (a instanceof OperationResult) return asFailure(a);
        String e = Validation.pageSize(limit);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        try {
            return OperationResult.success(store.recentTransactions(accountNumber, limit, config.getServerId(), config.getPeerId()));
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    // --------------------------------------------------------------------------------------- money movement

    private enum Kind { DEPOSIT, WITHDRAW, TRANSFER }

    @Override
    public OperationResult<TransactionDTO> deposit(String sessionId, String accountNumber, String requestId,
                                                   BigDecimal amount, String note) {
        return move(Kind.DEPOSIT, sessionId, accountNumber, null, requestId, amount, note);
    }

    @Override
    public OperationResult<TransactionDTO> withdraw(String sessionId, String accountNumber, String requestId,
                                                    BigDecimal amount, String note) {
        return move(Kind.WITHDRAW, sessionId, accountNumber, null, requestId, amount, note);
    }

    @Override
    public OperationResult<TransactionDTO> transfer(String sessionId, String fromAccount, String toAccount,
                                                    String requestId, BigDecimal amount, String note) {
        return move(Kind.TRANSFER, sessionId, fromAccount, toAccount, requestId, amount, note);
    }

    private OperationResult<TransactionDTO> move(Kind kind, String sessionId, String account, String toAccount,
                                                 String requestId, BigDecimal amount, String rawNote) {
        Object a = authorize(sessionId, account);
        if (a instanceof OperationResult) return asFailure(a);
        String e = Validation.requestId(requestId);
        if (e == null) e = Validation.note(rawNote);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        String amountProblem = Validation.amount(amount);
        if (amountProblem != null) return fail(ErrorCodes.INVALID_AMOUNT, amountProblem);
        BigDecimal value = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        String note = Validation.normalizeNote(rawNote);
        if (kind == Kind.TRANSFER) {
            String te = Validation.accountNumber(toAccount);
            if (te != null) return fail(ErrorCodes.VALIDATION, "Tài khoản thụ hưởng: " + te);
            if (toAccount.equals(account)) return fail(ErrorCodes.SAME_ACCOUNT, "Không thể chuyển khoản cho chính mình");
        }
        try {
            String origin = config.getServerId();
            String peer = config.getPeerId();
            MoneyResult r = switch (kind) {
                case DEPOSIT -> store.deposit(origin, peer, account, requestId, value, note);
                case WITHDRAW -> store.withdraw(origin, peer, account, requestId, value, note, dayStart());
                case TRANSFER -> store.transfer(origin, peer, account, toAccount, requestId, value, note, dayStart());
            };
            return toResult(kind, r, account, toAccount, requestId);
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    private OperationResult<TransactionDTO> toResult(Kind kind, MoneyResult r, String account, String toAccount, String requestId) {
        switch (r.outcome()) {
            case OK -> {
                TransactionDTO tx = r.transaction();
                metrics.incrementTransactionsStored();
                notifier.balanceChanged(tx, false);
                if (r.creditLeg() != null) {
                    metrics.incrementTransactionsStored();
                    notifier.balanceChanged(r.creditLeg(), false);
                }
                replication.wake();
                if (tx.getAmount().compareTo(LARGE_TRANSACTION) >= 0) {
                    audit.record(account, "LARGE_" + tx.getType(), account, "OK", Money.format(tx.getAmount())
                            + (toAccount == null ? "" : " -> " + toAccount) + " tx=" + tx.getTransactionId());
                }
                String durable = store.isDurable() ? "" : " (chế độ RAM, KHÔNG bền vững)";
                return OperationResult.success("Giao dịch thành công tại " + config.getBranchName() + durable, tx);
            }
            case DUPLICATE -> {
                audit.record(account, "IDEMPOTENT_REPLAY", account, "OK", "request " + requestId
                        + " already executed as " + r.transaction().getTransactionId() + "; no money moved again");
                return OperationResult.success("Giao dịch đã được xử lý trước đó (yêu cầu lặp lại) - không trừ tiền lần 2",
                        r.transaction());
            }
            case ACCOUNT_NOT_FOUND -> {
                return fail(ErrorCodes.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản");
            }
            case ACCOUNT_LOCKED -> {
                return fail(ErrorCodes.ACCOUNT_LOCKED, "Tài khoản đang bị khóa");
            }
            case COUNTERPARTY_NOT_FOUND -> {
                return fail(ErrorCodes.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản thụ hưởng " + toAccount);
            }
            case COUNTERPARTY_LOCKED -> {
                return fail(ErrorCodes.ACCOUNT_LOCKED, "Tài khoản thụ hưởng đang bị khóa");
            }
            case INSUFFICIENT_FUNDS -> {
                return fail(ErrorCodes.INSUFFICIENT_FUNDS, "Số dư không đủ để thực hiện giao dịch");
            }
            case LIMIT_EXCEEDED -> {
                return fail(ErrorCodes.LIMIT_EXCEEDED, "Vượt hạn mức giao dịch trong ngày. " + r.detail());
            }
            case REQUEST_ID_REUSED -> {
                return fail(ErrorCodes.REQUEST_ID_REUSED, r.detail());
            }
            case BALANCE_OVERFLOW -> {
                return fail(ErrorCodes.VALIDATION, "Số dư sau giao dịch vượt giới hạn cho phép");
            }
        }
        return fail(ErrorCodes.INTERNAL, "Unexpected outcome");
    }

    // ------------------------------------------------------------------------------------------------ session

    @Override
    public OperationResult<Void> heartbeat(String sessionId) {
        return sessions.getUsable(sessionId) == null ? invalidSession(sessionId) : OperationResult.ok();
    }

    @Override
    public OperationResult<Void> logout(String sessionId) {
        if (sessionId != null) sessions.disconnect(sessionId);
        return OperationResult.ok();
    }

    @Override
    public String getServerId() {
        return config.getServerId();
    }
}
