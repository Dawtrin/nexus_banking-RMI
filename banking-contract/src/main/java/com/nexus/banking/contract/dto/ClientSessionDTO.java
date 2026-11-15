package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.SessionStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Server-tracked customer session (one per account, enforced cluster-wide) on one branch node. */
public class ClientSessionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    private final String sessionId;
    private final String accountNumber;
    private final String ownerName;
    private final String clientInstanceId;
    private final String remoteIp;
    private final Instant connectedAt;
    private final Instant lastSeenAt;
    private final SessionStatus status;
    private final String connectedServerId;
    private final boolean pushEnabled;

    public ClientSessionDTO(String sessionId, String accountNumber, String ownerName, String clientInstanceId,
                            String remoteIp, Instant connectedAt, Instant lastSeenAt, SessionStatus status,
                            String connectedServerId, boolean pushEnabled) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.ownerName = ownerName == null ? "" : ownerName;
        this.clientInstanceId = clientInstanceId == null ? "" : clientInstanceId;
        this.remoteIp = remoteIp == null ? "UNKNOWN" : remoteIp;
        this.connectedAt = connectedAt == null ? Instant.now() : connectedAt;
        this.lastSeenAt = lastSeenAt == null ? Instant.now() : lastSeenAt;
        this.status = status == null ? SessionStatus.ACTIVE : status;
        this.connectedServerId = connectedServerId == null ? "UNKNOWN" : connectedServerId;
        this.pushEnabled = pushEnabled;
    }

    public String getSessionId() { return sessionId; }
    public String getAccountNumber() { return accountNumber; }
    public String getOwnerName() { return ownerName; }
    public String getClientInstanceId() { return clientInstanceId; }
    public String getRemoteIp() { return remoteIp; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public SessionStatus getStatus() { return status; }
    public String getConnectedServerId() { return connectedServerId; }
    /** True when the device registered an RMI callback and receives balance pushes. */
    public boolean isPushEnabled() { return pushEnabled; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ClientSessionDTO s && sessionId.equals(s.sessionId));
    }

    @Override
    public int hashCode() { return sessionId.hashCode(); }

    @Override
    public String toString() {
        return "ClientSessionDTO{" + sessionId + ", account=" + accountNumber + ", ip=" + remoteIp + ", " + status
                + ", server=" + connectedServerId + "}";
    }
}
