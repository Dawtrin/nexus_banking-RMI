package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.PresenceStatus;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.contract.enums.PeerSyncStatus;
import com.nexus.banking.server.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MySQL/MariaDB implementation. All writes that allocate ids (append, ingest) run under one writer lock
 * and a single transaction, so commit order equals local_sequence order: a reader that has seen
 * local_sequence N can never later see a committed row with a smaller number. Failures roll back and
 * surface as {@link StorageException}; nothing is swallowed.
 */
public class JdbcChatStore implements ChatStore {
    private static final String MSG_COLS =
            "m.local_sequence, m.message_id, m.room_id, m.sender_id, m.sender_name, m.content, m.message_type, "
                    + "m.created_at, m.origin_server_id, m.origin_sequence";

    private final DatabaseManager db;
    private final ReentrantLock writeLock = new ReentrantLock();

    public JdbcChatStore(DatabaseManager db) {
        this.db = db;
    }

    @FunctionalInterface
    private interface SqlBody<T> {
        T run(Connection c) throws SQLException;
    }

    @Override
    public String modeLabel() {
        return db.isConnected() ? "MYSQL_DURABLE" : "DB_UNAVAILABLE";
    }

    @Override
    public boolean isDurable() {
        return true;
    }

    private <T> T read(SqlBody<T> body) throws StorageException {
        try (Connection c = db.getConnection()) {
            return body.run(c);
        } catch (SQLException e) {
            db.reportFailure(e);
            throw new StorageException("Storage unavailable: " + e.getMessage(), e);
        }
    }

    private <T> T write(SqlBody<T> body) throws StorageException {
        writeLock.lock();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = body.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // connection is unusable anyway; original failure is reported below
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // pool discards broken connections
                }
            }
        } catch (SQLException e) {
            db.reportFailure(e);
            throw new StorageException("Write failed and was rolled back: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new StorageException("Write failed and was rolled back: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    private static LocalDateTime ts(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        LocalDateTime ldt = rs.getObject(col, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static ChatMessageDTO mapRow(ResultSet rs) throws SQLException {
        String avatar = "";
        try {
            avatar = rs.getString("sender_avatar");
            if (avatar == null) avatar = "";
        } catch (SQLException ignored) {
        }
        return new ChatMessageDTO(rs.getString("message_id"), rs.getString("room_id"), rs.getString("sender_id"),
                rs.getString("sender_name"), rs.getString("content"), instant(rs, "created_at"),
                rs.getString("origin_server_id"), rs.getLong("origin_sequence"),
                MessageType.valueOf(rs.getString("message_type")), rs.getLong("local_sequence"), avatar);
    }

    @Override
    public List<ChatRoomDTO> listRooms() throws StorageException {
        return read(c -> {
            List<ChatRoomDTO> rooms = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT room_id, room_name, description, created_at FROM chat_rooms ORDER BY room_id")) {
                while (rs.next()) {
                    rooms.add(new ChatRoomDTO(rs.getString(1), rs.getString(2), rs.getString(3), instant(rs, "created_at")));
                }
            }
            return rooms;
        });
    }

    @Override
    public Optional<ChatRoomDTO> findRoom(String roomId) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT room_id, room_name, description, created_at FROM chat_rooms WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new ChatRoomDTO(rs.getString(1), rs.getString(2), rs.getString(3), instant(rs, "created_at")))
                            : Optional.<ChatRoomDTO>empty();
                }
            }
        });
    }

    @Override
    public AppendResult appendLocal(String originServerId, String peerId, String roomId, String senderId,
                                    String senderName, String clientMessageId, String content, String senderAvatar) throws StorageException {
        return write(c -> {
            if (clientMessageId != null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + MSG_COLS + " FROM chat_messages m "
                        + "WHERE m.origin_server_id = ? AND m.sender_id = ? AND m.client_message_id = ?")) {
                    ps.setString(1, originServerId);
                    ps.setString(2, senderId);
                    ps.setString(3, clientMessageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return new AppendResult(mapRow(rs), true);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO origin_sequences (origin_server_id, last_seq) VALUES (?, 0)")) {
                ps.setString(1, originServerId);
                ps.executeUpdate();
            }
            long next;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_seq FROM origin_sequences WHERE origin_server_id = ? FOR UPDATE")) {
                ps.setString(1, originServerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("origin sequence row missing for " + originServerId);
                    next = rs.getLong(1) + 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE origin_sequences SET last_seq = ? WHERE origin_server_id = ?")) {
                ps.setLong(1, next);
                ps.setString(2, originServerId);
                ps.executeUpdate();
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            String messageId = UUID.randomUUID().toString();
            long local;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_messages (message_id, room_id, sender_id, sender_name, content, message_type, "
                            + "created_at, origin_server_id, origin_sequence, client_message_id, sender_avatar) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, messageId);
                ps.setString(2, roomId);
                ps.setString(3, senderId);
                ps.setString(4, senderName);
                ps.setString(5, content);
                ps.setString(6, MessageType.CHAT.name());
                ps.setObject(7, ts(now));
                ps.setString(8, originServerId);
                ps.setLong(9, next);
                ps.setString(10, clientMessageId);
                ps.setString(11, senderAvatar == null ? "" : senderAvatar);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No local_sequence generated");
                    local = keys.getLong(1);
                }
            }
            if (peerId != null) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_outbox "
                        + "(message_id, target_peer_id, status, attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'PENDING', 0, ?, ?, ?)")) {
                    ps.setString(1, messageId);
                    ps.setString(2, peerId);
                    ps.setObject(3, ts(now));
                    ps.setObject(4, ts(now));
                    ps.setObject(5, ts(now));
                    ps.executeUpdate();
                }
            }
            return new AppendResult(new ChatMessageDTO(messageId, roomId, senderId, senderName, content, now,
                    originServerId, next, MessageType.CHAT, local, senderAvatar == null ? "" : senderAvatar), false);
        });
    }

    @Override
    public Optional<ChatMessageDTO> updateMessage(String originServerId, String peerId, String messageId,
                                                  String senderId, String newContent) throws StorageException {
        return write(c -> {
            ChatMessageDTO existing = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + MSG_COLS + " FROM chat_messages m WHERE m.message_id = ? FOR UPDATE")) {
                ps.setString(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existing = mapRow(rs);
                }
            }
            if (existing == null || !existing.getSenderId().equals(senderId)) {
                return Optional.empty();
            }
            String formatted = newContent.endsWith(" (edited)") ? newContent : newContent + " (edited)";
            long newLocalSeq;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(local_sequence), 0) + 1 FROM chat_messages")) {
                rs.next();
                newLocalSeq = rs.getLong(1);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE chat_messages SET content = ?, local_sequence = ? WHERE message_id = ?")) {
                ps.setString(1, formatted);
                ps.setLong(2, newLocalSeq);
                ps.setString(3, messageId);
                ps.executeUpdate();
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (peerId != null) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_outbox "
                        + "(message_id, target_peer_id, status, attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'PENDING', 0, ?, ?, ?) ON DUPLICATE KEY UPDATE status = 'PENDING', attempt_count = 0, next_attempt_at = VALUES(next_attempt_at), updated_at = VALUES(updated_at)")) {
                    ps.setString(1, messageId);
                    ps.setString(2, peerId);
                    ps.setObject(3, ts(now));
                    ps.setObject(4, ts(now));
                    ps.setObject(5, ts(now));
                    ps.executeUpdate();
                }
            }
            return Optional.of(new ChatMessageDTO(messageId, existing.getRoomId(), existing.getSenderId(),
                    existing.getSenderName(), formatted, existing.getCreatedAt(), existing.getOriginServerId(),
                    existing.getOriginSequence(), existing.getMessageType(), newLocalSeq, existing.getSenderAvatar()));
        });
    }

    @Override
    public IngestResult ingestReplicated(String sourcePeerId, List<ChatMessageDTO> messages) throws StorageException {
        return write(c -> {
            List<String> accepted = new ArrayList<>();
            Map<String, String> rejected = new LinkedHashMap<>();
            long maxFromPeer = 0;
            for (ChatMessageDTO m : messages) {
                String existingId = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT message_id FROM chat_messages WHERE origin_server_id = ? AND origin_sequence = ?")) {
                    ps.setString(1, m.getOriginServerId());
                    ps.setLong(2, m.getOriginSequence());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) existingId = rs.getString(1);
                    }
                }
                if (existingId != null) {
                    if (existingId.equals(m.getMessageId())) {
                        try (PreparedStatement check = c.prepareStatement("SELECT content FROM chat_messages WHERE message_id = ?")) {
                            check.setString(1, m.getMessageId());
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next() && !m.getContent().equals(rs.getString(1))) {
                                    try (Statement st = c.createStatement();
                                         ResultSet r2 = st.executeQuery("SELECT COALESCE(MAX(local_sequence), 0) + 1 FROM chat_messages")) {
                                        r2.next();
                                        long nextSeq = r2.getLong(1);
                                        try (PreparedStatement u = c.prepareStatement("UPDATE chat_messages SET content = ?, local_sequence = ? WHERE message_id = ?")) {
                                            u.setString(1, m.getContent());
                                            u.setLong(2, nextSeq);
                                            u.setString(3, m.getMessageId());
                                            u.executeUpdate();
                                        }
                                    }
                                }
                            }
                        }
                        accepted.add(m.getMessageId());
                        if (m.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, m.getOriginSequence());
                    } else {
                        rejected.put(m.getMessageId(), "origin sequence " + m.getOriginServerId() + "#"
                                + m.getOriginSequence() + " already used by a different message");
                    }
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM chat_messages WHERE message_id = ?")) {
                    ps.setString(1, m.getMessageId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            rejected.put(m.getMessageId(), "message id already exists with a different origin sequence");
                            continue;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO chat_rooms (room_id, room_name, description) VALUES (?, ?, 'Created automatically by replication')")) {
                    ps.setString(1, m.getRoomId());
                    ps.setString(2, m.getRoomId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_messages (message_id, room_id, sender_id, sender_name, content, message_type, "
                                + "created_at, origin_server_id, origin_sequence, sender_avatar) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, m.getMessageId());
                    ps.setString(2, m.getRoomId());
                    ps.setString(3, m.getSenderId());
                    ps.setString(4, m.getSenderName());
                    ps.setString(5, m.getContent());
                    ps.setString(6, m.getMessageType().name());
                    ps.setObject(7, ts(m.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
                    ps.setString(8, m.getOriginServerId());
                    ps.setLong(9, m.getOriginSequence());
                    ps.setString(10, m.getSenderAvatar() == null ? "" : m.getSenderAvatar());
                    ps.executeUpdate();
                }
                accepted.add(m.getMessageId());
                if (m.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, m.getOriginSequence());
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_state "
                    + "(peer_server_id, last_replicated_origin_seq, last_success_at, last_sync_status, updated_at) "
                    + "VALUES (?, ?, ?, 'SYNCED', ?) ON DUPLICATE KEY UPDATE "
                    + "last_replicated_origin_seq = GREATEST(last_replicated_origin_seq, VALUES(last_replicated_origin_seq)), "
                    + "last_success_at = VALUES(last_success_at), updated_at = VALUES(updated_at)")) {
                ps.setString(1, sourcePeerId);
                ps.setLong(2, maxFromPeer);
                ps.setObject(3, ts(now));
                ps.setObject(4, ts(now));
                ps.executeUpdate();
            }
            long checkpoint;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_replicated_origin_seq FROM replication_state WHERE peer_server_id = ?")) {
                ps.setString(1, sourcePeerId);
                try (ResultSet rs = ps.executeQuery()) {
                    checkpoint = rs.next() ? rs.getLong(1) : 0;
                }
            }
            return new IngestResult(accepted, rejected, checkpoint);
        });
    }

    @Override
    public MessagePage latest(String roomId, int limit) throws StorageException {
        return read(c -> {
            long max;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(local_sequence), 0) FROM chat_messages WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    max = rs.getLong(1);
                }
            }
            List<ChatMessageDTO> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + MSG_COLS + " FROM chat_messages m "
                    + "WHERE m.room_id = ? AND m.local_sequence <= ? "
                    + "ORDER BY m.created_at DESC, m.origin_server_id DESC, m.origin_sequence DESC LIMIT ?")) {
                ps.setString(1, roomId);
                ps.setLong(2, max);
                ps.setInt(3, limit + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(mapRow(rs));
                }
            }
            boolean more = rows.size() > limit;
            if (more) rows.remove(rows.size() - 1);
            Collections.reverse(rows);
            return new MessagePage(rows, max, more);
        });
    }

    @Override
    public MessagePage after(String roomId, long afterCursor, int limit) throws StorageException {
        return read(c -> {
            List<ChatMessageDTO> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + MSG_COLS + " FROM chat_messages m "
                    + "WHERE m.room_id = ? AND m.local_sequence > ? ORDER BY m.local_sequence ASC LIMIT ?")) {
                ps.setString(1, roomId);
                ps.setLong(2, afterCursor);
                ps.setInt(3, limit + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(mapRow(rs));
                }
            }
            boolean more = rows.size() > limit;
            if (more) rows.remove(rows.size() - 1);
            long next = rows.isEmpty() ? afterCursor : rows.get(rows.size() - 1).getLocalSequence();
            return new MessagePage(rows, next, more);
        });
    }

    @Override
    public List<ChatMessageDTO> findByOriginAfter(String originServerId, long afterOriginSequence, int limit) throws StorageException {
        return read(c -> {
            List<ChatMessageDTO> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + MSG_COLS + " FROM chat_messages m "
                    + "WHERE m.origin_server_id = ? AND m.origin_sequence > ? ORDER BY m.origin_sequence ASC LIMIT ?")) {
                ps.setString(1, originServerId);
                ps.setLong(2, afterOriginSequence);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(mapRow(rs));
                }
            }
            return rows;
        });
    }

    @Override
    public long getInboundCheckpoint(String peerId) throws StorageException {
        return read(c -> {
            long fromState = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_replicated_origin_seq FROM replication_state WHERE peer_server_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fromState = rs.getLong(1);
                }
            }
            long fromMessages = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(origin_sequence), 0) FROM chat_messages WHERE origin_server_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fromMessages = rs.getLong(1);
                }
            }
            return Math.max(fromState, fromMessages);
        });
    }

    @Override
    public void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) throws StorageException {
        read(c -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO replication_state "
                    + "(peer_server_id, last_replicated_origin_seq, last_success_at, last_sync_status, last_error, updated_at) "
                    + "VALUES (?, 0, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                    + "last_success_at = COALESCE(VALUES(last_success_at), last_success_at), "
                    + "last_sync_status = VALUES(last_sync_status), last_error = VALUES(last_error), "
                    + "updated_at = VALUES(updated_at)")) {
                ps.setString(1, peerId);
                ps.setObject(2, success ? ts(now) : null);
                ps.setString(3, status.name());
                ps.setString(4, error == null ? null : error.substring(0, Math.min(500, error.length())));
                ps.setObject(5, ts(now));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) throws StorageException {
        return read(c -> {
            List<OutboxEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT o.outbox_id, o.attempt_count, " + MSG_COLS
                    + " FROM replication_outbox o JOIN chat_messages m ON m.message_id = o.message_id "
                    + "WHERE o.target_peer_id = ? AND o.status = 'PENDING' AND o.next_attempt_at <= ? "
                    + "ORDER BY o.outbox_id ASC LIMIT ?")) {
                ps.setString(1, peerId);
                ps.setObject(2, ts(now));
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new OutboxEntry(rs.getLong("outbox_id"), rs.getInt("attempt_count"), mapRow(rs)));
                }
            }
            return out;
        });
    }

    @Override
    public void markDelivered(Collection<Long> outboxIds) throws StorageException {
        if (outboxIds.isEmpty()) return;
        read(c -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE replication_outbox SET status = 'DELIVERED', updated_at = ? WHERE outbox_id = ? AND status = 'PENDING'")) {
                for (Long id : outboxIds) {
                    ps.setObject(1, ts(now));
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public boolean rejectOutbox(long outboxId, String reason, Instant now, long baseMillis, long maxMillis, int maxAttempts)
            throws StorageException {
        return read(c -> {
            int attempts;
            try (PreparedStatement ps = c.prepareStatement("SELECT attempt_count FROM replication_outbox WHERE outbox_id = ?")) {
                ps.setLong(1, outboxId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    attempts = rs.getInt(1) + 1;
                }
            }
            boolean dead = attempts >= maxAttempts;
            long delay = Math.min(maxMillis, baseMillis * (1L << Math.min(attempts - 1, 20)));
            try (PreparedStatement ps = c.prepareStatement("UPDATE replication_outbox SET status = ?, attempt_count = ?, "
                    + "next_attempt_at = ?, last_error = ?, updated_at = ? WHERE outbox_id = ?")) {
                ps.setString(1, dead ? "DEAD" : "PENDING");
                ps.setInt(2, attempts);
                ps.setObject(3, ts(now.plusMillis(delay).truncatedTo(ChronoUnit.MILLIS)));
                ps.setString(4, reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
                ps.setObject(5, ts(now.truncatedTo(ChronoUnit.MILLIS)));
                ps.setLong(6, outboxId);
                ps.executeUpdate();
            }
            return dead;
        });
    }

    @Override
    public OutboxStats outboxStats(String peerId) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT "
                    + "COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END), 0), "
                    + "MIN(CASE WHEN status = 'PENDING' THEN created_at END) AS oldest "
                    + "FROM replication_outbox WHERE target_peer_id = ?")) {
                ps.setString(1, peerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new OutboxStats(rs.getLong(1), rs.getLong(2), instant(rs, "oldest"));
                }
            }
        });
    }

    @Override
    public int requeueDead(String peerId, Instant now) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE replication_outbox SET status = 'PENDING', attempt_count = 0, "
                    + "next_attempt_at = ?, last_error = NULL, updated_at = ? WHERE status = 'DEAD' AND target_peer_id = ?")) {
                LocalDateTime t = ts(now.truncatedTo(ChronoUnit.MILLIS));
                ps.setObject(1, t);
                ps.setObject(2, t);
                ps.setString(3, peerId);
                return ps.executeUpdate();
            }
        });
    }

    // ---------------------------------------------------------------- rooms admin

    @Override
    public boolean createRoom(ChatRoomDTO room) throws StorageException {
        return write(c -> {
            if (roomExists(c, room.getRoomId())) return false;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_rooms (room_id, room_name, description, created_at) VALUES (?,?,?,?)")) {
                ps.setString(1, room.getRoomId());
                ps.setString(2, room.getRoomName());
                ps.setString(3, room.getDescription());
                ps.setObject(4, ts(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
                ps.executeUpdate();
            }
            return true;
        });
    }

    private static boolean roomExists(Connection c, String roomId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM chat_rooms WHERE room_id = ?")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public boolean updateRoom(String roomId, String roomName, String description) throws StorageException {
        return write(c -> {
            if (!roomExists(c, roomId)) return false;
            try (PreparedStatement ps = c.prepareStatement("UPDATE chat_rooms SET room_name = ?, description = ? WHERE room_id = ?")) {
                ps.setString(1, roomName);
                ps.setString(2, description);
                ps.setString(3, roomId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public DeleteOutcome deleteRoom(String roomId) throws StorageException {
        return write(c -> {
            if (!roomExists(c, roomId)) return DeleteOutcome.NOT_FOUND;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chat_messages WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) return DeleteOutcome.NOT_EMPTY;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM chat_rooms WHERE room_id = ?")) {
                ps.setString(1, roomId);
                ps.executeUpdate();
            }
            return DeleteOutcome.DELETED;
        });
    }

    @Override
    public long countMessages(String roomId) throws StorageException {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chat_messages WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    // ------------------------------------------------------------------- profiles

    private static UserProfileDTO profileRow(ResultSet rs) throws SQLException {
        String avatar = "";
        try {
            avatar = rs.getString("avatar");
            if (avatar == null) avatar = "";
        } catch (SQLException ignored) {
        }
        return new UserProfileDTO(rs.getString("username"), rs.getString("display_name"), rs.getString("email"),
                PresenceStatus.valueOf(rs.getString("status")), instant(rs, "last_seen_at"), avatar);
    }

    @Override
    public UserProfileDTO touchProfile(String usernameKey, String defaultDisplayName) throws StorageException {
        return write(c -> {
            LocalDateTime now = ts(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO user_profiles (username, display_name, created_at, last_seen_at) "
                    + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at)")) {
                ps.setString(1, usernameKey);
                ps.setString(2, defaultDisplayName);
                ps.setObject(3, now);
                ps.setObject(4, now);
                ps.executeUpdate();
            }
            return findProfile(c, usernameKey);
        });
    }

    private static UserProfileDTO findProfile(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM user_profiles WHERE username = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("profile missing for " + key);
                return profileRow(rs);
            }
        }
    }

    @Override
    public UserProfileDTO updateProfile(String usernameKey, String displayName, String email, PresenceStatus status, String avatar) throws StorageException {
        return write(c -> {
            boolean hasAvatar = avatar != null;
            String sql = hasAvatar
                    ? "UPDATE user_profiles SET display_name = ?, email = ?, status = ?, avatar = ?, last_seen_at = ? WHERE username = ?"
                    : "UPDATE user_profiles SET display_name = ?, email = ?, status = ?, last_seen_at = ? WHERE username = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, displayName);
                ps.setString(2, email);
                ps.setString(3, status.name());
                if (hasAvatar) {
                    ps.setString(4, avatar);
                    ps.setObject(5, ts(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
                    ps.setString(6, usernameKey);
                } else {
                    ps.setObject(4, ts(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
                    ps.setString(5, usernameKey);
                }
                if (ps.executeUpdate() == 0) throw new SQLException("profile missing for " + usernameKey);
            }
            return findProfile(c, usernameKey);
        });
    }

    @Override
    public List<UserProfileDTO> listProfiles() throws StorageException {
        return read(c -> {
            List<UserProfileDTO> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT * FROM user_profiles ORDER BY last_seen_at DESC LIMIT 1000")) {
                while (rs.next()) out.add(profileRow(rs));
            }
            return out;
        });
    }

    // ---------------------------------------------------------------------- audit

    @Override
    public void appendAudit(Instant at, String serverId, String actor, String action, String target, String result, String details)
            throws StorageException {
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log (occurred_at, server_id, actor, action, target, result, details) "
                    + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setObject(1, ts(at.truncatedTo(ChronoUnit.MILLIS)));
                ps.setString(2, serverId);
                ps.setString(3, actor);
                ps.setString(4, action);
                ps.setString(5, target);
                ps.setString(6, result);
                ps.setString(7, details == null ? "" : details.substring(0, Math.min(1000, details.length())));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<AuditEntryDTO> recentAudit(int limit) throws StorageException {
        return read(c -> {
            List<AuditEntryDTO> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new AuditEntryDTO(rs.getLong("audit_id"), instant(rs, "occurred_at"), rs.getString("server_id"),
                                rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("result"),
                                rs.getString("details")));
                    }
                }
            }
            return out;
        });
    }
}
