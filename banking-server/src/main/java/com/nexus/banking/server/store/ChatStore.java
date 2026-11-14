package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.PresenceStatus;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.enums.PeerSyncStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Single persistence boundary for messages, the replication outbox and replication checkpoints.
 * Every method either completes durably (for the durable implementation) or throws {@link StorageException};
 * nothing is ever silently dropped or reported as stored when it was not.
 */
public interface ChatStore {

    /** Honest label: {@code MYSQL_DURABLE} or {@code RAM_ONLY_DEMO}. */
    String modeLabel();

    boolean isDurable();

    List<ChatRoomDTO> listRooms() throws StorageException;

    Optional<ChatRoomDTO> findRoom(String roomId) throws StorageException;

    /**
     * Atomically: idempotency check on (sender, clientMessageId), origin-sequence allocation, message insert
     * and (when {@code peerId != null}) outbox insert. Any failure rolls everything back.
     */
    default AppendResult appendLocal(String originServerId, String peerId, String roomId, String senderId,
                             String senderName, String clientMessageId, String content) throws StorageException {
        return appendLocal(originServerId, peerId, roomId, senderId, senderName, clientMessageId, content, "");
    }

    AppendResult appendLocal(String originServerId, String peerId, String roomId, String senderId,
                             String senderName, String clientMessageId, String content, String senderAvatar) throws StorageException;

    /**
     * Atomically edits an existing message if senderId matches. Advances sequence and queues outbox row.
     */
    Optional<ChatMessageDTO> updateMessage(String originServerId, String peerId, String messageId, String senderId, String newContent) throws StorageException;

    /** Atomically stores a replicated batch and advances the inbound checkpoint for {@code sourcePeerId}. */
    IngestResult ingestReplicated(String sourcePeerId, List<ChatMessageDTO> messages) throws StorageException;

    /** Latest {@code limit} messages in display order, with the ingestion cursor to poll from. */
    MessagePage latest(String roomId, int limit) throws StorageException;

    /** Messages with localSequence greater than {@code afterCursor}, oldest ingestion first. */
    MessagePage after(String roomId, long afterCursor, int limit) throws StorageException;

    List<ChatMessageDTO> findByOriginAfter(String originServerId, long afterOriginSequence, int limit) throws StorageException;

    long getInboundCheckpoint(String peerId) throws StorageException;

    void recordSyncState(String peerId, PeerSyncStatus status, String error, boolean success) throws StorageException;

    List<OutboxEntry> dueOutbox(String peerId, Instant now, int limit) throws StorageException;

    void markDelivered(Collection<Long> outboxIds) throws StorageException;

    /** Counts a peer rejection: exponential backoff, and DEAD after {@code maxAttempts}. Returns true when dead-lettered. */
    boolean rejectOutbox(long outboxId, String reason, Instant now, long baseMillis, long maxMillis, int maxAttempts)
            throws StorageException;

    OutboxStats outboxStats(String peerId) throws StorageException;

    int requeueDead(String peerId, Instant now) throws StorageException;

    enum DeleteOutcome { DELETED, NOT_FOUND, NOT_EMPTY }

    /** False when a room with this id already exists. */
    boolean createRoom(ChatRoomDTO room) throws StorageException;

    /** False when the room does not exist. */
    boolean updateRoom(String roomId, String roomName, String description) throws StorageException;

    /** Only rooms without messages can be deleted. */
    DeleteOutcome deleteRoom(String roomId) throws StorageException;

    long countMessages(String roomId) throws StorageException;

    /** Creates the profile on first login (display name defaults to the username) and refreshes last-seen. */
    UserProfileDTO touchProfile(String usernameKey, String defaultDisplayName) throws StorageException;

    default UserProfileDTO updateProfile(String usernameKey, String displayName, String email, PresenceStatus status) throws StorageException {
        return updateProfile(usernameKey, displayName, email, status, null);
    }

    UserProfileDTO updateProfile(String usernameKey, String displayName, String email, PresenceStatus status, String avatar) throws StorageException;

    List<UserProfileDTO> listProfiles() throws StorageException;

    void appendAudit(Instant at, String serverId, String actor, String action, String target, String result, String details)
            throws StorageException;

    /** Newest first. */
    List<AuditEntryDTO> recentAudit(int limit) throws StorageException;
}
