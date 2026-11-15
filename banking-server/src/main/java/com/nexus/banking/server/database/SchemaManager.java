package com.nexus.banking.server.database;

import com.nexus.banking.server.store.DefaultAccounts;
import com.nexus.banking.server.store.PinHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Applies and verifies the idempotent banking schema, seeds demo accounts and recovers origin sequences. */
final class SchemaManager {
    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "bank_accounts", "origin_sequences", "bank_transactions", "replication_outbox", "replication_state", "audit_log");

    private SchemaManager() {
    }

    static void apply(Connection c) throws SQLException {
        failOnLegacySchema(c);
        try (Statement st = c.createStatement()) {
            for (String sql : loadStatements()) {
                st.execute(sql);
            }
        }
        seedAccounts(c);
        recoverOriginSequences(c);
        protectAuditLog(c);
    }

    static void verify(Connection c) throws SQLException {
        failOnLegacySchema(c);
        List<String> missing = new ArrayList<>();
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(c, table)) missing.add(table);
        }
        if (!missing.isEmpty()) {
            throw new SQLException("Schema is incomplete (missing tables " + missing
                    + "). Run db-schema.sql or enable database.schema.auto=true");
        }
    }

    private static void failOnLegacySchema(Connection c) throws SQLException {
        if (tableExists(c, "chat_messages")) {
            throw new SQLException("This database contains the old NEXUS Chat schema (chat_messages). "
                    + "Use a fresh database for NEXUS Banking, e.g. CREATE DATABASE nexus_banking_a.");
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureColumn(Connection c, String table, String column, String alterSql) {
        try {
            if (tableExists(c, table) && !columnExists(c, table, column)) {
                try (Statement st = c.createStatement()) {
                    st.execute(alterSql);
                }
            }
        } catch (SQLException e) {
            log.warn("Could not alter table {}.{}: {}", table, column, e.getMessage());
        }
    }

    /** Every node seeds the SAME demo accounts with the SAME opening balances (INSERT IGNORE keeps existing rows). */
    private static void seedAccounts(Connection c) throws SQLException {
        boolean empty;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bank_accounts")) {
            rs.next();
            empty = rs.getLong(1) == 0;
        }
        if (!empty) return;
        String hash = PinHasher.hash(DefaultAccounts.DEMO_PIN);
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO bank_accounts (account_number, owner_name, "
                + "balance, branch_id, status, daily_limit, pin_hash) VALUES (?,?,?,?,?,?,?)")) {
            for (DefaultAccounts.Seed s : DefaultAccounts.list()) {
                ps.setString(1, s.accountNumber());
                ps.setString(2, s.ownerName());
                ps.setBigDecimal(3, s.balanceValue());
                ps.setString(4, s.branchId());
                ps.setString(5, s.status().name());
                ps.setBigDecimal(6, s.limitValue());
                ps.setString(7, hash);
                ps.executeUpdate();
            }
        }
        log.info("Seeded {} demo accounts (PIN {})", DefaultAccounts.list().size(), DefaultAccounts.DEMO_PIN);
    }

    /** Self-healing: origin_sequences can never lag behind rows that already exist. */
    private static void recoverOriginSequences(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            int n = st.executeUpdate("INSERT INTO origin_sequences (origin_server_id, last_seq) "
                    + "SELECT origin_server_id, MAX(origin_sequence) FROM bank_transactions GROUP BY origin_server_id "
                    + "ON DUPLICATE KEY UPDATE last_seq = GREATEST(last_seq, VALUES(last_seq))");
            if (n > 0) log.info("Origin sequence counters verified against stored ledger entries");
        }
    }

    /**
     * Best effort: database triggers make audit_log append-only (UPDATE/DELETE are rejected). Needs the TRIGGER
     * privilege (and, with binary logging, log_bin_trust_function_creators); when unavailable the hash chain still
     * detects tampering, so a failure here is only logged.
     */
    private static void protectAuditLog(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS trg_audit_no_update");
            st.execute("CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_log FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only'");
            st.execute("DROP TRIGGER IF EXISTS trg_audit_no_delete");
            st.execute("CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_log FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only'");
        } catch (SQLException e) {
            log.warn("Could not install append-only triggers on audit_log ({}); the audit hash chain still protects it",
                    e.getMessage());
        }
    }

    private static List<String> loadStatements() throws SQLException {
        try (InputStream is = SchemaManager.class.getResourceAsStream("/db/db-schema.sql")) {
            if (is == null) throw new SQLException("db-schema.sql not found on classpath");
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder cleaned = new StringBuilder();
            for (String line : raw.split("\\R")) {
                String t = line.trim();
                if (!t.startsWith("--")) cleaned.append(line).append('\n');
            }
            List<String> out = new ArrayList<>();
            for (String stmt : cleaned.toString().split(";")) {
                if (!stmt.trim().isEmpty()) out.add(stmt.trim());
            }
            return out;
        } catch (IOException e) {
            throw new SQLException("Cannot read db-schema.sql: " + e.getMessage(), e);
        }
    }
}
