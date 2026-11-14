package com.nexus.banking.server.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime counters. Every counter counts individual messages / events, never batches. */
public class ServerMetrics {
    private final Instant startedAt = Instant.now();
    private final ThroughputRecorder recorder = new ThroughputRecorder();
    private final AtomicLong messagesStored = new AtomicLong();
    private final AtomicLong messagesReplicatedOut = new AtomicLong();
    private final AtomicLong messagesReplicatedIn = new AtomicLong();
    private final AtomicLong replicationErrors = new AtomicLong();
    private final AtomicLong rejectedMessages = new AtomicLong();

    public Instant getStartedAt() { return startedAt; }
    public long getUptimeSeconds() { return Duration.between(startedAt, Instant.now()).getSeconds(); }

    public ThroughputRecorder getRecorder() { return recorder; }
    public void incrementMessagesStored() { messagesStored.incrementAndGet(); recorder.stored(1); }
    public void addMessagesReplicatedOut(long n) { messagesReplicatedOut.addAndGet(n); recorder.replicatedOut((int) n); }
    public void addMessagesReplicatedIn(long n) { messagesReplicatedIn.addAndGet(n); recorder.replicatedIn((int) n); }
    public void incrementReplicationErrors() { replicationErrors.incrementAndGet(); }
    public void addRejectedMessages(long n) { rejectedMessages.addAndGet(n); }

    public long getMessagesStored() { return messagesStored.get(); }
    public long getMessagesReplicatedOut() { return messagesReplicatedOut.get(); }
    public long getMessagesReplicatedIn() { return messagesReplicatedIn.get(); }
    public long getReplicationErrors() { return replicationErrors.get(); }
    public long getRejectedMessages() { return rejectedMessages.get(); }
}
