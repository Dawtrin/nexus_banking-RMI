package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.PresenceStatus;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.rmi.RemoteClientContext;
import com.nexus.banking.server.service.ClientSessionManager.Session;
import com.nexus.banking.server.store.AppendResult;
import com.nexus.banking.server.store.ChatStore;
import com.nexus.banking.server.store.StorageException;

import java.util.List;
import java.util.Optional;

/**
 * Client-facing service. Authorization is enforced here on the server for every call:
 * valid (non-disconnected) session, existing room, and room membership for reading and sending.
 * A message is reported as sent only after the store has committed it.
 */
public class ChatServiceImpl implements ChatRemote {
    private final ServerConfig config;
    private final ChatStore store;
    private final ClientSessionManager sessions;
    private final PeerReplicationManager replication;
    private final ServerMetrics metrics;
    private final SystemLogService logService;

    public ChatServiceImpl(ServerConfig config, ChatStore store, ClientSessionManager sessions,
                           PeerReplicationManager replication, ServerMetrics metrics, SystemLogService logService) {
        this.config = config;
        this.store = store;
        this.sessions = sessions;
        this.replication = replication;
        this.metrics = metrics;
        this.logService = logService;
    }

    private static <T> OperationResult<T> fail(String code, String message) {
        return OperationResult.failure(code, message);
    }

    private <T> OperationResult<T> invalidSession(String sessionId) {
        ClientSessionManager.Session s = sessions.findAny(sessionId);
        if (s != null && s.isEvicted()) {
            return fail(ErrorCodes.SESSION_EVICTED, s.getDisconnectReason());
        }
        return fail(ErrorCodes.INVALID_SESSION, "Invalid or expired session");
    }

    private <T> OperationResult<T> storageFailure(StorageException e) {
        logService.logEvent(EventSeverity.ERROR, "STORAGE", "Operation failed: " + e.getMessage());
        return fail(ErrorCodes.STORAGE_UNAVAILABLE, "Storage is unavailable; the operation was NOT stored. Try again later.");
    }

    @Override
    public OperationResult<ClientSessionDTO> registerClient(String username, String clientInstanceId) {
        String e = Validation.username(username);
        if (e == null) e = Validation.instanceId(clientInstanceId);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        UserProfileDTO profile = null;
        try {
            profile = store.touchProfile(username.toLowerCase(), username);
        } catch (StorageException ex) {
            // Profiles are not critical for chatting: fall back to defaults, the failure is logged.
            logService.logEvent(EventSeverity.WARN, "STORAGE", "Profile lookup failed for a login: " + ex.getMessage());
        }
        if (config.isSingleSessionEnabled()) {
            sessions.evictByUsername(username, "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác (" + RemoteClientContext.getObservedClientHost() + ")");
            if (replication != null) {
                String clientHost = RemoteClientContext.getObservedClientHost();
                replication.evictPeerSession(username, "Tài khoản của bạn đã được đăng nhập từ Node " + config.getServerId() + " (" + clientHost + ")");
            }
        }

        Session s = sessions.register(username, clientInstanceId, RemoteClientContext.getObservedClientHost(),
                profile == null ? username : profile.getDisplayName(),
                profile == null ? PresenceStatus.ONLINE : profile.getStatus(),
                profile == null ? "" : profile.getAvatar());
        if (s == null) return fail(ErrorCodes.VALIDATION, "Server is full; try again later");
        return OperationResult.success("Registered on " + config.getServerId() + " (" + store.modeLabel() + ")", sessions.toDto(s));
    }

    @Override
    public OperationResult<List<ChatRoomDTO>> getRooms(String sessionId) {
        if (sessions.getUsable(sessionId) == null) return invalidSession(sessionId);
        try {
            return OperationResult.success(store.listRooms());
        } catch (StorageException e) {
            return storageFailure(e);
        }
    }

    @Override
    public OperationResult<Void> joinRoom(String sessionId, String roomId) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.roomId(roomId);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        try {
            if (store.findRoom(roomId).isEmpty()) return fail(ErrorCodes.ROOM_NOT_FOUND, "Room '" + roomId + "' does not exist");
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
        sessions.join(s, roomId);
        return OperationResult.ok("Joined " + roomId);
    }

    @Override
    public OperationResult<ChatMessageDTO> sendMessage(String sessionId, String roomId, String clientMessageId, String content) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.roomId(roomId);
        if (e == null) e = Validation.clientMessageId(clientMessageId);
        if (e == null) e = Validation.content(content);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        try {
            if (store.findRoom(roomId).isEmpty()) return fail(ErrorCodes.ROOM_NOT_FOUND, "Room '" + roomId + "' does not exist");
            if (!s.isMemberOf(roomId)) return fail(ErrorCodes.NOT_IN_ROOM, "Join room '" + roomId + "' before sending");
            AppendResult r = store.appendLocal(config.getServerId(), config.getPeerId(), roomId, s.getSenderId(),
                    s.getDisplayName(), clientMessageId, Validation.normalizeContent(content), s.getAvatar());
            if (!r.duplicate()) {
                metrics.incrementMessagesStored();
                replication.wake();
            }
            String note = store.isDurable() ? "Stored durably" : "Stored in RAM-only demo mode (NOT durable)";
            return OperationResult.success(r.duplicate() ? "Already stored (idempotent retry)" : note, r.message());
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    @Override
    public OperationResult<ChatMessageDTO> editMessage(String sessionId, String messageId, String newContent) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.content(newContent);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        if (messageId == null || messageId.isBlank()) return fail(ErrorCodes.VALIDATION, "messageId cannot be blank");
        try {
            Optional<ChatMessageDTO> updated = store.updateMessage(config.getServerId(), config.getPeerId(),
                    messageId.trim(), s.getSenderId(), Validation.normalizeContent(newContent));
            if (updated.isEmpty()) {
                return fail(ErrorCodes.VALIDATION, "Message not found or you are not the author");
            }
            replication.wake();
            logService.logEvent(EventSeverity.INFO, "CHAT", "Message edited by user='" + s.getUsername() + "' id=" + messageId);
            return OperationResult.success("Message edited successfully", updated.get());
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    @Override
    public OperationResult<MessagePage> getHistory(String sessionId, String roomId, int limit) {
        OperationResult<MessagePage> denied = checkRead(sessionId, roomId, limit, 0);
        if (denied != null) return denied;
        try {
            return OperationResult.success(store.latest(roomId, limit));
        } catch (StorageException e) {
            return storageFailure(e);
        }
    }

    @Override
    public OperationResult<MessagePage> getMessages(String sessionId, String roomId, long afterCursor, int limit) {
        OperationResult<MessagePage> denied = checkRead(sessionId, roomId, limit, afterCursor);
        if (denied != null) return denied;
        try {
            return OperationResult.success(store.after(roomId, afterCursor, limit));
        } catch (StorageException e) {
            return storageFailure(e);
        }
    }

    private <T> OperationResult<T> checkRead(String sessionId, String roomId, int limit, long cursor) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.roomId(roomId);
        if (e == null) e = Validation.pageSize(limit);
        if (e == null) e = Validation.cursor(cursor);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        if (!s.isMemberOf(roomId)) return fail(ErrorCodes.NOT_IN_ROOM, "Join room '" + roomId + "' before reading it");
        return null;
    }

    @Override
    public OperationResult<List<RoomSummaryDTO>> getRoomSummaries(String sessionId) {
        if (sessions.getUsable(sessionId) == null) return invalidSession(sessionId);
        try {
            List<RoomSummaryDTO> out = new java.util.ArrayList<>();
            for (ChatRoomDTO r : store.listRooms()) {
                out.add(new RoomSummaryDTO(r.getRoomId(), r.getRoomName(), r.getDescription(), sessions.memberCount(r.getRoomId()), -1, r.getCreatedAt()));
            }
            return OperationResult.success(out);
        } catch (StorageException e) {
            return storageFailure(e);
        }
    }

    @Override
    public OperationResult<Void> leaveRoom(String sessionId, String roomId) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.roomId(roomId);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        sessions.leave(s, roomId);
        return OperationResult.ok("Left " + roomId);
    }

    @Override
    public OperationResult<List<MemberDTO>> getRoomMembers(String sessionId, String roomId) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.roomId(roomId);
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        if (!s.isMemberOf(roomId)) return fail(ErrorCodes.NOT_IN_ROOM, "Join room '" + roomId + "' to see its members");
        return OperationResult.success(sessions.membersOf(roomId));
    }

    @Override
    public OperationResult<UserProfileDTO> getProfile(String sessionId) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        try {
            return OperationResult.success(store.touchProfile(s.getUsername().toLowerCase(), s.getUsername()));
        } catch (StorageException e) {
            return storageFailure(e);
        }
    }

    @Override
    public OperationResult<UserProfileDTO> updateProfile(String sessionId, String displayName, String email, String presence) {
        return updateProfile(sessionId, displayName, email, presence, null);
    }

    @Override
    public OperationResult<UserProfileDTO> updateProfile(String sessionId, String displayName, String email, String presence, String avatar) {
        Session s = sessions.getUsable(sessionId);
        if (s == null) return invalidSession(sessionId);
        String e = Validation.displayName(displayName);
        if (e == null) e = Validation.email(email == null ? "" : email.trim());
        if (e == null && avatar != null) e = Validation.avatar(avatar);
        PresenceStatus status = null;
        try {
            status = PresenceStatus.valueOf(presence == null ? "" : presence.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            if (e == null) e = "presence must be ONLINE, AWAY or BUSY";
        }
        if (e != null) return fail(ErrorCodes.VALIDATION, e);
        try {
            UserProfileDTO saved = store.updateProfile(s.getUsername().toLowerCase(), displayName.trim(), email == null ? "" : email.trim(), status, avatar);
            s.applyProfile(saved.getDisplayName(), saved.getStatus(), saved.getAvatar());
            return OperationResult.success("Profile saved", saved);
        } catch (StorageException ex) {
            return storageFailure(ex);
        }
    }

    @Override
    public OperationResult<Void> heartbeat(String sessionId) {
        return sessions.getUsable(sessionId) == null
                ? invalidSession(sessionId)
                : OperationResult.ok();
    }

    @Override
    public OperationResult<Void> disconnectClient(String sessionId) {
        if (sessionId != null) sessions.disconnect(sessionId);
        return OperationResult.ok();
    }

    @Override
    public String getServerId() {
        return config.getServerId();
    }
}
