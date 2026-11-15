package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.TransactionDTO;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a replicated batch: accepted ids are committed (or already present / deliberately suppressed);
 * rejected are refused for this payload. {@code applied} lists the entries that really changed a balance here
 * (with this node's running balance), so the caller can push notifications.
 */
public record IngestResult(List<String> accepted, Map<String, String> rejected, long checkpoint,
                           List<TransactionDTO> applied, List<LedgerEvent> events) {
    public IngestResult(List<String> accepted, Map<String, String> rejected, long checkpoint) {
        this(accepted, rejected, checkpoint, List.of(), List.of());
    }
}
