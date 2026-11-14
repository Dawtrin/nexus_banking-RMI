package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.enums.PresenceStatus;
import com.nexus.banking.contract.enums.SessionStatus;
import com.nexus.banking.server.monitoring.SystemLogService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory session registry. Sessions are ephemeral: they do not survive a server restart (clients must
 * register again, which the desktop client's reconnect logic does). A DISCONNECTED session can never be
 * revived; only ACTIVE/IDLE/STALE sessions are usable, and a valid call refreshes them.
 */
public class ClientSessionManager implements AutoCloseable {
    public static final int MAX_SESSIONS = 2000;
    static final long STALE_AFTER_SECONDS = 30;
    static final long DISCONNECT_AFTER_SECONDS = 60;
    static final long PURGE_AFTER_SECONDS = 600;

    /** Mutable server-side session. */
    public static final class Session {
        private final String sessionId = UUID.randomUUID().toString();
        private final String username;
        private final String senderId;
        private final String clientInstanceId;
        private final String remoteIp;
        private final Instant connectedAt = Instant.now();
        private final Set<String> rooms = ConcurrentHashMap.newKeySet();
        private volatile Instant lastSeenAt = connectedAt;
        private volatile SessionStatus status = SessionStatus.ACTIVE;
        private volatile String displayName;
        private volatile PresenceStatus presence = PresenceStatus.ONLINE;
        private volatile String avatar = "";
        private volatile boolean evicted = false;
        private volatile String disconnectReason = "";

        Session(String username, String clientInstanceId, String remoteIp) {
            this.username = username;
            this.clientInstanceId = clientInstanceId;
            this.remoteIp = remoteIp;
            this.senderId = senderIdFor(username, clientInstanceId);
            this.displayName = username;
        }

        public String getSessionId() { return sessionId; }
        public String getUsername() { return username; }
        public String getSenderId() { return senderId; }
        public boolean isMemberOf(String roomId) { return rooms.contains(roomId); }
        public String getDisplayName() { return displayName; }
        public PresenceStatus getPresence() { return presence; }
        public String getAvatar() { return avatar == null ? "" : avatar; }
        public boolean isEvicted() { return evicted; }
        public String getDisconnectReason() { return disconnectReason; }
        public void evict(String reason) {
            this.evicted = true;
            this.disconnectReason = reason != null ? reason : "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.";
            this.status = SessionStatus.DISCONNECTED;
            this.rooms.clear();
        }
        public void applyProfile(String displayName, PresenceStatus presence) {
            applyProfile(displayName, presence, this.avatar);
        }
        public void applyProfile(String displayName, PresenceStatus presence, String avatar) {
            this.displayName = displayName;
            this.presence = presence;
            this.avatar = avatar == null ? "" : avatar;
        }
    }

    /** Stable sender identity for one client installation: survives reconnects, distinguishes equal usernames. */
    public static String senderIdFor(String username, String clientInstanceId) {
        return username.toLowerCase() + "#" + clientInstanceId;
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final String serverId;
    private final SystemLogService logService;
    private final ScheduledExecutorService reaper;

    public ClientSessionManager(String serverId, SystemLogService logService, boolean startReaper) {
        this.serverId = serverId;
        this.logService = logService;
        if (startReaper) {
            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nexus-session-reaper");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleWithFixedDelay(() -> reap(Instant.now()), 10, 10, TimeUnit.SECONDS);
        } else {
            reaper = null;
        }
    }

    /** Returns null when the registry is full. */
    public Session register(String username, String clientInstanceId, String remoteIp, String displayName, PresenceStatus presence) {
        return register(username, clientInstanceId, remoteIp, displayName, presence, "");
    }

    public Session register(String username, String clientInstanceId, String remoteIp, String displayName, PresenceStatus presence, String avatar) {
        if (sessions.size() >= MAX_SESSIONS) {
            reap(Instant.now());
            if (sessions.size() >= MAX_SESSIONS) return null;
        }
        Session s = new Session(username, clientInstanceId, remoteIp);
        s.applyProfile(displayName == null ? username : displayName, presence == null ? PresenceStatus.ONLINE : presence, avatar);
        sessions.put(s.sessionId, s);
        logService.logEvent(EventSeverity.INFO, "SESSION", "Client connected: user='" + username + "' ip=" + remoteIp);
        return s;
    }

    /** Evicts any active session for the given username. Returns the number of sessions evicted. */
    public int evictByUsername(String username, String reason) {
        if (username == null || username.isBlank()) return 0;
        int count = 0;
        String normalized = username.trim().toLowerCase();
        for (Session s : sessions.values()) {
            if (s.status != SessionStatus.DISCONNECTED && s.username.trim().toLowerCase().equals(normalized)) {
                s.evict(reason);
                count++;
                logService.logEvent(EventSeverity.WARN, "SESSION",
                        "Evicted session for user='" + username + "' sessionId=" + s.sessionId + " reason: " + reason);
            }
        }
        return count;
    }

    /** The session if it is usable (and refreshes it); null if unknown or DISCONNECTED. */
    public Session getUsable(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null || s.status == SessionStatus.DISCONNECTED) return null;
        s.lastSeenAt = Instant.now();
        s.status = SessionStatus.ACTIVE;
        return s;
    }

    public void join(Session s, String roomId) {
        s.rooms.add(roomId);
    }

    public void leave(Session s, String roomId) {
        s.rooms.remove(roomId);
    }

    /** Removes every member from a room that no longer exists. */
    public void dropRoom(String roomId) {
        for (Session s : sessions.values()) s.rooms.remove(roomId);
    }

    /** Distinct users (one entry per username) currently joined to the room on this node. */
    public List<MemberDTO> membersOf(String roomId) {
        Map<String, MemberDTO> byUser = new java.util.LinkedHashMap<>();
        for (Session s : sessions.values()) {
            if (s.status != SessionStatus.DISCONNECTED && s.rooms.contains(roomId)) {
                byUser.putIfAbsent(s.username.toLowerCase(), new MemberDTO(s.username, s.displayName, s.presence, serverId, s.connectedAt, s.getAvatar()));
            }
        }
        List<MemberDTO> out = new ArrayList<>(byUser.values());
        out.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        return out;
    }

    public int memberCount(String roomId) {
        return membersOf(roomId).size();
    }

    public int liveSessionsOf(String usernameKey) {
        return (int) sessions.values().stream()
                .filter(s -> s.status != SessionStatus.DISCONNECTED && s.username.equalsIgnoreCase(usernameKey)).count();
    }

    public Session findAny(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public void disconnect(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s != null && s.status != SessionStatus.DISCONNECTED) {
            s.status = SessionStatus.DISCONNECTED;
            s.rooms.clear();
            logService.logEvent(EventSeverity.INFO, "SESSION", "Client disconnected: user='" + s.username + "'");
        }
    }

    /** Applies stale/disconnect/purge thresholds; package-visible for deterministic tests. */
    void reap(Instant now) {
        for (Session s : new ArrayList<>(sessions.values())) {
            long idle = Duration.between(s.lastSeenAt, now).getSeconds();
            if (s.status == SessionStatus.DISCONNECTED) {
                if (idle > PURGE_AFTER_SECONDS) sessions.remove(s.sessionId);
            } else if (idle > DISCONNECT_AFTER_SECONDS) {
                s.status = SessionStatus.DISCONNECTED;
                s.rooms.clear();
                logService.logEvent(EventSeverity.WARN, "SESSION", "Session timed out: user='" + s.username + "'");
            } else if (idle > STALE_AFTER_SECONDS) {
                s.status = SessionStatus.STALE;
            }
        }
    }

    /** Test hook: backdate a session's last-seen time. */
    void backdate(String sessionId, Instant lastSeen) {
        Session s = sessions.get(sessionId);
        if (s != null) s.lastSeenAt = lastSeen;
    }

    public int activeCount() {
        return (int) sessions.values().stream().filter(s -> s.status != SessionStatus.DISCONNECTED).count();
    }

    public List<ClientSessionDTO> listLive() {
        List<ClientSessionDTO> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (s.status != SessionStatus.DISCONNECTED) out.add(toDto(s));
        }
        out.sort((a, b) -> a.getConnectedAt().compareTo(b.getConnectedAt()));
        return out;
    }

    public ClientSessionDTO toDto(Session s) {
        return new ClientSessionDTO(s.sessionId, s.username, s.clientInstanceId, s.remoteIp, 0, s.connectedAt,
                s.lastSeenAt, s.status, serverId);
    }

    @Override
    public void close() {
        if (reaper != null) reaper.shutdownNow();
    }
}
