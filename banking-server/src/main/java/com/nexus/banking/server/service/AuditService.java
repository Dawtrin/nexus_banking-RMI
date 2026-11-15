package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.StorageException;

import java.time.Instant;
import java.util.List;

/** Persists audit records (hash-chained by the store). A failing audit write is logged as an error, never swallowed silently. */
public class AuditService {
    private final String serverId;
    private final BankingStore store;
    private final SystemLogService logService;

    public AuditService(String serverId, BankingStore store, SystemLogService logService) {
        this.serverId = serverId;
        this.store = store;
        this.logService = logService;
    }

    public void record(String actor, String action, String target, String result, String details) {
        try {
            store.appendAudit(Instant.now(), serverId, SystemLogService.sanitize(actor), action,
                    SystemLogService.sanitize(target), result, SystemLogService.sanitize(details));
        } catch (StorageException e) {
            logService.logEvent(EventSeverity.ERROR, "AUDIT", "Audit record could not be stored (" + action + "): " + e.getMessage());
        }
    }

    /** Records what the store noticed while applying replicated entries (suppressed duplicates, overdrafts). */
    public void recordLedgerEvents(List<com.nexus.banking.server.store.LedgerEvent> events) {
        for (com.nexus.banking.server.store.LedgerEvent e : events) {
            record("system", e.kind().name(), e.account(), "WARN", e.details());
            logService.logEvent(EventSeverity.WARN, "LEDGER", e.kind() + " account=" + e.account() + ": " + e.details());
        }
    }

    public List<AuditEntryDTO> recent(int limit) throws StorageException {
        return store.recentAudit(limit);
    }
}
