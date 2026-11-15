package com.nexus.banking.server.store;

import com.nexus.banking.server.config.DatabaseConfig;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The same contract against a REAL MySQL/MariaDB (skipped, never "passed", when NEXUS_TEST_DB_HOST is not set). */
@EnabledIf("com.nexus.banking.server.support.TestDb#available")
class JdbcBankingStoreDbTest extends BankingStoreContractTest {
    private static int counter = 0;
    private DatabaseManager db;
    private String dbName;

    @Override
    protected BankingStore store() throws Exception {
        dbName = "nexus_bank_t" + (++counter) + "_" + (System.nanoTime() % 100000);
        DatabaseConfig cfg = TestDb.fresh(dbName);
        db = new DatabaseManager(cfg, true);
        return new JdbcBankingStore(db);
    }

    @AfterEach
    void drop() throws Exception {
        if (db != null) db.close();
        if (dbName != null) {
            try (Connection c = TestDb.admin("mysql"); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + dbName);
            }
        }
    }

    @Test
    void ledgerSurvivesARestartOfTheStore() throws Exception {
        BankingStore s = store();
        s.withdraw("n-a", "n-b", A, req(), m(1_000_000), "", DAY);
        db.close();
        db = new DatabaseManager(TestDb.reopen(dbName), true);
        BankingStore again = new JdbcBankingStore(db);
        assertThat(again.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("49000000");
        assertThat(again.outboxStats("n-b").pending()).isEqualTo(1);
        // origin sequences continue, never restart (unique (origin, sequence) would otherwise explode)
        MoneyResult next = again.withdraw("n-a", "n-b", A, req(), m(1_000), "", DAY);
        assertThat(next.transaction().getOriginSequence()).isEqualTo(2);
    }

    @Test
    void databaseRejectsDuplicateRequestIdsEvenIfTheApplicationWere_Bypassed() throws Exception {
        BankingStore s = store();
        String id = req();
        s.deposit("n-a", null, A, id, m(1), "");
        try (Connection c = TestDb.admin(dbName); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO bank_transactions (transaction_id, account_number, "
                    + "transaction_type, amount, balance_after, created_at, origin_server_id, origin_sequence, client_request_id) "
                    + "VALUES ('forced-1','" + A + "','DEPOSIT',1,1,NOW(3),'n-x',1,'" + id + "')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void auditTriggersMakeTheLedgerAppendOnlyAndTheChainDetectsTamperingAnyway() throws Exception {
        BankingStore s = store();
        for (int i = 0; i < 4; i++) s.appendAudit(Instant.now(), "n-a", "ops", "LOCK_ACCOUNT", "acc" + i, "OK", "reason " + i);
        try (Connection c = TestDb.admin(dbName); Statement st = c.createStatement()) {
            boolean trigger;
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='" + dbName + "'")) {
                rs.next();
                trigger = rs.getInt(1) >= 2;
            }
            if (trigger) {
                assertThatThrownBy(() -> st.executeUpdate("UPDATE audit_log SET details='x' WHERE audit_id=2")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_log WHERE audit_id=2")).isInstanceOf(SQLException.class);
                st.execute("DROP TRIGGER trg_audit_no_update");        // an attacker with DBA rights removes the guard...
            }
            st.executeUpdate("UPDATE audit_log SET details='nothing happened' WHERE audit_id=2");   // ...and edits history
        }
        AuditVerificationDtoCheck.assertBroken(s.verifyAuditChain(), 2);
    }

    /** Small helper keeps the assertion readable. */
    static final class AuditVerificationDtoCheck {
        static void assertBroken(com.nexus.banking.contract.dto.AuditVerificationDTO v, long id) {
            assertThat(v.isValid()).isFalse();
            assertThat(v.getFirstBrokenId()).isEqualTo(id);
        }
    }
}
