package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.DatabaseHealthDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.dto.ServerEventDTO;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.ThroughputPointDTO;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.contract.remote.BankingMonitoringService;
import com.nexus.banking.server.database.DatabaseManager;
import com.nexus.banking.server.monitoring.SystemLogService;

import java.util.List;

/** Admin monitoring API: every method requires the configured admin token. */
public class MonitoringServiceImpl implements BankingMonitoringService {
    private final AdminAuth auth;
    private final StatusBuilder statusBuilder;
    private final ClientSessionManager sessions;
    private final SystemLogService logService;
    private final DatabaseManager db;
    private final PeerReplicationManager replication;
    private final ServerMetrics metrics;

    public MonitoringServiceImpl(AdminAuth auth, StatusBuilder statusBuilder, ClientSessionManager sessions,
                                 SystemLogService logService, DatabaseManager db, PeerReplicationManager replication,
                                 ServerMetrics metrics) {
        this.auth = auth;
        this.statusBuilder = statusBuilder;
        this.sessions = sessions;
        this.logService = logService;
        this.db = db;
        this.replication = replication;
        this.metrics = metrics;
    }

    @Override
    public OperationResult<ServerStatusDTO> getServerStatus(String adminToken) {
        OperationResult<ServerStatusDTO> denied = auth.check(adminToken);
        return denied != null ? denied : OperationResult.success(statusBuilder.build());
    }

    @Override
    public OperationResult<List<ClientSessionDTO>> getActiveSessions(String adminToken) {
        OperationResult<List<ClientSessionDTO>> denied = auth.check(adminToken);
        return denied != null ? denied : OperationResult.success(sessions.listLive());
    }

    @Override
    public OperationResult<List<ServerEventDTO>> getRecentEvents(String adminToken, int limit) {
        OperationResult<List<ServerEventDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        if (limit < 1 || limit > 500) return OperationResult.failure(ErrorCodes.VALIDATION, "limit must be between 1 and 500");
        return OperationResult.success(logService.getRecentEvents(limit));
    }

    @Override
    public OperationResult<DatabaseHealthDTO> getDatabaseHealth(String adminToken) {
        OperationResult<DatabaseHealthDTO> denied = auth.check(adminToken);
        return denied != null ? denied : OperationResult.success(db.getHealth());
    }

    @Override
    public OperationResult<ReplicationStatusDTO> getReplicationStatus(String adminToken) {
        OperationResult<ReplicationStatusDTO> denied = auth.check(adminToken);
        return denied != null ? denied : OperationResult.success(replication.getStatus());
    }

    @Override
    public OperationResult<List<ThroughputPointDTO>> getThroughput(String adminToken, int minutes) {
        OperationResult<List<ThroughputPointDTO>> denied = auth.check(adminToken);
        if (denied != null) return denied;
        if (minutes < 1 || minutes > 60) return OperationResult.failure(ErrorCodes.VALIDATION, "minutes must be between 1 and 60");
        return OperationResult.success(metrics.getRecorder().series(minutes, System.currentTimeMillis()));
    }
}
