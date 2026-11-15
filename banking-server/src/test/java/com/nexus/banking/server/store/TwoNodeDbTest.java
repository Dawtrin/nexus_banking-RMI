package com.nexus.banking.server.store;

import com.nexus.banking.server.support.Node;
import com.nexus.banking.server.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.Statement;

import static com.nexus.banking.server.support.Node.m;
import static com.nexus.banking.server.support.Node.req;
import static org.assertj.core.api.Assertions.assertThat;

/** Two branches, each with its OWN MySQL database, partitioned and healed. */
@EnabledIf("com.nexus.banking.server.support.TestDb#available")
class TwoNodeDbTest {
    private Node a;
    private Node b;
    private final String dbA = "nexus_bank_two_a";
    private final String dbB = "nexus_bank_two_b";

    @BeforeEach
    void up() throws Exception {
        a = Node.jdbc("server-a", "server-b", TestDb.fresh(dbA));
        b = Node.jdbc("server-b", "server-a", TestDb.fresh(dbB));
        Node.connect(a, b);
    }

    @AfterEach
    void down() throws Exception {
        a.close();
        b.close();
        try (Connection c = TestDb.admin("mysql"); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbA);
            st.execute("DROP DATABASE IF EXISTS " + dbB);
        }
    }

    private void converge() {
        for (int i = 0; i < 4; i++) {
            a.replication.runOnce();
            b.replication.runOnce();
        }
    }

    @Test
    void partitionedBranchesConvergeAndStayConsistentAfterRestart() throws Exception {
        a.link.set(null);
        b.link.set(null);
        String sa = a.login(Node.AN);
        String sc = b.login(Node.CUONG);
        for (int i = 0; i < 6; i++) a.bank.withdraw(sa, Node.AN, req(), m(100_000), "A offline");
        for (int i = 0; i < 4; i++) b.bank.transfer(sc, Node.CUONG, Node.AN, req(), m(250_000), "B offline");
        a.replication.runOnce();
        assertThat(a.store.outboxStats("server-b").pending()).isEqualTo(6);
        Thread.sleep(300);
        Node.connect(a, b);
        converge();
        String da = a.store.ledgerSummary("server-a").getBalanceDigest();
        String db = b.store.ledgerSummary("server-b").getBalanceDigest();
        assertThat(da).isEqualTo(db);
        assertThat(a.balance(Node.AN)).isEqualByComparingTo("50400000");     // -600k +1,000k
        assertThat(b.balance(Node.CUONG)).isEqualByComparingTo("79000000");
        assertThat(a.store.outboxStats("server-b").pending()).isZero();
        assertThat(b.store.outboxStats("server-a").pending()).isZero();
        assertThat(a.store.ledgerSummary("server-a").getLastSequenceByOrigin()).containsEntry("server-a", 6L).containsEntry("server-b", 8L);

        // a node that restarts keeps its books and continues its own sequence without gaps or reuse
        a.close();
        a = Node.jdbc("server-a", "server-b", TestDb.reopen(dbA));
        Node.connect(a, b);
        assertThat(a.balance(Node.AN)).isEqualByComparingTo("50400000");
        String sa2 = a.login(Node.AN);
        var r = a.bank.withdraw(sa2, Node.AN, req(), m(1_000), "after restart");
        assertThat(r.getData().getOriginSequence()).isEqualTo(7);
        converge();
        assertThat(a.store.ledgerSummary("server-a").getBalanceDigest()).isEqualTo(b.store.ledgerSummary("server-b").getBalanceDigest());
    }
}
