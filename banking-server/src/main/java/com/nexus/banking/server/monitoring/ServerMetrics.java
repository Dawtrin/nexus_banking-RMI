package com.nexus.banking.server.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime counters. Every counter counts individual ledger entries / events, never batches. */
public class ServerMetrics {
    private final Instant startedAt = Instant.now();
    private final ThroughputRecorder recorder = new ThroughputRecorder();
    private final AtomicLong transactionsStored = new AtomicLong();
    private final AtomicLong entriesReplicatedOut = new AtomicLong();
    private final AtomicLong entriesReplicatedIn = new AtomicLong();
    private final AtomicLong replicationErrors = new AtomicLong();
    private final AtomicLong rejectedEntries = new AtomicLong();

    public Instant getStartedAt() { return startedAt; }
    public long getUptimeSeconds() { return Duration.between(startedAt, Instant.now()).getSeconds(); }

    public ThroughputRecorder getRecorder() { return recorder; }
    public void incrementTransactionsStored() { transactionsStored.incrementAndGet(); recorder.stored(1); }
    public void addEntriesReplicatedOut(long n) { entriesReplicatedOut.addAndGet(n); recorder.replicatedOut((int) n); }
    public void addEntriesReplicatedIn(long n) { entriesReplicatedIn.addAndGet(n); recorder.replicatedIn((int) n); }
    public void incrementReplicationErrors() { replicationErrors.incrementAndGet(); }
    public void addRejectedEntries(long n) { rejectedEntries.addAndGet(n); }

    public long getTransactionsStored() { return transactionsStored.get(); }
    public long getEntriesReplicatedOut() { return entriesReplicatedOut.get(); }
    public long getEntriesReplicatedIn() { return entriesReplicatedIn.get(); }
    public long getReplicationErrors() { return replicationErrors.get(); }
    public long getRejectedEntries() { return rejectedEntries.get(); }
}
