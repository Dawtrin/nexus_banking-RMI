package com.nexus.banking.server.support;

import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.remote.BankingReplicationService;
import com.nexus.banking.server.config.DatabaseConfig;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.service.AdminAuth;
import com.nexus.banking.server.service.AdminServiceImpl;
import com.nexus.banking.server.service.AuditService;
import com.nexus.banking.server.service.BankingServiceImpl;
import com.nexus.banking.server.service.CallbackNotifier;
import com.nexus.banking.server.service.ClientSessionManager;
import com.nexus.banking.server.service.IngestPostProcessor;
import com.nexus.banking.server.service.LoginThrottle;
import com.nexus.banking.server.service.MonitoringServiceImpl;
import com.nexus.banking.server.service.PeerReplicationManager;
import com.nexus.banking.server.service.ReplicationServiceImpl;
import com.nexus.banking.server.service.StatusBuilder;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.InMemoryBankingStore;
import com.nexus.banking.server.store.JdbcBankingStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** A complete in-JVM branch node (no RMI) for deterministic tests. */
public final class Node implements AutoCloseable {
    public static final String SECRET = "test-replication-secret-0123456789";
    public static final String ADMIN_TOKEN = "test-admin-token-123456";
    public static final String PIN = "123456";

    public static final String AN = "1001000001";      // 50,000,000, limit 20,000,000
    public static final String BINH = "1001000002";    // 25,000,000
    public static final String CUONG = "2002000001";   // 80,000,000, limit 30,000,000
    public static final String EM = "3003000001";      // 5,000,000, limit 5,000,000
    public static final String LOCKED = "4004000001";

    /** Records every push a device would receive. */
    public static final class RecordingCallback implements BankingCallback {
        public final List<BalanceNotificationDTO> balances = new CopyOnWriteArrayList<>();
        public final List<String> evictions = new CopyOnWriteArrayList<>();
        public final List<String> statuses = new CopyOnWriteArrayList<>();

        @Override public void onBalanceChanged(BalanceNotificationDTO n) { balances.add(n); }
        @Override public void onSessionEvicted(String reason) { evictions.add(reason); }
        @Override public void onAccountStatusChanged(String account, AccountStatus status, String message) { statuses.add(account + ":" + status); }
        @Override public void ping() { }
    }

    public final ServerConfig config;
    public final DatabaseManager db;
    public final BankingStore store;
    public final ClientSessionManager sessions;
    public final SystemLogService logs;
    public final ServerMetrics metrics = new ServerMetrics();
    public final PeerReplicationManager replication;
    public final BankingServiceImpl bank;
    public final ReplicationServiceImpl repl;
    public final MonitoringServiceImpl monitoring;
    public final AdminServiceImpl admin;
    public final AuditService audit;
    public final StatusBuilder status;
    public final CallbackNotifier notifier;
    public final LoginThrottle throttle = new LoginThrottle();
    /** The peer endpoint this node talks to; null simulates an unreachable peer (network cut). */
    public final AtomicReference<BankingReplicationService> link = new AtomicReference<>();

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
        this.store = dbCfg.isEnabled() ? new JdbcBankingStore(db) : new InMemoryBankingStore();
        this.logs = new SystemLogService(id);
        this.sessions = new ClientSessionManager(id, logs, false);
        this.notifier = new CallbackNotifier(id, sessions, logs, false);
        this.replication = new PeerReplicationManager(config, store, metrics, logs, () -> {
            BankingReplicationService r = link.get();
            if (r == null) throw new IOException("peer offline (test)");
            return r;
        }, false);
        this.status = new StatusBuilder(config, store, db, sessions, replication, metrics);
        AdminAuth adminAuth = new AdminAuth(config.getAdminToken(), logs);
        this.audit = new AuditService(id, store, logs);
        adminAuth.setAudit(audit);
        IngestPostProcessor post = new IngestPostProcessor(metrics, audit, notifier);
        this.replication.setPostProcessor(post);
        this.bank = new BankingServiceImpl(config, store, sessions, replication, metrics, logs, notifier, audit, throttle);
        this.repl = new ReplicationServiceImpl(config, store, metrics, logs, status, sessions);
        this.repl.setPostProcessor(post);
        this.monitoring = new MonitoringServiceImpl(adminAuth, status, sessions, logs, db, replication, metrics);
        this.admin = new AdminServiceImpl(config, adminAuth, store, sessions, replication, audit, logs, notifier);
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

    /** Connects two nodes directly (replication calls go straight to the other node's service). */
    public static void connect(Node a, Node b) {
        a.link.set(b.repl);
        b.link.set(a.repl);
    }

    /** Logs the account in with the demo PIN; returns the session id. */
    public String login(String account) {
        return login(account, "device-" + account + "-0001", null);
    }

    public String login(String account, String deviceId, BankingCallback cb) {
        OperationResult<LoginResultDTO> r = bank.login(account, PIN, deviceId, cb);
        if (!r.isSuccess()) throw new IllegalStateException(r.toString());
        return r.getData().getSession().getSessionId();
    }

    public BigDecimal balance(String account) {
        try {
            return store.findAccount(account).orElseThrow().getBalance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String req() {
        return UUID.randomUUID().toString();
    }

    public static BigDecimal m(long v) {
        return BigDecimal.valueOf(v).setScale(2);
    }

    /** Runs replication cycles until nothing more happens (bounded). */
    public void sync() {
        for (int i = 0; i < 3; i++) replication.runOnce();
    }

    public List<String> auditActions() {
        List<String> out = new ArrayList<>();
        try {
            store.recentAudit(500).forEach(a -> out.add(a.getAction()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Override
    public void close() {
        notifier.close();
        sessions.close();
        replication.close();
        db.close();
    }
}
