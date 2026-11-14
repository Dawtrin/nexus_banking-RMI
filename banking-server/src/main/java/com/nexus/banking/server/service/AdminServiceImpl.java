package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserAdminDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.AdminRemote;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.service.ClientSessionManager.Session;
import com.nexus.banking.server.store.ChatStore;
import com.nexus.banking.server.store.StorageException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administrative actions of this node. Every call is token-authenticated, validated, executed against the real
 * store/session state and recorded in the persistent audit log (success and failure).
 */
public class AdminServiceImpl implements AdminRemote {
    private final ServerConfig config;
    private final AdminAuth auth;
    private final ChatStore store;
    private final ClientSessionManager sessions;
    private final PeerReplicationManager replication;
    private final AuditService audit;
    private final SystemLogService logService;

    public AdminServiceImpl(ServerConfig config, AdminAuth auth, ChatStore store, ClientSessionManager sessions,
                            PeerReplicationManager replication, AuditService audit, SystemLogService logService) {
        this.config = config;
        this.auth = auth;
        this.store = store;
        this.sessions = sessions;
        this.replication = replication;
        this.audit = audit;
        this.logService = logService;
    }

    private <T> OperationResult<T> storageFailure(String actor, String action, String target, StorageException e) {
        audit.record(actor, action, target, "FAILED", "storage: " + e.getMessage());
        logService.logEvent(EventSeverity.ERROR, "ADMIN", action + " failed: " + e.getMessage());
        return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Storage is unavailable; nothing was changed.");
    }

    @Override
    public OperationResult<List<RoomSummaryDTO>> listRooms(String adminToken) {
        OperationResult<List<RoomSummaryDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        try {
            List<RoomSummaryDTO> out = new ArrayList<>();
            for (ChatRoomDTO r : store.listRooms()) {
                out.add(new RoomSummaryDTO(r.getRoomId(), r.getRoomName(), r.getDescription(), sessions.memberCount(r.getRoomId()),
                        store.countMessages(r.getRoomId()), r.getCreatedAt()));
            }
            return OperationResult.success(out);
        } catch (StorageException e) {
            return storageFailure("-", "LIST_ROOMS", "rooms", e);
        }
    }

    @Override
    public OperationResult<Void> createRoom(String adminToken, String actor, String roomId, String roomName, String description) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.roomId(roomId);
        if (e == null) e = Validation.roomName(roomName);
        if (e == null) e = Validation.roomDescription(description);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            boolean created = store.createRoom(new ChatRoomDTO(roomId, roomName.trim(), description == null ? "" : description, Instant.now()));
            audit.record(actor, "CREATE_ROOM", roomId, created ? "OK" : "REJECTED", created ? roomName.trim() : "room already exists");
            return created ? OperationResult.ok("Room created") : OperationResult.failure(ErrorCodes.VALIDATION, "Room '" + roomId + "' already exists");
        } catch (StorageException ex) {
            return storageFailure(actor, "CREATE_ROOM", roomId, ex);
        }
    }

    @Override
    public OperationResult<Void> updateRoom(String adminToken, String actor, String roomId, String roomName, String description) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.roomId(roomId);
        if (e == null) e = Validation.roomName(roomName);
        if (e == null) e = Validation.roomDescription(description);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            boolean ok = store.updateRoom(roomId, roomName.trim(), description == null ? "" : description);
            audit.record(actor, "UPDATE_ROOM", roomId, ok ? "OK" : "REJECTED", ok ? roomName.trim() : "room not found");
            return ok ? OperationResult.ok("Room updated") : OperationResult.failure(ErrorCodes.ROOM_NOT_FOUND, "Room '" + roomId + "' does not exist");
        } catch (StorageException ex) {
            return storageFailure(actor, "UPDATE_ROOM", roomId, ex);
        }
    }

    @Override
    public OperationResult<Void> deleteRoom(String adminToken, String actor, String roomId) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.roomId(roomId);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            ChatStore.DeleteOutcome out = store.deleteRoom(roomId);
            audit.record(actor, "DELETE_ROOM", roomId, out == ChatStore.DeleteOutcome.DELETED ? "OK" : "REJECTED", out.name());
            return switch (out) {
                case DELETED -> {
                    sessions.dropRoom(roomId);
                    yield OperationResult.ok("Room deleted");
                }
                case NOT_FOUND -> OperationResult.failure(ErrorCodes.ROOM_NOT_FOUND, "Room '" + roomId + "' does not exist");
                case NOT_EMPTY -> OperationResult.failure(ErrorCodes.VALIDATION, "Room '" + roomId + "' contains messages and cannot be deleted");
            };
        } catch (StorageException ex) {
            return storageFailure(actor, "DELETE_ROOM", roomId, ex);
        }
    }

    @Override
    public OperationResult<List<MemberDTO>> getRoomMembers(String adminToken, String roomId) {
        OperationResult<List<MemberDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.roomId(roomId);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        return OperationResult.success(sessions.membersOf(roomId));
    }

    @Override
    public OperationResult<List<UserAdminDTO>> listUsers(String adminToken) {
        OperationResult<List<UserAdminDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        try {
            List<UserAdminDTO> out = new ArrayList<>();
            for (UserProfileDTO p : store.listProfiles()) {
                out.add(new UserAdminDTO(p.getUsername(), p.getDisplayName(), p.getStatus(), sessions.liveSessionsOf(p.getUsername()), p.getLastSeenAt()));
            }
            return OperationResult.success(out);
        } catch (StorageException ex) {
            return storageFailure("-", "LIST_USERS", "users", ex);
        }
    }

    @Override
    public OperationResult<Void> disconnectSession(String adminToken, String actor, String sessionId) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e == null) e = Validation.sessionIdFormat(sessionId);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        Session s = sessions.findAny(sessionId);
        if (s == null) {
            audit.record(actor, "DISCONNECT_SESSION", sessionId, "REJECTED", "session not found");
            return OperationResult.failure(ErrorCodes.INVALID_SESSION, "Session not found");
        }
        sessions.disconnect(sessionId);
        audit.record(actor, "DISCONNECT_SESSION", sessionId, "OK", "user " + s.getUsername());
        return OperationResult.ok("Session disconnected");
    }

    @Override
    public OperationResult<List<AuditEntryDTO>> getAuditLog(String adminToken, int limit) {
        OperationResult<List<AuditEntryDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        if (limit < 1 || limit > 500) return OperationResult.failure(ErrorCodes.VALIDATION, "limit must be between 1 and 500");
        try {
            return OperationResult.success(audit.recent(limit));
        } catch (StorageException ex) {
            return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Audit log is unavailable: storage error");
        }
    }

    @Override
    public OperationResult<Integer> requeueDeadLetters(String adminToken, String actor) {
        OperationResult<Integer> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        try {
            int n = replication.requeueDead();
            audit.record(actor, "REQUEUE_DEAD_LETTERS", config.getPeerId(), "OK", n + " message(s)");
            return OperationResult.success(n);
        } catch (StorageException ex) {
            return storageFailure(actor, "REQUEUE_DEAD_LETTERS", config.getPeerId(), ex);
        }
    }

    @Override
    public OperationResult<Void> syncNow(String adminToken, String actor) {
        OperationResult<Void> denied = auth.check(adminToken);
        if (denied != null) return denied;
        String e = Validation.actor(actor);
        if (e != null) return OperationResult.failure(ErrorCodes.VALIDATION, e);
        replication.wake();
        audit.record(actor, "SYNC_NOW", config.getPeerId(), "OK", "replication cycle requested");
        return OperationResult.ok("Replication cycle requested");
    }

    @Override
    public OperationResult<Map<String, String>> getServerConfig(String adminToken) {
        OperationResult<Map<String, String>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("server.id", config.getServerId());
        m.put("server.advertised.host", config.getAdvertisedHost());
        m.put("server.rmi.registry.port", String.valueOf(config.getRmiRegistryPort()));
        m.put("server.rmi.service.port", String.valueOf(config.getRmiServicePort()));
        m.put("server.peer.id", config.getPeerId());
        m.put("server.peer.host", config.getPeerHost());
        m.put("server.peer.registry.port", String.valueOf(config.getPeerRegistryPort()));
        m.put("database.enabled", String.valueOf(config.getDatabaseConfig().isEnabled()));
        m.put("database.host", config.getDatabaseConfig().getHost());
        m.put("database.port", String.valueOf(config.getDatabaseConfig().getPort()));
        m.put("database.name", config.getDatabaseConfig().getDatabaseName());
        m.put("database.schema.auto", String.valueOf(config.isSchemaAutoApply()));
        m.put("replication.batch.size", String.valueOf(config.getReplicationBatchSize()));
        m.put("replication.poll.millis", String.valueOf(config.getReplicationPollMillis()));
        m.put("replication.catchup.page", String.valueOf(config.getCatchUpPageSize()));
        m.put("replication.max.rejected.attempts", String.valueOf(config.getMaxRejectedAttempts()));
        m.put("admin.api", config.getAdminToken() == null ? "disabled" : "enabled");
        return OperationResult.success(m);
    }
}
