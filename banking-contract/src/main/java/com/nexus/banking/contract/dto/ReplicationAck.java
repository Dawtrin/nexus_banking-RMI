package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entry outcome of a replication batch. A transaction id appears in {@code acceptedTransactionIds} only after the
 * receiving branch has durably committed it (or already held / deliberately suppressed an identical request).
 */
public class ReplicationAck implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    private final List<String> acceptedTransactionIds;
    private final Map<String, String> rejected;

    public ReplicationAck(List<String> acceptedTransactionIds, Map<String, String> rejected) {
        this.acceptedTransactionIds = acceptedTransactionIds == null ? new ArrayList<>() : new ArrayList<>(acceptedTransactionIds);
        this.rejected = rejected == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rejected);
    }

    public List<String> getAcceptedTransactionIds() { return Collections.unmodifiableList(acceptedTransactionIds); }

    /** transactionId to human-readable rejection reason. */
    public Map<String, String> getRejected() { return Collections.unmodifiableMap(rejected); }
}
