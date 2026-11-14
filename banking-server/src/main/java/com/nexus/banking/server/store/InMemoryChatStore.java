package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.PresenceStatus;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.contract.enums.PeerSyncStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RAM-only store for explicit demo mode ({@code database.enabled=false}). Same semantics as the JDBC
 * store (idempotency, sequences, outbox, checkpoints) but NOT durable: all data is lost on restart.
 * Retention is bounded per room to keep memory finite.
 */
public class InMemoryChatStore implements ChatStore {
    private static final int MAX_MESSAGES_PER_ROOM = 5000;

    private static final class OutboxRow {
        final long id;
        final String messageId;
        final String peerId;
        String status = "PENDING";
        int attempts;
        Instant nextAttempt;
        final Instant createdAt;

        OutboxRow(long id, String messageId, String peerId, Instant now) {
            this.id = id;
            this.messageId = messageId;
            this.peerId = peerId;
            this.nextAttempt = now;
            this.createdAt = now;
        }
    }

    private final Map<String, ChatRoomDTO> rooms = new LinkedHashMap<>();
    private final List<ChatMessageDTO> messages = new ArrayList<>();
    private final Map<String, ChatMessageDTO> byId = new HashMap<>();
    private final Map<String, Long> originSeq = new HashMap<>();
    private final Map<String, String> clientKeys = new HashMap<>();
    private final List<OutboxRow> outbox = new ArrayList<>();
    private final Map<String, Long> checkpoints = new HashMap<>();
    private long localSeq = 0;
    private long outboxSeq = 0;

    public InMemoryChatStore() {
        for (ChatRoomDTO r : DefaultRooms.list()) rooms.put(r.getRoomId(), r);
    }

    @Override
    public String modeLabel() {
        return "RAM_ONLY_DEMO";
    }

    @Override
    public boolean isDurable() {
        return false;
    }

    @Override
    public synchronized List<ChatRoomDTO> listRooms() {
        return new ArrayList<>(rooms.values());
    }

    @Override
    public synchronized Optional<ChatRoomDTO> findRoom(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    @Override
    public synchronized AppendResult appendLocal(String originServerId, String peerId, String roomId, String senderId,
                                                 String senderName, String clientMessageId, String content, String senderAvatar) {
        String key = originServerId + "|" + senderId + "|" + clientMessageId;
        if (clientMessageId != null && clientKeys.containsKey(key)) {
            ChatMessageDTO existing = byId.get(clientKeys.get(key));
            if (existing != null) return new AppendResult(existing, true);
        }
        long seq = originSeq.merge(originServerId, 1L, Long::sum);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String id = UUID.randomUUID().toString();
        ChatMessageDTO m = new ChatMessageDTO(id, roomId, senderId, senderName, content, now, originServerId, seq,
                MessageType.CHAT, ++localSeq, senderAvatar);
        store(m);
        if (clientMessageId != null) clientKeys.put(key, id);
        if (peerId != null) outbox.add(new OutboxRow(++outboxSeq, id, peerId, now));
        return new AppendResult(m, false);
    }

    @Override
    public synchronized Optional<ChatMessageDTO> updateMessage(String originServerId, String peerId, String messageId,
                                                               String senderId, String newContent) {
        ChatMessageDTO existing = byId.get(messageId);
        if (existing == null || !existing.getSenderId().equals(senderId)) {
            return Optional.empty();
        }
        String formatted = newContent.endsWith(" (edited)") ? newContent : newContent + " (edited)";
        ChatMessageDTO updated = new ChatMessageDTO(
                existing.getMessageId(),
                existing.getRoomId(),
                existing.getSenderId(),
                existing.getSenderName(),
                formatted,
                existing.getCreatedAt(),
                existing.getOriginServerId(),
                existing.getOriginSequence(),
                existing.getMessageType(),
                ++localSeq,
                existing.getSenderAvatar()
        );
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageId().equals(messageId)) {
                messages.set(i, updated);
                break;
            }
        }
        byId.put(messageId, updated);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (peerId != null) outbox.add(new OutboxRow(++outboxSeq, messageId, peerId, now));
        return Optional.of(updated);
    }

    private void store(ChatMessageDTO m) {
        messages.add(m);
        byId.put(m.getMessageId(), m);
        long inRoom = messages.stream().filter(x -> x.getRoomId().equals(m.getRoomId())).count();
        if (inRoom > MAX_MESSAGES_PER_ROOM) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getRoomId().equals(m.getRoomId())) {
                    byId.remove(messages.remove(i).getMessageId());
                    break;
                }
            }
        }
    }

    @Override
    public synchronized IngestResult ingestReplicated(String sourcePeerId, List<ChatMessageDTO> batch) {
        List<String> accepted = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        long maxFromPeer = 0;
        for (ChatMessageDTO m : batch) {
            ChatMessageDTO sameSeq = messages.stream()
                    .filter(x -> x.getOriginServerId().equals(m.getOriginServerId()) && x.getOriginSequence() == m.getOriginSequence())
                    .findFirst().orElse(null);
            if (sameSeq != null) {
                if (sameSeq.getMessageId().equals(m.getMessageId())) {
                    if (!sameSeq.getContent().equals(m.getContent())) {
                        ChatMessageDTO updated = new ChatMessageDTO(
                                m.getMessageId(), m.getRoomId(), m.getSenderId(), m.getSenderName(),
                                m.getContent(), m.getCreatedAt(), m.getOriginServerId(), m.getOriginSequence(),
                                m.getMessageType(), ++localSeq, m.getSenderAvatar());
                        for (int i = 0; i < messages.size(); i++) {
                            if (messages.get(i).getMessageId().equals(m.getMessageId())) {
                                messages.set(i, updated);
                                break;
                            }
                        }
                        byId.put(m.getMessageId(), updated);
                    }
                    accepted.add(m.getMessageId());
                    if (m.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, m.getOriginSequence());
                } else {
                    rejected.put(m.getMessageId(), "origin sequence already used by a different message");
                }
                continue;
            }
            if (byId.containsKey(m.getMessageId())) {
                rejected.put(m.getMessageId(), "message id already exists with a different origin sequence");
                continue;
            }
            rooms.computeIfAbsent(m.getRoomId(), id ->
                    new ChatRoomDTO(id, id, "Created automatically by replication", Instant.now()));
            store(new ChatMessageDTO(m.getMessageId(), m.getRoomId(), m.getSenderId(), m.getSenderName(), m.getContent(),
                    m.getCreatedAt(), m.getOriginServerId(), m.getOriginSequence(), m.getMessageType(), ++localSeq, m.getSenderAvatar()));
            accepted.add(m.getMessageId());
            if (m.getOriginServerId().equals(sourcePeerId)) maxFromPeer = Math.max(maxFromPeer, m.getOriginSequence());
        }
        long cp = checkpoints.merge(sourcePeerId, maxFromPeer, Math::max);
        return new IngestResult(accepted, rejected, cp);
    }

    @Override
    public synchronized MessagePage latest(String roomId, int limit) {
        long max = messages.stream().filter(m -> m.getRoomId().equals(roomId)).mapToLong(ChatMessageDTO::getLocalSequence).max().orElse(0);
        List<ChatMessageDTO> sorted = new ArrayList<>(messages.stream().filter(m -> m.getRoomId().equals(roomId)).toList());
        sorted.sort(ChatMessageDTO.displayOrder());
        boolean more = sorted.size() > limit;
        List<ChatMessageDTO> page = new ArrayList<>(sorted.subList(Math.max(0, sorted.size() - limit), sorted.size()));
        return new MessagePage(page, max, more);
    }

    @Override
    public synchronized MessagePage after(String roomId, long afterCursor, int limit) {
        List<ChatMessageDTO> rows = new ArrayList<>(messages.stream()
                .filter(m -> m.getRoomId().equals(roomId) && m.getLocalSequence() > afterCursor)
                .sorted(Comparator.comparingLong(ChatMessageDTO::getLocalSequence)).toList());
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        long next = rows.isEmpty() ? afterCursor : rows.get(rows.size() - 1).getLocalSequence();
        return new MessagePage(rows, next, more);
    }

    @Override
    public synchronized List<ChatMessageDTO> findByOriginAfter(String originServerId, long afterOriginSequence, int limit) {
        return new ArrayList<>(messages.stream()
                .filter(m -> m.getOriginServerId().equals(originServerId) && m.getOriginSequence() > afterOriginSequence)
                .sorted(Comparator.comparingLong(ChatMessageDTO::getOriginSequence)).limit(limit).toList());
    }

    @Override
    public synchronized long getInboundCheckpoint(String peerId) {
        return checkpoints.getOrDefault(peerId, 0L);
    }

    @Override
    public void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) {
        // RAM mode keeps sync state in the replication manager only.
    }

    @Override
    public synchronized List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) {
        List<OutboxEntry> out = new ArrayList<>();
        for (OutboxRow r : outbox) {
            if (out.size() >= limit) break;
            ChatMessageDTO m = byId.get(r.messageId);
            if (r.peerId.equals(peerId) && r.status.equals("PENDING") && !r.nextAttempt.isAfter(now) && m != null) {
                out.add(new OutboxEntry(r.id, r.attempts, m));
            }
        }
        return out;
    }

    @Override
    public synchronized void markDelivered(Collection<Long> ids) {
        outbox.removeIf(r -> ids.contains(r.id));
    }

    @Override
    public synchronized boolean rejectOutbox(long id, String reason, Instant now, long base, long max, int maxAttempts) {
        for (OutboxRow r : outbox) {
            if (r.id == id) {
                r.attempts++;
                boolean dead = r.attempts >= maxAttempts;
                r.status = dead ? "DEAD" : "PENDING";
                r.nextAttempt = now.plusMillis(Math.min(max, base * (1L << Math.min(r.attempts - 1, 20))));
                return dead;
            }
        }
        return false;
    }

    @Override
    public synchronized OutboxStats outboxStats(String peerId) {
        long pending = 0, dead = 0;
        Instant oldest = null;
        for (OutboxRow r : outbox) {
            if (!r.peerId.equals(peerId)) continue;
            if (r.status.equals("PENDING")) {
                pending++;
                if (oldest == null || r.createdAt.isBefore(oldest)) oldest = r.createdAt;
            } else if (r.status.equals("DEAD")) {
                dead++;
            }
        }
        return new OutboxStats(pending, dead, oldest);
    }

    @Override
    public synchronized int requeueDead(String peerId, Instant now) {
        int n = 0;
        for (OutboxRow r : outbox) {
            if (r.peerId.equals(peerId) && r.status.equals("DEAD")) {
                r.status = "PENDING";
                r.attempts = 0;
                r.nextAttempt = now;
                n++;
            }
        }
        return n;
    }

    private final Map<String, UserProfileDTO> profiles = new LinkedHashMap<>();
    private final List<AuditEntryDTO> audit = new ArrayList<>();
    private long auditSeq = 0;

    @Override
    public synchronized boolean createRoom(ChatRoomDTO room) {
        return rooms.putIfAbsent(room.getRoomId(), room) == null;
    }

    @Override
    public synchronized boolean updateRoom(String roomId, String roomName, String description) {
        ChatRoomDTO old = rooms.get(roomId);
        if (old == null) return false;
        rooms.put(roomId, new ChatRoomDTO(roomId, roomName, description, old.getCreatedAt()));
        return true;
    }

    @Override
    public synchronized DeleteOutcome deleteRoom(String roomId) {
        if (!rooms.containsKey(roomId)) return DeleteOutcome.NOT_FOUND;
        if (messages.stream().anyMatch(m -> m.getRoomId().equals(roomId))) return DeleteOutcome.NOT_EMPTY;
        rooms.remove(roomId);
        return DeleteOutcome.DELETED;
    }

    @Override
    public synchronized long countMessages(String roomId) {
        return messages.stream().filter(m -> m.getRoomId().equals(roomId)).count();
    }

    @Override
    public synchronized UserProfileDTO touchProfile(String key, String defaultDisplayName) {
        UserProfileDTO old = profiles.get(key);
        UserProfileDTO p = old == null
                ? new UserProfileDTO(key, defaultDisplayName, "", PresenceStatus.ONLINE, Instant.now(), "")
                : new UserProfileDTO(key, old.getDisplayName(), old.getEmail(), old.getStatus(), Instant.now(), old.getAvatar());
        profiles.put(key, p);
        return p;
    }

    @Override
    public synchronized UserProfileDTO updateProfile(String key, String displayName, String email, PresenceStatus status, String avatar) {
        UserProfileDTO old = profiles.get(key);
        String finalAvatar = avatar != null ? avatar : (old != null ? old.getAvatar() : "");
        UserProfileDTO p = new UserProfileDTO(key, displayName, email, status, Instant.now(), finalAvatar);
        profiles.put(key, p);
        return p;
    }

    @Override
    public synchronized List<UserProfileDTO> listProfiles() {
        return new ArrayList<>(profiles.values());
    }

    @Override
    public synchronized void appendAudit(Instant at, String serverId, String actor, String action, String target, String result, String details) {
        audit.add(new AuditEntryDTO(++auditSeq, at, serverId, actor, action, target, result, details));
        if (audit.size() > 2000) audit.remove(0);
    }

    @Override
    public synchronized List<AuditEntryDTO> recentAudit(int limit) {
        List<AuditEntryDTO> out = new ArrayList<>(audit);
        java.util.Collections.reverse(out);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
