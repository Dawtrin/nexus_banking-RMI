package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.PresenceStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** A user currently joined to a room on the answering node. */
public class MemberDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String displayName;
    private final PresenceStatus status;
    private final String serverId;
    private final Instant connectedAt;
    private final String avatar;

    public MemberDTO(String username, String displayName, PresenceStatus status, String serverId, Instant connectedAt) {
        this(username, displayName, status, serverId, connectedAt, "");
    }

    public MemberDTO(String username, String displayName, PresenceStatus status, String serverId, Instant connectedAt, String avatar) {
        this.username = username;
        this.displayName = displayName;
        this.status = status;
        this.serverId = serverId;
        this.connectedAt = connectedAt;
        this.avatar = avatar == null ? "" : avatar;
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public PresenceStatus getStatus() { return status; }
    public String getServerId() { return serverId; }
    public Instant getConnectedAt() { return connectedAt; }
    public String getAvatar() { return avatar == null ? "" : avatar; }
}
