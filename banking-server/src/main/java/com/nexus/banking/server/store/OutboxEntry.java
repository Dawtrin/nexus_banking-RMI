package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatMessageDTO;

public record OutboxEntry(long outboxId, int attemptCount, ChatMessageDTO message) {
}
