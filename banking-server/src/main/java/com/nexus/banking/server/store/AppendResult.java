package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatMessageDTO;

/** Result of appending a locally originated message; {@code duplicate} means an earlier identical request won. */
public record AppendResult(ChatMessageDTO message, boolean duplicate) {
}
