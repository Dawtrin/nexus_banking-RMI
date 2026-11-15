package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** Transaction counters of one fixed-size time bucket (counts of individual ledger entries, never batches). */
public class ThroughputPointDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Instant bucketStart;
    private final int bucketSeconds;
    private final int stored;
    private final int replicatedIn;
    private final int replicatedOut;

    public ThroughputPointDTO(Instant bucketStart, int bucketSeconds, int stored, int replicatedIn, int replicatedOut) {
        this.bucketStart = bucketStart;
        this.bucketSeconds = bucketSeconds;
        this.stored = stored;
        this.replicatedIn = replicatedIn;
        this.replicatedOut = replicatedOut;
    }

    public Instant getBucketStart() { return bucketStart; }
    public int getBucketSeconds() { return bucketSeconds; }
    /** Ledger entries created for local customers in this bucket. */
    public int getStored() { return stored; }
    public int getReplicatedIn() { return replicatedIn; }
    public int getReplicatedOut() { return replicatedOut; }
}
