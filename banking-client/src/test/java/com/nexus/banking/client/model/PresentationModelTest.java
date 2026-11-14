package com.nexus.banking.client.model;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.enums.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PresentationModelTest {

    @Test
    void testChatMessageModel() {
        ChatMessageDTO dto = new ChatMessageDTO(
                UUID.randomUUID().toString(),
                "general",
                "usr-1",
                "Alice",
                "Test presentation message",
                Instant.now(),
                "server-a",
                1L,
                MessageType.CHAT
        );

        ChatMessageModel model = new ChatMessageModel(dto, true);
        assertThat(model.getMessageId()).isEqualTo(dto.getMessageId());
        assertThat(model.getContent()).isEqualTo("Test presentation message");
        assertThat(model.isOwnMessage()).isTrue();
        assertThat(model.getFormattedTime()).isNotEmpty();
    }

    @Test
    void messagesAreOrderedByCreatedAtThenOriginThenSequence() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        ChatMessageModel b1 = new ChatMessageModel(new ChatMessageDTO("m3", "general", "s", "n", "x", t, "server-b", 1, MessageType.CHAT, 3), false);
        ChatMessageModel a2 = new ChatMessageModel(new ChatMessageDTO("m1", "general", "s", "n", "x", t, "server-a", 2, MessageType.CHAT, 1), false);
        ChatMessageModel a1 = new ChatMessageModel(new ChatMessageDTO("m2", "general", "s", "n", "x", t, "server-a", 1, MessageType.CHAT, 2), false);
        ChatMessageModel older = new ChatMessageModel(new ChatMessageDTO("m4", "general", "s", "n", "x", t.minusSeconds(9), "server-z", 9, MessageType.CHAT, 4), false);
        java.util.List<ChatMessageModel> list = new java.util.ArrayList<>(java.util.List.of(b1, a2, older, a1));
        list.sort(ChatMessageModel.DISPLAY_ORDER);
        assertThat(list).extracting(ChatMessageModel::getMessageId).containsExactly("m4", "m2", "m1", "m3");
    }
}
