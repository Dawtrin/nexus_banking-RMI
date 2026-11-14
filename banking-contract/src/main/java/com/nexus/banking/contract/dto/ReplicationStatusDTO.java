package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.PeerSyncStatus;
import com.nexus.banking.contract.enums.ServerStatus;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** Admin view of replication toward / from the configured peer. All values come from live state. */
public class ReplicationStatusDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String localServerId;
    private final String peerServerId;
    private final String peerEndpoint;
    private final ServerStatus peerStatus;
    private final PeerSyncStatus syncStatus;
    private final long pendingCount;
    private final long deadLetterCount;
    private final Instant oldestPendingAt;
    private final Instant lastSuccessAt;
    private final Instant lastCheckedAt;
    private final String lastError;
    private final long inboundCheckpoint;
    private final int consecutiveFailures;

    public ReplicationStatusDTO(String localServerId, String peerServerId, String peerEndpoint,
                                ServerStatus peerStatus, PeerSyncStatus syncStatus,
                                long pendingCount, long deadLetterCount, Instant oldestPendingAt,
                                Instant lastSuccessAt, Instant lastCheckedAt, String lastError,
                                long inboundCheckpoint, int consecutiveFailures) {
        this.localServerId = localServerId;
        this.peerServerId = peerServerId;
        this.peerEndpoint = peerEndpoint;
        this.peerStatus = peerStatus == null ? ServerStatus.UNKNOWN : peerStatus;
        this.syncStatus = syncStatus == null ? PeerSyncStatus.UNKNOWN : syncStatus;
        this.pendingCount = pendingCount;
        this.deadLetterCount = deadLetterCount;
        this.oldestPendingAt = oldestPendingAt;
        this.lastSuccessAt = lastSuccessAt;
        this.lastCheckedAt = lastCheckedAt;
        this.lastError = lastError;
        this.inboundCheckpoint = inboundCheckpoint;
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getLocalServerId() { return localServerId; }
    public String getPeerServerId() { return peerServerId; }
    public String getPeerEndpoint() { return peerEndpoint; }
    public ServerStatus getPeerStatus() { return peerStatus; }
    public PeerSyncStatus getSyncStatus() { return syncStatus; }
    public long getPendingCount() { return pendingCount; }
    public long getDeadLetterCount() { return deadLetterCount; }
    public Instant getOldestPendingAt() { return oldestPendingAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public String getLastError() { return lastError; }
    /** Highest origin sequence of the peer's messages this node has durably stored. */
    public long getInboundCheckpoint() { return inboundCheckpoint; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
}
