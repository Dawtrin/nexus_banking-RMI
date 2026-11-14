package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.server.monitoring.SystemLogService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/** Constant-time admin token check. No configured token means the admin API is disabled, never open. */
public final class AdminAuth {
    private final String token;
    private final SystemLogService logService;
    private final AtomicLong failures = new AtomicLong();
    private volatile AuditService audit;

    public void setAudit(AuditService audit) {
        this.audit = audit;
    }

    public AdminAuth(String token, SystemLogService logService) {
        this.token = token;
        this.logService = logService;
    }

    /** Returns null when authorized, otherwise the failure to return to the caller. */
    public <T> OperationResult<T> check(String presented) {
        if (token == null) {
            return OperationResult.failure(ErrorCodes.ADMIN_DISABLED,
                    "Administrative API is disabled: no admin token is configured on this server");
        }
        boolean ok = presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (ok) return null;
        long n = failures.incrementAndGet();
        if (n <= 20 || n % 100 == 0) {
            logService.logEvent(EventSeverity.WARN, "ADMIN", "Rejected admin request with invalid token (failure #" + n + ")");
            if (audit != null) audit.record("unknown", "ADMIN_AUTH", "admin-api", "DENIED", "invalid token (failure #" + n + ")");
        }
        return OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Invalid admin token");
    }

    public long getFailureCount() {
        return failures.get();
    }
}
