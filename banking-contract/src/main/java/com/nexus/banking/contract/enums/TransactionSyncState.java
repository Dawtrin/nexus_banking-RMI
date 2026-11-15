package com.nexus.banking.contract.enums;

import java.io.Serializable;

/** Replication state of a ledger entry as seen from the node that answers. */
public enum TransactionSyncState implements Serializable {
    /** Originated on this node and not yet acknowledged by the peer branch (outbox PENDING). */
    PENDING("Chờ đồng bộ"),
    /** Originated on this node and committed on the peer branch as well. */
    SYNCED("Đã đồng bộ"),
    /** Originated on the other branch and applied here by replication. */
    FROM_PEER("Từ chi nhánh khác"),
    /** Could not be delivered after repeated rejections (dead letter). */
    FAILED("Lỗi đồng bộ");

    private final String label;

    TransactionSyncState(String label) { this.label = label; }

    public String getLabel() { return label; }
}
