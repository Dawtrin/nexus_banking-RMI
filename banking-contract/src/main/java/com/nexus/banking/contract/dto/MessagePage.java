package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bounded slice of room messages.
 *
 * <p>{@code nextCursor} is a node-local ingestion cursor (the largest {@code localSequence} the
 * server has handed out for this room). Clients pass it back to {@code getMessages} to receive only
 * newer ingestions; it is independent of display ordering (createdAt + tie-breaker), so late
 * replicated messages with older timestamps are never skipped.</p>
 */
public class MessagePage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final List<ChatMessageDTO> messages;
    private final long nextCursor;
    private final boolean hasMore;

    public MessagePage(List<ChatMessageDTO> messages, long nextCursor, boolean hasMore) {
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public List<ChatMessageDTO> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public long getNextCursor() {
        return nextCursor;
    }

    /** For {@code getMessages}: more newer messages are available now. For {@code getHistory}: older messages exist. */
    public boolean hasMore() {
        return hasMore;
    }
}
