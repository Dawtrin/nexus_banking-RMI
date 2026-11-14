package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.contract.remote.ReplicationRemote;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.server.support.Node;
import com.nexus.banking.server.support.TcpProxy;
import com.nexus.banking.server.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Two server nodes, two separate databases, in one JVM (no network). SKIPPED unless NEXUS_TEST_DB_HOST is set. */
class TwoNodeDbTest {
    private static final String DB_A = "nexus_it_node_a";
    private static final String DB_B = "nexus_it_node_b";
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

    private Node open(String id, String peer, com.nexus.banking.server.config.DatabaseConfig cfg) {
        Node n = Node.jdbc(id, peer, cfg);
        toClose.add(n);
        return n;
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private List<String> contents(Node n, String user) {
        String s = n.login(user, "instance-" + user + "-7777", "general");
        return n.chat.getHistory(s, "general", 200).getData().getMessages().stream().map(ChatMessageDTO::getContent).toList();
    }

    @Test
    void outboxAndCheckpointsSurviveRestartsOfBothNodes() throws Exception {
        Node a = open("server-a", "server-b", TestDb.fresh(DB_A));
        Node b = open("server-b", "server-a", TestDb.fresh(DB_B));
        b.link.set(a.repl);                       // B can reach A; A cannot reach B (offline)
        String alice = a.login("alice");
        for (int i = 1; i <= 10; i++) a.send(alice, "a" + i);
        a.replication.runOnce();
        assertThat(a.replication.getStatus().getPendingCount()).isEqualTo(10);
        assertThat(a.replication.getStatus().getPeerStatus()).isEqualTo(ServerStatus.UNREACHABLE);

        a.close();                                // A "crashes" with 10 undelivered messages
        Node a2 = open("server-a", "server-b", TestDb.reopen(DB_A));
        assertThat(a2.replication.getStatus().getPendingCount()).isEqualTo(10);   // outbox was durable
        a2.link.set(b.repl);
        sleep(150);
        a2.replication.runOnce();
        assertThat(a2.replication.getStatus().getPendingCount()).isZero();
        assertThat(contents(b, "bobreader")).hasSize(10);

        String alice2 = a2.login("alice");
        a2.send(alice2, "a11");
        assertThat(a2.store.findByOriginAfter("server-a", 10, 10)).extracting(ChatMessageDTO::getOriginSequence).containsExactly(11L);

        b.close();                                // B restarts: checkpoint must be durable
        Node b2 = open("server-b", "server-a", TestDb.reopen(DB_B));
        assertThat(b2.store.getInboundCheckpoint("server-a")).isEqualTo(10);
        b2.link.set(a2.repl);
        a2.link.set(b2.repl);
        b2.replication.runOnce();                 // catch-up fetches only a11
        assertThat(contents(b2, "bobreader2")).hasSize(11);
        assertThat(b2.store.getInboundCheckpoint("server-a")).isEqualTo(11);
    }

    @Test
    void catchUpAcrossManyBatchesAgainstARealDatabase() throws Exception {
        Node a = open("server-a", "server-b", TestDb.fresh(DB_A));
        Node b = open("server-b", "server-a", TestDb.fresh(DB_B));
        for (int i = 1; i <= 250; i++) {
            a.store.appendLocal("server-a", "server-b", "general", "alice#i", "alice", UUID.randomUUID().toString(), "m" + i);
        }
        b.link.set(a.repl);                       // pull only; A cannot push
        b.replication.runOnce();
        assertThat(b.store.getInboundCheckpoint("server-a")).isEqualTo(250);
        assertThat(b.store.latest("general", 200).getNextCursor()).isPositive();
        assertThat(b.store.findByOriginAfter("server-a", 0, 1000)).hasSize(250);
    }

    @Test
    void receiverDoesNotAcknowledgeWhenItsDatabaseIsDown() throws Exception {
        TestDb.fresh(DB_B);
        TcpProxy proxy = new TcpProxy(33307, "127.0.0.1", TestDb.realPort());
        toClose.add(proxy);
        proxy.start();
        Node a = open("server-a", "server-b", TestDb.fresh(DB_A));
        Node b = open("server-b", "server-a", TestDb.viaPortFor(DB_B, 33307));
        a.link.set(b.repl);
        String alice = a.login("alice");
        a.send(alice, "must-not-be-lost");
        a.replication.runOnce();                  // delivered normally first
        assertThat(a.replication.getStatus().getPendingCount()).isZero();

        proxy.stop();                             // B's database dies
        a.send(alice, "while-b-db-down");
        sleep(150);
        a.replication.runOnce();
        assertThat(a.replication.getStatus().getPendingCount()).isEqualTo(1);    // NOT acknowledged => still pending
        assertThat(a.replication.getStatus().getPeerStatus()).isEqualTo(ServerStatus.DEGRADED);

        proxy.start();                            // B's database returns, no restart
        sleep(900);
        a.replication.runOnce();
        assertThat(a.replication.getStatus().getPendingCount()).isZero();
        assertThat(b.store.findByOriginAfter("server-a", 0, 10)).extracting(ChatMessageDTO::getContent)
                .containsExactly("must-not-be-lost", "while-b-db-down");
    }
}
