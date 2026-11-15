package com.nexus.banking.server;

import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.server.config.ConfigException;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.rmi.RmiRegistryManager;
import com.nexus.banking.server.service.AdminAuth;
import com.nexus.banking.server.service.AdminServiceImpl;
import com.nexus.banking.server.service.AuditService;
import com.nexus.banking.server.service.BankingServiceImpl;
import com.nexus.banking.server.service.CallbackNotifier;
import com.nexus.banking.server.service.IngestPostProcessor;
import com.nexus.banking.server.service.LoginThrottle;
import com.nexus.banking.server.service.ClientSessionManager;
import com.nexus.banking.server.service.MonitoringServiceImpl;
import com.nexus.banking.server.service.PeerReplicationManager;
import com.nexus.banking.server.service.ReplicationServiceImpl;
import com.nexus.banking.server.service.StatusBuilder;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.InMemoryBankingStore;
import com.nexus.banking.server.store.JdbcBankingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/** NEXUS Banking branch-node entry point. Exits with status 2 on invalid configuration. */
public class ServerApplication {
    private static final Logger log = LoggerFactory.getLogger(ServerApplication.class);

    public static void main(String[] args) throws Exception {
        com.nexus.banking.contract.rmi.RmiTimeouts.install();
        ServerConfig config;
        try {
            config = new ServerConfig(args);
        } catch (ConfigException e) {
            System.err.println("CONFIGURATION ERROR: " + e.getMessage());
            System.exit(2);
            return;
        }

        ServerMetrics metrics = new ServerMetrics();
        SystemLogService logService = new SystemLogService(config.getServerId());
        DatabaseManager db = new DatabaseManager(config.getDatabaseConfig(), config.isSchemaAutoApply());
        BankingStore store = db.isEnabled() ? new JdbcBankingStore(db) : new InMemoryBankingStore();
        ClientSessionManager sessions = new ClientSessionManager(config.getServerId(), logService, true);
        PeerReplicationManager replication = new PeerReplicationManager(config, store, metrics, logService,
                PeerReplicationManager.rmiConnector(config), true);
        StatusBuilder statusBuilder = new StatusBuilder(config, store, db, sessions, replication, metrics);

        CallbackNotifier notifier = new CallbackNotifier(config.getServerId(), sessions, logService);
        AdminAuth adminAuth = new AdminAuth(config.getAdminToken(), logService);
        AuditService audit = new AuditService(config.getServerId(), store, logService);
        adminAuth.setAudit(audit);
        IngestPostProcessor postProcessor = new IngestPostProcessor(metrics, audit, notifier);
        replication.setPostProcessor(postProcessor);
        BankingServiceImpl banking = new BankingServiceImpl(config, store, sessions, replication, metrics, logService,
                notifier, audit, new LoginThrottle());
        ReplicationServiceImpl replicationService = new ReplicationServiceImpl(config, store, metrics, logService, statusBuilder, sessions);
        replicationService.setPostProcessor(postProcessor);
        MonitoringServiceImpl monitoring = new MonitoringServiceImpl(adminAuth, statusBuilder, sessions, logService, db, replication, metrics);
        AdminServiceImpl adminService = new AdminServiceImpl(config, adminAuth, store, sessions, replication, audit, logService, notifier);

        RmiRegistryManager rmi = new RmiRegistryManager(config);
        rmi.start();
        rmi.exportAndBind(RmiRegistryManager.BANKING_SERVICE_NAME, banking, config.getRmiServicePort());
        rmi.exportAndBind(RmiRegistryManager.REPLICATION_SERVICE_NAME, replicationService, config.getRmiServicePort());
        rmi.exportAndBind(RmiRegistryManager.MONITORING_SERVICE_NAME, monitoring, config.getRmiServicePort());
        rmi.exportAndBind(RmiRegistryManager.ADMIN_SERVICE_NAME, adminService, config.getRmiServicePort());
        replication.start();

        logService.logEvent(EventSeverity.INFO, "SERVER", "Branch node '" + config.getServerId() + "' (" + config.getBranchName() + ") started: registry "
                + config.getRmiRegistryPort() + ", service port " + config.getRmiServicePort() + ", persistence "
                + store.modeLabel() + (store.isDurable() ? "" : " (data is lost on restart)"));
        if (config.isSslEnabled()) {
            log.info("RMI Transport: TLS/SSL ENCRYPTED (Client Auth: {})", config.isSslNeedClientAuth());
        } else {
            log.info("RMI Transport: PLAIN TCP (Unencrypted)");
        }
        if (!store.isDurable()) {
            log.warn("RAM-ONLY DEMO MODE: accounts and ledger are NOT persisted (database.enabled=false)");
        }

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            replication.close();
            notifier.close();
            sessions.close();
            rmi.close();
            db.close();
            stop.countDown();
        }, "nexus-shutdown"));
        stop.await();
    }
}
