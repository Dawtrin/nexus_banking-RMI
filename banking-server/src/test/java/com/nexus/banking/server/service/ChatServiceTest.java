package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceTest {
    private Node node;

    @BeforeEach
    void setUp() {
        node = Node.ram("server-a", "server-b");
    }

    @AfterEach
    void tearDown() {
        node.close();
    }

    private String cmid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void registrationValidatesUsernameAndInstanceId() {
        assertThat(node.chat.registerClient("a", "instance-0001").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.registerClient("bad name!", "instance-0001").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.registerClient("alice", "x").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.registerClient("alice", "instance-0001").isSuccess()).isTrue();
    }

    @Test
    void everyProtectedCallRejectsUnknownSessions() {
        assertThat(node.chat.getRooms("nope").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.joinRoom("nope", "general").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.sendMessage("nope", "general", cmid(), "hi").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.getHistory("nope", "general", 10).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.getMessages("nope", "general", 0, 10).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.heartbeat("nope").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.getRooms(null).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
    }

    @Test
    void roomMembershipIsRequiredToSendAndRead() {
        ClientSessionDTO s = node.chat.registerClient("alice", "instance-0001").getData();
        String id = s.getSessionId();
        assertThat(node.chat.sendMessage(id, "general", cmid(), "hi").getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
        assertThat(node.chat.getHistory(id, "general", 10).getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
        assertThat(node.chat.getMessages(id, "general", 0, 10).getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
        assertThat(node.chat.joinRoom(id, "general").isSuccess()).isTrue();
        assertThat(node.chat.sendMessage(id, "general", cmid(), "hi").isSuccess()).isTrue();
        // membership is per room
        assertThat(node.chat.sendMessage(id, "random", cmid(), "hi").getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
    }

    @Test
    void unknownRoomIsRejected() {
        String id = node.login("alice");
        assertThat(node.chat.joinRoom(id, "ghost").getErrorCode()).isEqualTo(ErrorCodes.ROOM_NOT_FOUND);
        assertThat(node.chat.sendMessage(id, "ghost", cmid(), "hi").getErrorCode()).isEqualTo(ErrorCodes.ROOM_NOT_FOUND);
        assertThat(node.chat.joinRoom(id, "../etc").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    @Test
    void contentAndPaginationAreValidated() {
        String id = node.login("alice");
        assertThat(node.chat.sendMessage(id, "general", cmid(), "   ").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.sendMessage(id, "general", cmid(), "x".repeat(4001)).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.sendMessage(id, "general", cmid(), "bad\u0007bell").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.sendMessage(id, "general", "bad id with spaces", "hi").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.getHistory(id, "general", 0).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.getHistory(id, "general", 201).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(node.chat.getMessages(id, "general", -1, 10).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    @Test
    void retryWithSameClientMessageIdDoesNotDuplicate() {
        String id = node.login("alice");
        String key = cmid();
        OperationResult<ChatMessageDTO> first = node.chat.sendMessage(id, "general", key, "hello");
        OperationResult<ChatMessageDTO> retry = node.chat.sendMessage(id, "general", key, "hello");
        assertThat(first.isSuccess() && retry.isSuccess()).isTrue();
        assertThat(retry.getData().getMessageId()).isEqualTo(first.getData().getMessageId());
        MessagePage page = node.chat.getHistory(id, "general", 50).getData();
        assertThat(page.getMessages()).hasSize(1);
        assertThat(node.metrics.getMessagesStored()).isEqualTo(1);
    }

    @Test
    void sameUsernameFromTwoInstancesGetsDistinctSenderIds() {
        String a = node.login("alice", "instance-aaaa-0001", "general");
        String b = node.login("alice", "instance-bbbb-0002", "general");
        node.send(a, "one");
        node.send(b, "two");
        List<ChatMessageDTO> msgs = node.chat.getHistory(a, "general", 10).getData().getMessages();
        assertThat(msgs.get(0).getSenderId()).isNotEqualTo(msgs.get(1).getSenderId());
    }

    @Test
    void pollingByCursorReturnsOnlyNewMessagesAndNeverSkips() {
        String id = node.login("alice");
        node.send(id, "m1");
        MessagePage h = node.chat.getHistory(id, "general", 50).getData();
        assertThat(h.getMessages()).hasSize(1);
        long cursor = h.getNextCursor();
        assertThat(node.chat.getMessages(id, "general", cursor, 50).getData().getMessages()).isEmpty();
        node.send(id, "m2");
        node.send(id, "m3");
        MessagePage p = node.chat.getMessages(id, "general", cursor, 1).getData();
        assertThat(p.getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("m2");
        assertThat(p.hasMore()).isTrue();
        MessagePage p2 = node.chat.getMessages(id, "general", p.getNextCursor(), 10).getData();
        assertThat(p2.getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("m3");
    }

    @Test
    void roomsAreIsolated() {
        String id = node.login("alice");
        node.chat.joinRoom(id, "random");
        node.send(id, "in general");
        assertThat(node.chat.getHistory(id, "random", 10).getData().getMessages()).isEmpty();
    }

    @Test
    void displayOrderUsesCreatedAtThenOriginThenSequence() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        ChatMessageDTO a2 = new ChatMessageDTO("m1", "general", "s", "n", "x", t, "server-a", 2, MessageType.CHAT, 1);
        ChatMessageDTO a1 = new ChatMessageDTO("m2", "general", "s", "n", "x", t, "server-a", 1, MessageType.CHAT, 2);
        ChatMessageDTO b1 = new ChatMessageDTO("m3", "general", "s", "n", "x", t, "server-b", 1, MessageType.CHAT, 3);
        ChatMessageDTO older = new ChatMessageDTO("m4", "general", "s", "n", "x", t.minusSeconds(5), "server-z", 9, MessageType.CHAT, 4);
        List<ChatMessageDTO> list = new ArrayList<>(List.of(b1, a2, older, a1));
        list.sort(ChatMessageDTO.displayOrder());
        assertThat(list).extracting(ChatMessageDTO::getMessageId).containsExactly("m4", "m2", "m1", "m3");
    }

    @Test
    void disconnectedSessionCannotBeRevived() {
        String id = node.login("alice");
        node.chat.disconnectClient(id);
        assertThat(node.chat.heartbeat(id).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.chat.getMessages(id, "general", 0, 10).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(node.sessions.activeCount()).isZero();
    }

    @Test
    void sessionsGoStaleThenDisconnectAndStaleOnesRecoverOnUse() {
        String id = node.login("alice");
        node.sessions.backdate(id, Instant.now().minusSeconds(40));
        node.sessions.reap(Instant.now());
        assertThat(node.sessions.listLive()).extracting(s -> s.getStatus().name()).containsExactly("STALE");
        assertThat(node.chat.heartbeat(id).isSuccess()).isTrue(); // alive again
        assertThat(node.sessions.listLive()).extracting(s -> s.getStatus().name()).containsExactly("ACTIVE");
        node.sessions.backdate(id, Instant.now().minusSeconds(120));
        node.sessions.reap(Instant.now());
        assertThat(node.chat.heartbeat(id).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
    }

    @Test
    void messageNoticeIsHonestAboutRamOnlyMode() {
        String id = node.login("alice");
        OperationResult<ChatMessageDTO> r = node.chat.sendMessage(id, "general", cmid(), "hi");
        assertThat(r.getMessage()).contains("NOT durable");
        assertThat(node.status.build().getPersistenceMode()).isEqualTo("RAM_ONLY_DEMO");
    }

    @Test
    void adminApiRequiresTheConfiguredToken() {
        assertThat(node.monitoring.getServerStatus(null).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getServerStatus("wrong-token-000000").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getActiveSessions("wrong").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getRecentEvents("wrong", 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getDatabaseHealth("wrong").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getReplicationStatus("wrong").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.admin.requeueDeadLetters("wrong", "op").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(node.monitoring.getServerStatus(Node.ADMIN_TOKEN).isSuccess()).isTrue();
    }

    @Test
    void statusReflectsRealStateNotConstants() {
        node.login("alice");
        var st = node.monitoring.getServerStatus(Node.ADMIN_TOKEN).getData();
        assertThat(st.getServerId()).isEqualTo("server-a");
        assertThat(st.getActiveSessionCount()).isEqualTo(1);
        assertThat(st.getPeerStatus().name()).isEqualTo("UNKNOWN");   // never contacted
        assertThat(st.getDatabaseStatus().name()).isEqualTo("UNKNOWN"); // database disabled
    }

    @Test
    void editMessageUpdatesContentAndAppendsTag() {
        String aliceSession = node.login("alice");
        String bobSession = node.login("bob");
        node.chat.joinRoom(aliceSession, "general");
        node.chat.joinRoom(bobSession, "general");

        // Alice sends message
        var sendRes = node.chat.sendMessage(aliceSession, "general", cmid(), "Hello world");
        assertThat(sendRes.isSuccess()).isTrue();
        String messageId = sendRes.getData().getMessageId();

        // Bob cannot edit Alice's message
        var bobEdit = node.chat.editMessage(bobSession, messageId, "Hacked by Bob");
        assertThat(bobEdit.isSuccess()).isFalse();

        // Alice cannot edit with blank content
        var blankEdit = node.chat.editMessage(aliceSession, messageId, "   ");
        assertThat(blankEdit.getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);

        // Alice edits her own message
        var editRes = node.chat.editMessage(aliceSession, messageId, "Hello updated world");
        assertThat(editRes.isSuccess()).isTrue();
        assertThat(editRes.getData().getContent()).isEqualTo("Hello updated world (edited)");

        // Non-existent message edit fails
        var notFoundEdit = node.chat.editMessage(aliceSession, UUID.randomUUID().toString(), "Nope");
        assertThat(notFoundEdit.getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    @Test
    void duplicateLoginEvictsPreviousSession() {
        Node singleNode = Node.ram("server-single", "server-b", Map.of("server.session.single", "true"));
        try {
            var reg1 = singleNode.chat.registerClient("alice", "device-0001");
            assertThat(reg1.isSuccess()).isTrue();
            String session1 = reg1.getData().getSessionId();
            assertThat(singleNode.chat.heartbeat(session1).isSuccess()).isTrue();

            var reg2 = singleNode.chat.registerClient("alice", "device-0002");
            assertThat(reg2.isSuccess()).isTrue();
            String session2 = reg2.getData().getSessionId();

            assertThat(singleNode.chat.heartbeat(session2).isSuccess()).isTrue();

            var hb1 = singleNode.chat.heartbeat(session1);
            assertThat(hb1.getErrorCode()).isEqualTo(ErrorCodes.SESSION_EVICTED);
            assertThat(hb1.getMessage()).contains("Tài khoản của bạn đã được đăng nhập từ một thiết bị khác");
        } finally {
            singleNode.close();
        }
    }
}
