package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.PresenceStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** Profile of a username on one node (username-only demo identity; email is optional and never used for login). */
public class UserProfileDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String displayName;
    private final String email;
    private final PresenceStatus status;
    private final Instant lastSeenAt;
    private final String avatar;

    public UserProfileDTO(String username, String displayName, String email, PresenceStatus status, Instant lastSeenAt) {
        this(username, displayName, email, status, lastSeenAt, "");
    }

    public UserProfileDTO(String username, String displayName, String email, PresenceStatus status, Instant lastSeenAt, String avatar) {
        this.username = username;
        this.displayName = displayName;
        this.email = email == null ? "" : email;
        this.status = status == null ? PresenceStatus.ONLINE : status;
        this.lastSeenAt = lastSeenAt;
        this.avatar = avatar == null ? "" : avatar;
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public PresenceStatus getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getAvatar() { return avatar == null ? "" : avatar; }
}
