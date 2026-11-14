package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.PresenceStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** Administrative view of a user: no credentials exist, and the email is deliberately not exposed. */
public class UserAdminDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String displayName;
    private final PresenceStatus status;
    private final int liveSessions;
    private final Instant lastSeenAt;

    public UserAdminDTO(String username, String displayName, PresenceStatus status, int liveSessions, Instant lastSeenAt) {
        this.username = username;
        this.displayName = displayName;
        this.status = status;
        this.liveSessions = liveSessions;
        this.lastSeenAt = lastSeenAt;
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public PresenceStatus getStatus() { return status; }
    public int getLiveSessions() { return liveSessions; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public boolean isOnline() { return liveSessions > 0; }
}
