package com.nexus.banking.server.support;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.remote.ReplicationRemote;
import com.nexus.banking.server.config.DatabaseConfig;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.service.AdminAuth;
import com.nexus.banking.server.service.ChatServiceImpl;
import com.nexus.banking.server.service.ClientSessionManager;
import com.nexus.banking.server.service.MonitoringServiceImpl;
import com.nexus.banking.server.service.PeerReplicationManager;
import com.nexus.banking.server.service.ReplicationServiceImpl;
import com.nexus.banking.server.service.StatusBuilder;
import com.nexus.banking.server.store.ChatStore;
import com.nexus.banking.server.store.InMemoryChatStore;
import com.nexus.banking.server.store.JdbcChatStore;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** A complete in-JVM server node (no RMI) for deterministic tests. */
public final class Node implements AutoCloseable {
    public static final String SECRET = "test-replication-secret-0123456789";
    public static final String ADMIN_TOKEN = "test-admin-token-123456";

    public final ServerConfig config;
    public final DatabaseManager db;
    public final ChatStore store;
    public final ClientSessionManager sessions;
    public final SystemLogService logs;
    public final ServerMetrics metrics = new ServerMetrics();
    public final PeerReplicationManager replication;
    public final ChatServiceImpl chat;
    public final ReplicationServiceImpl repl;
    public final MonitoringServiceImpl monitoring;
    public final com.nexus.banking.server.service.AdminServiceImpl admin;
    public final com.nexus.banking.server.service.AuditService audit;
    public final StatusBuilder status;
    /** The peer endpoint this node talks to; null simulates an unreachable peer. */
    public final AtomicReference<ReplicationRemote> link = new AtomicReference<>();

    private Node(String id, String peer, DatabaseConfig dbCfg, Map<String, String> overrides) {
        Map<String, String> cli = new HashMap<>();
        cli.put("server.id", id);
        cli.put("server.peer.id", peer);
        cli.put("server.replication.secret", SECRET);
        cli.put("server.admin.token", ADMIN_TOKEN);
        cli.put("database.enabled", String.valueOf(dbCfg.isEnabled()));
        cli.put("replication.backoff.base.millis", "10");
        cli.put("replication.backoff.max.millis", "100");
        cli.put("replication.max.rejected.attempts", "3");
        cli.put("replication.catchup.page", "100");
        cli.put("replication.batch.size", "50");
        cli.putAll(overrides);
        this.config = new ServerConfig(cli, Map.of(), Map.of(), new Properties());
        this.db = new DatabaseManager(dbCfg, true);
        this.store = dbCfg.isEnabled() ? new JdbcChatStore(db) : new InMemoryChatStore();
        this.logs = new SystemLogService(id);
        this.sessions = new ClientSessionManager(id, logs, false);
        this.replication = new PeerReplicationManager(config, store, metrics, logs, () -> {
            ReplicationRemote r = link.get();
            if (r == null) throw new IOException("peer offline (test)");
            return r;
        }, false);
        this.status = new StatusBuilder(config, store, db, sessions, replication, metrics);
        this.chat = new ChatServiceImpl(config, store, sessions, replication, metrics, logs);
        this.repl = new ReplicationServiceImpl(config, store, metrics, logs, status, sessions);
        AdminAuth adminAuth = new AdminAuth(config.getAdminToken(), logs);
        this.audit = new com.nexus.banking.server.service.AuditService(id, store, logs);
        adminAuth.setAudit(audit);
        this.monitoring = new MonitoringServiceImpl(adminAuth, status, sessions, logs, db, replication, metrics);
        this.admin = new com.nexus.banking.server.service.AdminServiceImpl(config, adminAuth, store, sessions, replication, audit, logs);
    }

    public static Node ram(String id, String peer) {
        return new Node(id, peer, TestDb.disabled(), Map.of());
    }

    public static Node ram(String id, String peer, Map<String, String> overrides) {
        return new Node(id, peer, TestDb.disabled(), overrides);
    }

    public static Node jdbc(String id, String peer, DatabaseConfig cfg) {
        return new Node(id, peer, cfg, Map.of());
    }

    /** Registers a user and joins "general"; returns the session id. */
    public String login(String user) {
        return login(user, "instance-" + user + "-0001", "general");
    }

    public String login(String user, String instanceId, String room) {
        OperationResult<ClientSessionDTO> r = chat.registerClient(user, instanceId);
        if (!r.isSuccess()) throw new IllegalStateException(r.toString());
        OperationResult<Void> j = chat.joinRoom(r.getData().getSessionId(), room);
        if (!j.isSuccess()) throw new IllegalStateException(j.toString());
        return r.getData().getSessionId();
    }

    public void send(String session, String text) {
        OperationResult<?> r = chat.sendMessage(session, "general", java.util.UUID.randomUUID().toString(), text);
        if (!r.isSuccess()) throw new IllegalStateException(r.toString());
    }

    @Override
    public void close() {
        sessions.close();
        replication.close();
        db.close();
    }
}
