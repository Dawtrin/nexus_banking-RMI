package com.nexus.banking.server.store;

import java.time.Instant;

public record OutboxStats(long pending, long dead, Instant oldestPendingAt) {
}
