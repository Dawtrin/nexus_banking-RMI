package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.TransactionDTO;

public record OutboxEntry(long outboxId, int attemptCount, TransactionDTO entry) {
}
