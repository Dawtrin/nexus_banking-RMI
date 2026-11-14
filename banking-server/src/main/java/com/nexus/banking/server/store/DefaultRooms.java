package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatRoomDTO;

import java.time.Instant;
import java.util.List;

/** Rooms every node provides (rooms cannot be created through the client API). */
public final class DefaultRooms {
    private DefaultRooms() {
    }

    public static List<ChatRoomDTO> list() {
        Instant now = Instant.now();
        return List.of(
                new ChatRoomDTO("general", "General Chat", "Main discussion channel for all cluster members", now),
                new ChatRoomDTO("announcements", "Announcements", "System notices and broadcast updates", now),
                new ChatRoomDTO("random", "Random Talk", "Casual discussions and off-topic chat", now));
    }
}
