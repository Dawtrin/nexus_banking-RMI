package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.BankingAdminService;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.service.ClientSessionManager.Session;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.PinHasher;
import com.nexus.banking.server.store.StorageException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Risk-control and operations actions of this node. Every call is token-authenticated, validated, executed against the
 * real store/session state and recorded in the hash-chained audit ledger (success and failure).
 */
public class AdminServiceImpl implements BankingAdminService {
    private final ServerConfig config;
    private final AdminAuth auth;
    private final BankingStore store;
    private final ClientSessionManager sessions;
    private final PeerReplicationManager replication;
    private final AuditService audit;
    private final SystemLogService logService;
    private final CallbackNotifier notifier;

    public AdminServiceImpl(ServerConfig config, AdminAuth auth, BankingStore store, ClientSessionManager sessions,
                            PeerReplicationManager replication, AuditService audit, SystemLogService logService,
                            CallbackNotifier notifier) {
        this.config = config;
        this.auth = auth;
        this.store = store;
        this.sessions = sessions;
        this.replication = replication;
        this.audit = audit;
        this.logService = logService;
        this.notifier = notifier;
    }

    private <T> OperationResult<T> storageFailure(String actor, String action, String target, StorageException e) {
        audit.record(actor, action, target, "FAILED", "storage: " + e.getMessage());
        logService.logEvent(EventSeverity.ERROR, "ADMIN", action + " failed: " + e.getMessage());
        return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Storage is unavailable; nothing was changed.");
    }

    @Override
    public OperationResult<List<AccountDTO>> listAccounts(String adminToken) {
        OperationResult<List<AccountDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        try {
            return OperationResult.success(store.listAccounts());
        } catch (StorageException e) {
            return storageFailure("-", "LIST_ACCOUNTS", "accounts", e);
        }
    }

    @Override
    public OperationResult<Void> createAccount(String adminToken, String actor, String accountNumber, String ownerName,
                                               String branchId, BigDecimal openingBalance, BigDecimal dailyLimit, String pin) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.accountNumber(accountNumber);
        if (e == null) e = Validation.ownerName(ownerName);
        if (e == null) e = Validation.branchId(branchId);
        if (e == null) e = Validation.pin(pin);
        if (e == null && (openingBalance == null || openingBalance.signum() < 0 || openingBalance.stripTrailingZeros().scale() > 2
                || openingBalance.compareTo(Money.MAX_BALANCE) > 0)) e = "Số dư đầu kỳ không hợp lệ";
        if (e == null && (dailyLimit == null || dailyLimit.signum() <= 0 || dailyLimit.stripTrailingZeros().scale() > 2
                || dailyLimit.compareTo(Money.MAX_BALANCE) > 0)) e = "Hạn mức ngày không hợp lệ";
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            AccountDTO acc = new AccountDTO(accountNumber, ownerName.trim(), openingBalance.setScale(2), branchId,
                    AccountStatus.ACTIVE, Instant.now(), dailyLimit.setScale(2));
            boolean created = store.createAccount(acc, PinHasher.hash(pin));
            audit.record(actor, "OPEN_ACCOUNT", accountNumber, created ? "OK" : "REJECTED",
                    created ? ownerName.trim() + ", opening " + Money.format(acc.getBalance()) + ", limit " + Money.format(acc.getDailyLimit())
                            : "account already exists");
            return created ? OperationResult.ok("Đã mở tài khoản") : OperationResult.failure(ErrorCodes.VALIDATION,
                    "Tài khoản " + accountNumber + " đã tồn tại");
        } catch (StorageException ex) {
            return storageFailure(actor, "OPEN_ACCOUNT", accountNumber, ex);
        }
    }

    @Override
    public OperationResult<Void> setAccountStatus(String adminToken, String actor, String accountNumber,
                                                  AccountStatus status, String reason) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.accountNumber(accountNumber);
        if (e == null && status == null) e = "status is required";
        if (e == null && reason != null && reason.length() > 200) e = "reason must be at most 200 characters";
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        String why = reason == null || reason.isBlank() ? "no reason given" : reason.trim();
        try {
            boolean ok = store.setAccountStatus(accountNumber, status);
            audit.record(actor, status == AccountStatus.LOCKED ? "LOCK_ACCOUNT" : "UNLOCK_ACCOUNT", accountNumber,
                    ok ? "OK" : "REJECTED", ok ? why : "account not found");
            if (!ok) return OperationResult.failure(ErrorCodes.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản " + accountNumber);
            if (status == AccountStatus.LOCKED) {
                String msg = "Tài khoản đã bị khóa bởi Trung tâm điều hành: " + why;
                notifier.accountStatus(accountNumber, status, msg);
                sessions.evictByAccount(accountNumber, null, msg);
            } else {
                notifier.accountStatus(accountNumber, status, "Tài khoản đã được mở khóa");
            }
            return OperationResult.ok(status == AccountStatus.LOCKED ? "Đã khóa tài khoản" : "Đã mở khóa tài khoản");
        } catch (StorageException ex) {
            return storageFailure(actor, "SET_ACCOUNT_STATUS", accountNumber, ex);
        }
    }

    @Override
    public OperationResult<Void> setDailyLimit(String adminToken, String actor, String accountNumber, BigDecimal newLimit) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.accountNumber(accountNumber);
        if (e == null && (newLimit == null || newLimit.signum() <= 0 || newLimit.stripTrailingZeros().scale() > 2
                || newLimit.compareTo(Money.MAX_BALANCE) > 0)) e = "Hạn mức không hợp lệ";
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            AccountDTO before = store.findAccount(accountNumber).orElse(null);
            boolean ok = before != null && store.setDailyLimit(accountNumber, newLimit.setScale(2));
            audit.record(actor, "CHANGE_LIMIT", accountNumber, ok ? "OK" : "REJECTED", ok
                    ? Money.format(before.getDailyLimit()) + " -> " + Money.format(newLimit) : "account not found");
            return ok ? OperationResult.ok("Đã đổi hạn mức") : OperationResult.failure(ErrorCodes.ACCOUNT_NOT_FOUND,
                    "Không tìm thấy tài khoản " + accountNumber);
        } catch (StorageException ex) {
            return storageFailure(actor, "CHANGE_LIMIT", accountNumber, ex);
        }
    }

    @Override
    public OperationResult<List<TransactionDTO>> listTransactions(String adminToken, String accountNumber, int limit) {
        OperationResult<List<TransactionDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        if (limit < 1 || limit > 500) return OperationResult.failure(ErrorCodes.VALIDATION, "limit must be between 1 and 500");
        if (accountNumber != null && !accountNumber.isBlank()) {
            String e = Validation.accountNumber(accountNumber);
            if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        }
        try {
            boolean all = accountNumber == null || accountNumber.isBlank();
            return OperationResult.success(all
                    ? store.recentTransactionsAll(limit, config.getServerId(), config.getPeerId())
                    : store.recentTransactions(accountNumber, limit, config.getServerId(), config.getPeerId()));
        } catch (StorageException ex) {
            return storageFailure("-", "LIST_TRANSACTIONS", "ledger", ex);
        }
    }

    @Override
    public OperationResult<Void> disconnectSession(String adminToken, String actor, String sessionId) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.sessionIdFormat(sessionId);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        Session s = sessions.findAny(sessionId);
        if (s == null) {
            audit.record(actor, "DISCONNECT_SESSION", sessionId, "REJECTED", "session not found");
            return OperationResult.failure(ErrorCodes.INVALID_SESSION, "Session not found");
        }
        sessions.forceDisconnect(sessionId, "Phiên làm việc đã bị Trung tâm điều hành ngắt kết nối.");
        audit.record(actor, "DISCONNECT_SESSION", sessionId, "OK", "account " + s.getAccountNumber());
        return OperationResult.ok("Session disconnected");
    }

    @Override
    public OperationResult<Integer> disconnectAccount(String adminToken, String actor, String accountNumber, String reason) {
        OperationResult<Integer> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.accountNumber(accountNumber);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        String why = reason == null || reason.isBlank() ? "Trung tâm điều hành ngắt kết nối khẩn cấp" : reason.trim();
        int n = sessions.evictByAccount(accountNumber, null, "Phiên làm việc bị ngắt: " + why).size();
        audit.record(actor, "DISCONNECT_ACCOUNT", accountNumber, "OK", n + " session(s): " + why);
        return OperationResult.success(n);
    }

    @Override
    public OperationResult<List<AuditEntryDTO>> getAuditLog(String adminToken, int limit) {
        OperationResult<List<AuditEntryDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        if (limit < 1 || limit > 500) return OperationResult.failure(ErrorCodes.VALIDATION, "limit must be between 1 and 500");
        try {
            return OperationResult.success(audit.recent(limit));
        } catch (StorageException ex) {
            return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Audit log is unavailable: storage error");
        }
    }

    @Override
    public OperationResult<AuditVerificationDTO> verifyAuditChain(String adminToken) {
        OperationResult<AuditVerificationDTO> denied = auth.check(adminToken);
        if (denied != null) return denied;
        try {
            AuditVerificationDTO v = store.verifyAuditChain();
            if (!v.isValid()) logService.logEvent(EventSeverity.ERROR, "AUDIT", v.getMessage());
            return OperationResult.success(v);
        } catch (StorageException ex) {
            return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Audit log is unavailable: storage error");
        }
    }

    @Override
    public OperationResult<LedgerSummaryDTO> getLedgerSummary(String adminToken) {
        OperationResult<LedgerSummaryDTO> denied = auth.check(adminToken);
        if (denied != null) return denied;
        try {
            return OperationResult.success(store.ledgerSummary(config.getServerId()));
        } catch (StorageException ex) {
            return storageFailure("-", "LEDGER_SUMMARY", "ledger", ex);
        }
    }

    @Override
    public OperationResult<Integer> requeueDeadLetters(String adminToken, String actor) {
        OperationResult<Integer> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            int n = replication.requeueDead();
            audit.record(actor, "REQUEUE_DEAD_LETTERS", config.getPeerId(), "OK", n + " entr(ies)");
            return OperationResult.success(n);
        } catch (StorageException ex) {
            return storageFailure(actor, "REQUEUE_DEAD_LETTERS", config.getPeerId(), ex);
        }
    }

    @Override
    public OperationResult<Void> syncNow(String adminToken, String actor) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        replication.wake();
        audit.record(actor, "SYNC_NOW", config.getPeerId(), "OK", "replication cycle requested");
        return OperationResult.ok("Replication cycle requested");
    }

    @Override
    public OperationResult<Map<String, String>> getServerConfig(String adminToken) {
        OperationResult<Map<String, String>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("server.id", config.getServerId());
        m.put("server.branch.name", config.getBranchName());
        m.put("server.advertised.host", config.getAdvertisedHost());
        m.put("server.rmi.registry.port", String.valueOf(config.getRmiRegistryPort()));
        m.put("server.rmi.service.port", String.valueOf(config.getRmiServicePort()));
        m.put("server.peer.id", config.getPeerId());
        m.put("server.peer.host", config.getPeerHost());
        m.put("server.peer.registry.port", String.valueOf(config.getPeerRegistryPort()));
        m.put("server.session.single", String.valueOf(config.isSingleSessionEnabled()));
        m.put("server.business.timezone", config.getBusinessZone().getId());
        m.put("database.enabled", String.valueOf(config.getDatabaseConfig().isEnabled()));
        m.put("database.host", config.getDatabaseConfig().getHost());
        m.put("database.port", String.valueOf(config.getDatabaseConfig().getPort()));
        m.put("database.name", config.getDatabaseConfig().getDatabaseName());
        m.put("database.schema.auto", String.valueOf(config.isSchemaAutoApply()));
        m.put("replication.batch.size", String.valueOf(config.getReplicationBatchSize()));
        m.put("replication.poll.millis", String.valueOf(config.getReplicationPollMillis()));
        m.put("replication.catchup.page", String.valueOf(config.getCatchUpPageSize()));
        m.put("replication.max.rejected.attempts", String.valueOf(config.getMaxRejectedAttempts()));
        m.put("transport.tls", String.valueOf(config.isSslEnabled()));
        m.put("transport.mtls", String.valueOf(config.isSslNeedClientAuth()));
        m.put("admin.api", config.getAdminToken() == null ? "disabled" : "enabled");
        return OperationResult.success(m);
    }
}
