package com.nexus.banking.server.database;

import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.server.store.DefaultRooms;
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

/** Applies and verifies the idempotent v2 schema, seeds default rooms and recovers origin sequences. */
final class SchemaManager {
    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "chat_rooms", "origin_sequences", "chat_messages", "replication_outbox", "replication_state", "user_profiles", "audit_log");

    private SchemaManager() {
    }

    static void apply(Connection c) throws SQLException {
        failOnLegacySchema(c);
        try (Statement st = c.createStatement()) {
            for (String sql : loadStatements()) {
                st.execute(sql);
            }
        }
        ensureColumn(c, "chat_messages", "sender_avatar", "ALTER TABLE chat_messages ADD COLUMN sender_avatar MEDIUMTEXT NULL");
        ensureColumn(c, "user_profiles", "avatar", "ALTER TABLE user_profiles ADD COLUMN avatar MEDIUMTEXT NULL");
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE chat_messages MODIFY COLUMN content MEDIUMTEXT NOT NULL");
        } catch (SQLException ignored) {
            // Silently proceed if already modified or restricted
        }
        seedRooms(c);
        recoverOriginSequences(c);
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
        if (tableExists(c, "chat_messages") && !columnExists(c, "chat_messages", "client_message_id")) {
            throw new SQLException("Legacy v1 schema detected (chat_messages lacks client_message_id). "
                    + "v1 stored sequences unsafely and cannot be migrated automatically; "
                    + "export what you need, then drop and recreate the database.");
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

    private static void seedRooms(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO chat_rooms (room_id, room_name, description) VALUES (?, ?, ?)")) {
            for (ChatRoomDTO room : DefaultRooms.list()) {
                ps.setString(1, room.getRoomId());
                ps.setString(2, room.getRoomName());
                ps.setString(3, room.getDescription());
                ps.executeUpdate();
            }
        }
    }

    /** Self-healing: origin_sequences can never lag behind rows that already exist. */
    private static void recoverOriginSequences(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            int n = st.executeUpdate("INSERT INTO origin_sequences (origin_server_id, last_seq) "
                    + "SELECT origin_server_id, MAX(origin_sequence) FROM chat_messages GROUP BY origin_server_id "
                    + "ON DUPLICATE KEY UPDATE last_seq = GREATEST(last_seq, VALUES(last_seq))");
            if (n > 0) log.info("Origin sequence counters verified against stored messages");
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
