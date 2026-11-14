package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.server.support.Node;
import com.nexus.banking.server.support.TcpProxy;
import com.nexus.banking.server.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-database tests. SKIPPED (not passed) unless NEXUS_TEST_DB_HOST is set; see TestDb. */
class JdbcStoreDbTest {
    private static final String DB = "nexus_it_store";
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(TestDb.available(), "NEXUS_TEST_DB_HOST not set - database tests skipped");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        toClose.clear();
    }

    private Node node(com.nexus.banking.server.config.DatabaseConfig cfg) {
        Node n = Node.jdbc("server-a", "server-b", cfg);
        toClose.add(n);
        return n;
    }

    private long count(String sql) throws Exception {
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private AppendResult append(Node n, String text) throws Exception {
        return n.store.appendLocal("server-a", "server-b", "general", "alice#instance-0001", "alice", UUID.randomUUID().toString(), text);
    }

    @Test
    void restartDoesNotReuseOriginSequencesAndKeepsEveryMessage() throws Exception {
        Node first = node(TestDb.fresh(DB));
        assertThat(append(first, "before-1").message().getOriginSequence()).isEqualTo(1);
        assertThat(append(first, "before-2").message().getOriginSequence()).isEqualTo(2);
        first.close();

        Node second = node(TestDb.reopen(DB));          // simulated process restart
        AppendResult r = append(second, "after-restart");
        assertThat(r.message().getOriginSequence()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM replication_outbox WHERE status = 'PENDING'")).isEqualTo(3);
        assertThat(second.store.latest("general", 50).getMessages()).extracting(ChatMessageDTO::getContent)
                .containsExactly("before-1", "before-2", "after-restart");
    }

    @Test
    void failedOutboxInsertRollsBackTheMessageAndTheSequence() throws Exception {
        Node n = node(TestDb.fresh(DB));
        append(n, "ok-1");
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER trg_fail BEFORE INSERT ON replication_outbox FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'simulated outbox failure'");
        }
        assertThatThrownBy(() -> append(n, "doomed")).isInstanceOf(StorageException.class).hasMessageContaining("rolled back");
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(1);        // message not committed
        assertThat(count("SELECT last_seq FROM origin_sequences WHERE origin_server_id = 'server-a'")).isEqualTo(1); // no gap
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER trg_fail");
        }
        assertThat(append(n, "ok-2").message().getOriginSequence()).isEqualTo(2);
    }

    @Test
    void chatServiceReportsStorageFailureInsteadOfFalseSuccess() throws Exception {
        Node n = node(TestDb.fresh(DB));
        String s = n.login("alice");
        assertThat(n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "fine").isSuccess()).isTrue();
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER trg_fail2 BEFORE INSERT ON chat_messages FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'simulated disk failure'");
        }
        OperationResult<ChatMessageDTO> r = n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "lost?");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo(ErrorCodes.STORAGE_UNAVAILABLE);
        assertThat(r.getMessage()).doesNotContain("Stored durably");
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(1);
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER trg_fail2");
        }
    }

    @Test
    void unreachableDatabaseIsReportedHonestly() {
        Node n = node(TestDb.unreachable());
        String s = n.chat.registerClient("alice", "instance-alice-0001").getData().getSessionId();
        OperationResult<ChatMessageDTO> r = n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "hi");
        assertThat(r.getErrorCode()).isEqualTo(ErrorCodes.STORAGE_UNAVAILABLE);
        assertThat(n.db.isConnected()).isFalse();
        assertThat(n.status.build().getPersistenceMode()).isEqualTo("DB_UNAVAILABLE");
        assertThat(n.status.build().getServerStatus().name()).isEqualTo("DEGRADED");
        assertThat(n.chat.getRooms(s).getErrorCode()).isEqualTo(ErrorCodes.STORAGE_UNAVAILABLE);
    }

    @Test
    void databaseOutageAndRecoveryNeedNoRestart() throws Exception {
        TestDb.fresh(DB);
        int proxyPort = 33306;
        TcpProxy proxy = new TcpProxy(proxyPort, "127.0.0.1", TestDb.realPort());
        toClose.add(proxy);
        Node n = node(TestDb.viaPort(DB, proxyPort));       // database "down" at start-up
        String s = n.chat.registerClient("alice", "instance-alice-0001").getData().getSessionId();
        assertThat(n.chat.joinRoom(s, "general").getErrorCode()).isEqualTo(ErrorCodes.STORAGE_UNAVAILABLE);

        proxy.start();                                       // database comes up
        OperationResult<Void> joined = n.chat.joinRoom(s, "general");
        assertThat(joined.isSuccess()).as(joined.toString()).isTrue();
        OperationResult<ChatMessageDTO> ok = n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "after up");
        assertThat(ok.isSuccess()).as(ok.toString()).isTrue();
        assertThat(n.status.build().getPersistenceMode()).isEqualTo("MYSQL_DURABLE");

        proxy.stop();                                        // outage while running
        OperationResult<ChatMessageDTO> down = n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "during outage");
        assertThat(down.getErrorCode()).isEqualTo(ErrorCodes.STORAGE_UNAVAILABLE);
        assertThat(n.status.build().getPersistenceMode()).isEqualTo("DB_UNAVAILABLE");

        proxy.start();                                       // recovery
        Thread.sleep(800);
        OperationResult<ChatMessageDTO> back = n.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "recovered");
        assertThat(back.isSuccess()).as(back.toString()).isTrue();
        assertThat(n.status.build().getPersistenceMode()).isEqualTo("MYSQL_DURABLE");
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(2); // only what was really stored
    }

    @Test
    void clientRetryAcrossRestartDoesNotDuplicate() throws Exception {
        Node first = node(TestDb.fresh(DB));
        String key = UUID.randomUUID().toString();
        String id1 = first.store.appendLocal("server-a", "server-b", "general", "alice#i", "alice", key, "hello").message().getMessageId();
        first.close();
        Node second = node(TestDb.reopen(DB));
        AppendResult retry = second.store.appendLocal("server-a", "server-b", "general", "alice#i", "alice", key, "hello");
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.message().getMessageId()).isEqualTo(id1);
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(1);
    }

    @Test
    void inboundCheckpointSurvivesRestart() throws Exception {
        Node first = node(TestDb.fresh(DB));
        List<ChatMessageDTO> batch = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            batch.add(new ChatMessageDTO(UUID.randomUUID().toString(), "general", "bob#i", "bob", "b" + i, Instant.now(), "server-b", i, MessageType.CHAT, 0));
        }
        IngestResult r = first.store.ingestReplicated("server-b", batch);
        assertThat(r.accepted()).hasSize(3);
        assertThat(r.checkpoint()).isEqualTo(3);
        first.close();
        Node second = node(TestDb.reopen(DB));
        assertThat(second.store.getInboundCheckpoint("server-b")).isEqualTo(3);
        assertThat(count("SELECT last_replicated_origin_seq FROM replication_state WHERE peer_server_id = 'server-b'")).isEqualTo(3);
        // replaying the same batch changes nothing
        assertThat(second.store.ingestReplicated("server-b", batch).accepted()).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(3);
    }

    @Test
    void lateReplicatedMessageWithOlderTimestampIsNotSkippedByTheCursor() throws Exception {
        Node n = node(TestDb.fresh(DB));
        append(n, "local-now");
        MessagePage first = n.store.latest("general", 50);
        long cursor = first.getNextCursor();
        ChatMessageDTO late = new ChatMessageDTO(UUID.randomUUID().toString(), "general", "bob#i", "bob", "late-but-older",
                Instant.now().minusSeconds(3600), "server-b", 1, MessageType.CHAT, 0);
        n.store.ingestReplicated("server-b", List.of(late));
        MessagePage poll = n.store.after("general", cursor, 50);
        assertThat(poll.getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("late-but-older");
        List<ChatMessageDTO> all = new ArrayList<>(n.store.latest("general", 50).getMessages());
        assertThat(all).extracting(ChatMessageDTO::getContent).containsExactly("late-but-older", "local-now"); // display order
    }

    @Test
    void identicalTimestampsAreOrderedDeterministically() throws Exception {
        Node n = node(TestDb.fresh(DB));
        Instant t = Instant.parse("2026-05-05T10:00:00.123Z");
        List<ChatMessageDTO> batch = new ArrayList<>();
        batch.add(new ChatMessageDTO("id-b2", "general", "s", "n", "b2", t, "server-b", 2, MessageType.CHAT, 0));
        batch.add(new ChatMessageDTO("id-b1", "general", "s", "n", "b1", t, "server-b", 1, MessageType.CHAT, 0));
        batch.add(new ChatMessageDTO("id-c1", "general", "s", "n", "c1", t, "server-c", 1, MessageType.CHAT, 0));
        n.store.ingestReplicated("server-b", batch);
        assertThat(n.store.latest("general", 50).getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("b1", "b2", "c1");
        MessagePage onlyLast = n.store.latest("general", 2);
        assertThat(onlyLast.getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("b2", "c1");
        assertThat(onlyLast.hasMore()).isTrue();
    }

    @Test
    void concurrentAppendsProduceContiguousSequencesAndNoLostMessages() throws Exception {
        Node n = node(TestDb.fresh(DB));
        int threads = 8, per = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tid = t;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < per; i++) append(n, "t" + tid + "-" + i);
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        int total = threads * per;
        assertThat(count("SELECT COUNT(*) FROM chat_messages")).isEqualTo(total);
        assertThat(count("SELECT MAX(origin_sequence) FROM chat_messages")).isEqualTo(total);
        assertThat(count("SELECT COUNT(DISTINCT origin_sequence) FROM chat_messages")).isEqualTo(total);
        Set<Long> seen = new HashSet<>();
        long cursor = 0;
        MessagePage p;
        do {
            p = n.store.after("general", cursor, 100);
            p.getMessages().forEach(m -> seen.add(m.getLocalSequence()));
            cursor = p.getNextCursor();
        } while (p.hasMore());
        assertThat(seen).hasSize(total);
    }

    @Test
    void legacyV1SchemaIsRefusedWithAClearError() throws Exception {
        TestDb.fresh(DB);
        try (Connection c = TestDb.admin(DB); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE chat_messages (message_id VARCHAR(64) PRIMARY KEY, room_id VARCHAR(100))");
        }
        Node n = node(TestDb.reopen(DB));
        assertThat(n.db.isConnected()).isFalse();
        assertThat(n.db.getLastError()).contains("Legacy v1 schema");
    }

    @Test
    void profilesRoomsAndAuditSurviveARestart() throws Exception {
        Node first = node(TestDb.fresh(DB));
        String s = first.login("alice");
        assertThat(first.chat.updateProfile(s, "Alice Anderson", "alice@example.com", "BUSY").isSuccess()).isTrue();
        assertThat(first.admin.createRoom(Node.ADMIN_TOKEN, "op1", "dev", "Development", "Dev talk").isSuccess()).isTrue();
        first.chat.joinRoom(s, "dev");
        first.chat.sendMessage(s, "dev", UUID.randomUUID().toString(), "hello dev");
        assertThat(first.admin.deleteRoom(Node.ADMIN_TOKEN, "op1", "dev").isSuccess()).isFalse();   // not empty, recorded
        first.close();

        Node second = node(TestDb.reopen(DB));
        String again = second.login("alice", "instance-zzzz-0009", "general");
        assertThat(second.chat.getProfile(again).getData().getDisplayName()).isEqualTo("Alice Anderson");
        assertThat(second.chat.getProfile(again).getData().getStatus().name()).isEqualTo("BUSY");
        assertThat(second.admin.listRooms(Node.ADMIN_TOKEN).getData()).extracting(r -> r.getRoomId()).contains("dev");
        assertThat(second.admin.listUsers(Node.ADMIN_TOKEN).getData()).extracting(u -> u.getUsername()).contains("alice");
        var audit = second.admin.getAuditLog(Node.ADMIN_TOKEN, 20).getData();
        assertThat(audit).extracting(e -> e.getAction()).contains("CREATE_ROOM", "DELETE_ROOM");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor = 'op1'")).isEqualTo(2);
    }

    @Test
    void replicatedMessageCreatesMissingRoomInTheDatabase() throws Exception {
        Node n = node(TestDb.fresh(DB));
        ChatMessageDTO m = new ChatMessageDTO(UUID.randomUUID().toString(), "from-peer", "bob#i", "bob", "x", Instant.now(), "server-b", 1, MessageType.CHAT, 0);
        assertThat(n.store.ingestReplicated("server-b", List.of(m)).accepted()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM chat_rooms WHERE room_id = 'from-peer'")).isEqualTo(1);
    }
}
