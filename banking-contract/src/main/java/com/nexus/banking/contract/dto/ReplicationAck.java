package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-message outcome of a replication batch. A message id appears in {@code acceptedMessageIds} only
 * after the receiving node has durably committed it (or already held an identical copy).
 */
public class ReplicationAck implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final List<String> acceptedMessageIds;
    private final Map<String, String> rejected;

    public ReplicationAck(List<String> acceptedMessageIds, Map<String, String> rejected) {
        this.acceptedMessageIds = acceptedMessageIds == null ? new ArrayList<>() : new ArrayList<>(acceptedMessageIds);
        this.rejected = rejected == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rejected);
    }

    public List<String> getAcceptedMessageIds() {
        return Collections.unmodifiableList(acceptedMessageIds);
    }

    /** messageId to human-readable rejection reason (permanent for this payload). */
    public Map<String, String> getRejected() {
        return Collections.unmodifiableMap(rejected);
    }
}
