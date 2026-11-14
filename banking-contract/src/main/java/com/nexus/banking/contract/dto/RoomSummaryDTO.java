package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** A room plus the number of distinct users currently joined to it on the answering node. */
public class RoomSummaryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String roomId;
    private final String roomName;
    private final String description;
    private final int memberCount;
    private final long messageCount;
    private final Instant createdAt;

    public RoomSummaryDTO(String roomId, String roomName, String description, int memberCount, long messageCount, Instant createdAt) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.description = description;
        this.memberCount = memberCount;
        this.messageCount = messageCount;
        this.createdAt = createdAt;
    }

    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getDescription() { return description; }
    public int getMemberCount() { return memberCount; }
    /** -1 when unknown (not asked for by the caller's role). */
    public long getMessageCount() { return messageCount; }
    public Instant getCreatedAt() { return createdAt; }
}
