package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.enums.SessionStatus;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.server.monitoring.SystemLogService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * In-memory registry of customer sessions on this node. Sessions are ephemeral (they do not survive a restart; the app
 * logs in again). A DISCONNECTED session can never be revived; only ACTIVE/IDLE/STALE sessions are usable.
 * Single-session rule: {@link #evictByAccount} ends every other session of an account; a hook lets the caller push
 * the reason to the evicted device immediately.
 */
public class ClientSessionManager implements AutoCloseable {
    public static final int MAX_SESSIONS = 2000;
    static final long STALE_AFTER_SECONDS = 30;
    static final long DISCONNECT_AFTER_SECONDS = 60;
    static final long PURGE_AFTER_SECONDS = 600;

    public static final String DEFAULT_EVICTION_REASON = "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.";

    /** Mutable server-side session. */
    public static final class Session {
        private final String sessionId = UUID.randomUUID().toString();
        private final String accountNumber;
        private final String ownerName;
        private final String clientInstanceId;
        private final String remoteIp;
        private final Instant connectedAt = Instant.now();
        private volatile Instant lastSeenAt = connectedAt;
        private volatile SessionStatus status = SessionStatus.ACTIVE;
        private volatile boolean evicted = false;
        private volatile String disconnectReason = "";
        private volatile BankingCallback callback;
        private final SerialLane lane;

        Session(String accountNumber, String ownerName, String clientInstanceId, String remoteIp, BankingCallback callback,
                SerialLane lane) {
            this.accountNumber = accountNumber;
            this.ownerName = ownerName;
            this.clientInstanceId = clientInstanceId;
            this.remoteIp = remoteIp;
            this.callback = callback;
            this.lane = lane;
        }

        public String getSessionId() { return sessionId; }
        public String getAccountNumber() { return accountNumber; }
        public String getOwnerName() { return ownerName; }
        public String getClientInstanceId() { return clientInstanceId; }
        public String getRemoteIp() { return remoteIp; }
        public boolean isEvicted() { return evicted; }
        public String getDisconnectReason() { return disconnectReason; }
        public BankingCallback getCallback() { return callback; }
        public void dropCallback() { this.callback = null; }
        SerialLane lane() { return lane; }

        void evict(String reason) {
            this.evicted = true;
            this.disconnectReason = reason != null && !reason.isBlank() ? reason : DEFAULT_EVICTION_REASON;
            this.status = SessionStatus.DISCONNECTED;
        }
    }

    /** Ordered execution lane per session, so pushes to one device are delivered in order. */
    public interface SerialLane {
        void submit(Runnable r);
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final String serverId;
    private final SystemLogService logService;
    private final ScheduledExecutorService reaper;
    private volatile BiConsumer<Session, String> evictionListener = (s, r) -> { };
    private volatile java.util.function.Supplier<SerialLane> laneFactory = () -> Runnable::run;

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

    public void setEvictionListener(BiConsumer<Session, String> listener) {
        this.evictionListener = listener == null ? (s, r) -> { } : listener;
    }

    public void setLaneFactory(java.util.function.Supplier<SerialLane> factory) {
        this.laneFactory = factory;
    }

    /** Returns null when the registry is full. */
    public Session register(String accountNumber, String ownerName, String clientInstanceId, String remoteIp,
                            BankingCallback callback) {
        if (sessions.size() >= MAX_SESSIONS) {
            reap(Instant.now());
            if (sessions.size() >= MAX_SESSIONS) return null;
        }
        Session s = new Session(accountNumber, ownerName, clientInstanceId, remoteIp, callback, laneFactory.get());
        sessions.put(s.sessionId, s);
        logService.logEvent(EventSeverity.INFO, "SESSION", "Customer logged in: account=" + accountNumber + " ip=" + remoteIp);
        return s;
    }

    /**
     * Ends every live session of the account except those of {@code exceptClientInstanceId} (the same installation
     * reconnecting is not "another device"; its stale session is simply replaced without a warning).
     * Returns the sessions that were ended because of ANOTHER device (the ones that deserve a push).
     */
    public List<Session> evictByAccount(String accountNumber, String exceptClientInstanceId, String reason) {
        List<Session> evicted = new ArrayList<>();
        if (accountNumber == null || accountNumber.isBlank()) return evicted;
        for (Session s : sessions.values()) {
            if (s.status == SessionStatus.DISCONNECTED || !s.accountNumber.equals(accountNumber)) continue;
            if (exceptClientInstanceId != null && exceptClientInstanceId.equals(s.clientInstanceId)) {
                s.status = SessionStatus.DISCONNECTED;     // same device: silently superseded
                continue;
            }
            s.evict(reason);
            evicted.add(s);
            logService.logEvent(EventSeverity.WARN, "SESSION", "Evicted session of account=" + accountNumber
                    + " sessionId=" + s.sessionId + " reason: " + s.disconnectReason);
        }
        for (Session s : evicted) evictionListener.accept(s, s.disconnectReason);
        return evicted;
    }

    /** The session if usable (and refreshes it); null if unknown or DISCONNECTED. */
    public Session getUsable(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null || s.status == SessionStatus.DISCONNECTED) return null;
        s.lastSeenAt = Instant.now();
        s.status = SessionStatus.ACTIVE;
        return s;
    }

    public Session findAny(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /** Live sessions of an account on this node (used for balance pushes). */
    public List<Session> liveSessionsOf(String accountNumber) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (s.status != SessionStatus.DISCONNECTED && s.accountNumber.equals(accountNumber)) out.add(s);
        }
        return out;
    }

    public int liveSessionCount(String accountNumber) {
        return liveSessionsOf(accountNumber).size();
    }

    public void disconnect(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s != null && s.status != SessionStatus.DISCONNECTED) {
            s.status = SessionStatus.DISCONNECTED;
            logService.logEvent(EventSeverity.INFO, "SESSION", "Customer logged out: account=" + s.accountNumber);
        }
    }

    /** Operator action: ends one session and tells the device why. */
    public boolean forceDisconnect(String sessionId, String reason) {
        Session s = sessions.get(sessionId);
        if (s == null || s.status == SessionStatus.DISCONNECTED) return false;
        s.evict(reason);
        logService.logEvent(EventSeverity.WARN, "SESSION", "Session of account=" + s.accountNumber + " ended by operator");
        evictionListener.accept(s, s.disconnectReason);
        return true;
    }

    /** Applies stale/disconnect/purge thresholds; package-visible for deterministic tests. */
    void reap(Instant now) {
        for (Session s : new ArrayList<>(sessions.values())) {
            long idle = Duration.between(s.lastSeenAt, now).getSeconds();
            if (s.status == SessionStatus.DISCONNECTED) {
                if (idle > PURGE_AFTER_SECONDS) sessions.remove(s.sessionId);
            } else if (idle > DISCONNECT_AFTER_SECONDS) {
                s.status = SessionStatus.DISCONNECTED;
                logService.logEvent(EventSeverity.WARN, "SESSION", "Session timed out: account=" + s.accountNumber);
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
        return new ClientSessionDTO(s.sessionId, s.accountNumber, s.ownerName, s.clientInstanceId, s.remoteIp,
                s.connectedAt, s.lastSeenAt, s.status, serverId, s.callback != null);
    }

    @Override
    public void close() {
        if (reaper != null) reaper.shutdownNow();
    }
}
