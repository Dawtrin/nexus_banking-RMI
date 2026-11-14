package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.store.ChatStore;

import java.net.InetAddress;

/** Builds the node status from live state only; unknown values stay UNKNOWN / -1, never a hardcoded "ONLINE". */
public class StatusBuilder {
    private final ServerConfig config;
    private final ChatStore store;
    private final DatabaseManager db;
    private final ClientSessionManager sessions;
    private final PeerReplicationManager replication;
    private final ServerMetrics metrics;

    public StatusBuilder(ServerConfig config, ChatStore store, DatabaseManager db, ClientSessionManager sessions,
                         PeerReplicationManager replication, ServerMetrics metrics) {
        this.config = config;
        this.store = store;
        this.db = db;
        this.sessions = sessions;
        this.replication = replication;
        this.metrics = metrics;
    }

    public ServerStatusDTO build() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        ServerStatus dbStatus = !db.isEnabled() ? ServerStatus.UNKNOWN
                : db.isConnected() ? ServerStatus.ONLINE : ServerStatus.UNREACHABLE;
        // The node can serve requests, but is DEGRADED when it is configured for durability and the DB is down.
        ServerStatus self = (db.isEnabled() && !db.isConnected()) ? ServerStatus.DEGRADED : ServerStatus.ONLINE;
        ReplicationStatusDTO rs = replication.getStatus();
        return new ServerStatusDTO(config.getServerId(), hostname, config.getAdvertisedHost(),
                "all interfaces (no explicit bind address)", config.getAdvertisedHost(),
                config.getRmiRegistryPort(), config.getRmiServicePort(), self, replication.getPeerStatus(), dbStatus,
                metrics.getUptimeSeconds(), metrics.getStartedAt(), sessions.activeCount(),
                (int) Math.max(0, Math.min(Integer.MAX_VALUE, rs.getPendingCount())),
                replication.getLastSuccessAt(), store.modeLabel());
    }
}
