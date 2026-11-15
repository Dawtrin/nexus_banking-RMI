package com.nexus.banking.server.service;

import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.store.IngestResult;

/** What happens after replicated entries were committed locally: metrics, audit events and balance pushes. */
public class IngestPostProcessor {
    private final ServerMetrics metrics;
    private final AuditService audit;
    private final CallbackNotifier notifier;

    public IngestPostProcessor(ServerMetrics metrics, AuditService audit, CallbackNotifier notifier) {
        this.metrics = metrics;
        this.audit = audit;
        this.notifier = notifier;
    }

    public void handle(IngestResult r) {
        metrics.addEntriesReplicatedIn(r.applied().size());
        if (audit != null) audit.recordLedgerEvents(r.events());
        if (notifier != null) r.applied().forEach(t -> notifier.balanceChanged(t, true));
    }
}
