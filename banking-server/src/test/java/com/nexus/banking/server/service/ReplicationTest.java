package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.contract.remote.ReplicationRemote;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationTest {
    private Node a;
    private Node b;

    @BeforeEach
    void setUp() throws Exception {
        a = Node.ram("server-a", "server-b");
        b = Node.ram("server-b", "server-a");
    }

    @AfterEach
    void tearDown() throws Exception {
        a.close();
        b.close();
    }

    private List<ChatMessageDTO> history(Node n, String user) {
        String s = n.login(user + "-reader", "instance-" + user + "-9999", "general");
        return n.chat.getHistory(s, "general", 200).getData().getMessages();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void messagesFlowBothWaysWithoutLooping() throws Exception {
        a.link.set(b.repl);
        b.link.set(a.repl);
        a.send(a.login("alice"), "from A");
        b.send(b.login("bob"), "from B");
        a.replication.runOnce();
        b.replication.runOnce();
        a.replication.runOnce();
        assertThat(history(a, "x")).extracting(ChatMessageDTO::getContent).containsExactlyInAnyOrder("from A", "from B");
        assertThat(history(b, "y")).extracting(ChatMessageDTO::getContent).containsExactlyInAnyOrder("from A", "from B");
        // nothing is bounced back: no new pending entries on either node
        assertThat(a.replication.getStatus().getPendingCount()).isZero();
        assertThat(b.replication.getStatus().getPendingCount()).isZero();
    }

    @Test
    void duplicateDeliveryIsIdempotent() throws Exception {
        a.send(a.login("alice"), "once");
        List<ChatMessageDTO> batch = a.store.findByOriginAfter("server-a", 0, 500);
        // two deliveries of the same batch
        OperationResult<ReplicationAck> r1 = b.repl.replicateBatch("server-a", Node.SECRET, batch);
        OperationResult<ReplicationAck> r2 = b.repl.replicateBatch("server-a", Node.SECRET, batch);
        assertThat(r1.getData().getAcceptedMessageIds()).hasSize(1);
        assertThat(r2.getData().getAcceptedMessageIds()).hasSize(1);
        assertThat(history(b, "z")).hasSize(1);
    }

    @Test
    void unauthenticatedOrSpoofedCallersAreRejected() throws Exception {
        a.send(a.login("alice"), "secret stuff");
        List<ChatMessageDTO> batch = a.store.findByOriginAfter("server-a", 0, 500);
        assertThat(b.repl.replicateBatch("server-a", "wrong-secret-wrong-secret", batch).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(b.repl.replicateBatch("intruder", Node.SECRET, batch).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(b.repl.getMessagesAfter("server-a", null, 0, 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(b.repl.getPeerStatus("server-a", "nope").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(history(b, "q")).isEmpty();
    }

    @Test
    void messagesWithForeignOrOwnOriginAreRejectedPerMessage() throws Exception {
        Instant t = Instant.now();
        ChatMessageDTO claimsReceiver = new ChatMessageDTO("id-1", "general", "s", "n", "x", t, "server-b", 1, MessageType.CHAT, 0);
        ChatMessageDTO claimsThird = new ChatMessageDTO("id-2", "general", "s", "n", "x", t, "server-z", 1, MessageType.CHAT, 0);
        ChatMessageDTO good = new ChatMessageDTO("id-3", "general", "s", "n", "ok", t, "server-a", 1, MessageType.CHAT, 0);
        ReplicationAck ack = b.repl.replicateBatch("server-a", Node.SECRET, List.of(claimsReceiver, claimsThird, good)).getData();
        assertThat(ack.getAcceptedMessageIds()).containsExactly("id-3");
        assertThat(ack.getRejected()).containsKeys("id-1", "id-2");
    }

    @Test
    void conflictingOriginSequenceIsRejectedNotOverwritten() throws Exception {
        Instant t = Instant.now();
        ChatMessageDTO first = new ChatMessageDTO("id-1", "general", "s", "n", "first", t, "server-a", 1, MessageType.CHAT, 0);
        ChatMessageDTO other = new ChatMessageDTO("id-2", "general", "s", "n", "other", t, "server-a", 1, MessageType.CHAT, 0);
        b.repl.replicateBatch("server-a", Node.SECRET, List.of(first));
        ReplicationAck ack = b.repl.replicateBatch("server-a", Node.SECRET, List.of(other)).getData();
        assertThat(ack.getAcceptedMessageIds()).isEmpty();
        assertThat(ack.getRejected()).containsKey("id-2");
        assertThat(history(b, "r")).extracting(ChatMessageDTO::getContent).containsExactly("first");
    }

    @Test
    void peerOfflineKeepsMessagesPendingAndRecoveryDeliversThem() throws Exception {
        String alice = a.login("alice");
        a.send(alice, "m1");
        a.send(alice, "m2");
        a.replication.runOnce(); // link is null => peer unreachable
        ReplicationStatusDTO s = a.replication.getStatus();
        assertThat(s.getPeerStatus()).isEqualTo(ServerStatus.UNREACHABLE);
        assertThat(s.getPendingCount()).isEqualTo(2);
        assertThat(s.getLastError()).contains("peer offline");
        a.link.set(b.repl);
        sleep(150); // backoff window
        a.replication.runOnce();
        assertThat(a.replication.getStatus().getPendingCount()).isZero();
        assertThat(a.replication.getStatus().getPeerStatus()).isEqualTo(ServerStatus.ONLINE);
        assertThat(history(b, "w")).extracting(ChatMessageDTO::getContent).containsExactly("m1", "m2");
    }

    @Test
    void catchUpPullsMoreThanOneBatchAndIsPagedUntilComplete() throws Exception {
        String alice = a.login("alice");
        for (int i = 1; i <= 250; i++) a.send(alice, "msg " + i);
        // A cannot push (link null); B pulls through catch-up with page size 100 => 3 pages
        b.link.set(a.repl);
        b.replication.runOnce();
        assertThat(b.store.getInboundCheckpoint("server-a")).isEqualTo(250);
        assertThat(history(b, "p")).hasSize(200); // history is capped at 200 per page by contract
        assertThat(b.chat.getHistory(b.login("carol", "instance-cccc-0003", "general"), "general", 200).getData().hasMore()).isTrue();
    }

    @Test
    void failedCatchUpIsRetriedWithoutAnyDisconnectTransition() throws Exception {
        String alice = a.login("alice");
        for (int i = 1; i <= 5; i++) a.send(alice, "m" + i);
        AtomicInteger calls = new AtomicInteger();
        b.link.set(new ReplicationRemote() {
            @Override
            public OperationResult<ReplicationAck> replicateBatch(String s, String sec, List<ChatMessageDTO> m) {
                return a.repl.replicateBatch(s, sec, m);
            }

            @Override
            public OperationResult<List<ChatMessageDTO>> getMessagesAfter(String r, String sec, long after, int limit) throws java.rmi.RemoteException {
                if (calls.incrementAndGet() == 1) throw new java.rmi.RemoteException("boom during catch-up");
                return a.repl.getMessagesAfter(r, sec, after, limit);
            }

            @Override
            public OperationResult<ServerStatusDTO> getPeerStatus(String r, String sec) {
                return a.repl.getPeerStatus(r, sec);
            }
        });
        b.replication.runOnce(); // ping ok, catch-up throws
        assertThat(b.store.getInboundCheckpoint("server-a")).isZero();
        sleep(150);
        b.replication.runOnce(); // must retry on its own
        assertThat(b.store.getInboundCheckpoint("server-a")).isEqualTo(5);
    }

    @Test
    void poisonMessageDoesNotBlockOthersAndEndsDeadLetteredThenRequeueable() throws Exception {
        String alice = a.login("alice");
        a.send(alice, "good-1");
        a.send(alice, "poison");
        a.send(alice, "good-2");
        String poisonId = a.store.findByOriginAfter("server-a", 0, 10).get(1).getMessageId();
        a.link.set(new ReplicationRemote() {
            @Override
            public OperationResult<ReplicationAck> replicateBatch(String s, String sec, List<ChatMessageDTO> msgs) {
                List<ChatMessageDTO> ok = new ArrayList<>();
                Map<String, String> rej = new LinkedHashMap<>();
                for (ChatMessageDTO m : msgs) {
                    if (m.getMessageId().equals(poisonId)) rej.put(m.getMessageId(), "synthetic poison");
                    else ok.add(m);
                }
                ReplicationAck real = b.repl.replicateBatch(s, sec, ok).getData();
                return OperationResult.success(new ReplicationAck(real.getAcceptedMessageIds(), rej));
            }

            @Override
            public OperationResult<List<ChatMessageDTO>> getMessagesAfter(String r, String sec, long after, int limit) {
                return b.repl.getMessagesAfter(r, sec, after, limit);
            }

            @Override
            public OperationResult<ServerStatusDTO> getPeerStatus(String r, String sec) {
                return b.repl.getPeerStatus(r, sec);
            }
        });
        for (int i = 0; i < 6; i++) {
            a.replication.runOnce();
            sleep(120);
        }
        assertThat(history(b, "v")).extracting(ChatMessageDTO::getContent).containsExactlyInAnyOrder("good-1", "good-2");
        ReplicationStatusDTO s = a.replication.getStatus();
        assertThat(s.getDeadLetterCount()).isEqualTo(1);   // visible, not silently dropped
        assertThat(s.getPendingCount()).isZero();
        assertThat(a.admin.requeueDeadLetters(Node.ADMIN_TOKEN, "tester").getData()).isEqualTo(1);
        assertThat(a.replication.getStatus().getPendingCount()).isEqualTo(1);
        assertThat(a.replication.getStatus().getDeadLetterCount()).isZero();
    }

    @Test
    void ackOnlyListsMessagesTheReceiverActuallyHolds() throws Exception {
        String alice = a.login("alice");
        a.send(alice, "m1");
        ChatMessageDTO m = a.store.findByOriginAfter("server-a", 0, 500).get(0);
        ChatMessageDTO badRoom = new ChatMessageDTO("id-x", "bad room!", "s", "n", "x", Instant.now(), "server-a", 2, MessageType.CHAT, 0);
        ReplicationAck ack = b.repl.replicateBatch("server-a", Node.SECRET, List.of(m, badRoom)).getData();
        assertThat(ack.getAcceptedMessageIds()).containsExactly(m.getMessageId());
        assertThat(ack.getRejected()).containsKey("id-x");
        assertThat(history(b, "u")).hasSize(1);
    }

    @Test
    void metricsCountMessagesNotBatches() throws Exception {
        a.link.set(b.repl);
        String alice = a.login("alice");
        for (int i = 0; i < 7; i++) a.send(alice, "m" + i);
        a.replication.runOnce();
        assertThat(a.metrics.getMessagesReplicatedOut()).isEqualTo(7);
        assertThat(b.metrics.getMessagesReplicatedIn()).isEqualTo(7);
    }

    @Test
    void crossNodeLoginEvictsSessionOnPeerNode() throws Exception {
        Node nodeA = Node.ram("server-a", "server-b", Map.of("server.session.single", "true"));
        Node nodeB = Node.ram("server-b", "server-a", Map.of("server.session.single", "true"));
        try {
            nodeB.link.set(nodeA.repl);
            nodeA.link.set(nodeB.repl);

            String aliceA = nodeA.login("alice", "instance-a-001", "general");
            assertThat(nodeA.chat.heartbeat(aliceA).isSuccess()).isTrue();

            String aliceB = nodeB.login("alice", "instance-b-001", "general");
            assertThat(nodeB.chat.heartbeat(aliceB).isSuccess()).isTrue();

            sleep(150);

            var hbA = nodeA.chat.heartbeat(aliceA);
            assertThat(hbA.getErrorCode()).isEqualTo(ErrorCodes.SESSION_EVICTED);
            assertThat(hbA.getMessage()).contains("Tài khoản của bạn đã được đăng nhập");
        } finally {
            nodeA.close();
            nodeB.close();
        }
    }
}
