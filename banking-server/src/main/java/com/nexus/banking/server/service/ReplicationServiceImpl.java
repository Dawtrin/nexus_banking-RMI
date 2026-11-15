package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.BankingReplicationService;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.IngestResult;
import com.nexus.banking.server.store.StorageException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Receiving side of replication. Every call must present the configured peer id and shared secret.
 * The ack lists a message as accepted only after it is committed in this node's store.
 */
public class ReplicationServiceImpl implements BankingReplicationService {
    private static final int MAX_BATCH = 500;

    private final ServerConfig config;
    private final BankingStore store;
    private final ServerMetrics metrics;
    private final SystemLogService logService;
    private final StatusBuilder statusBuilder;
    private final ClientSessionManager sessions;
    private IngestPostProcessor postProcessor;
    private final AtomicLong authFailures = new AtomicLong();

    public ReplicationServiceImpl(ServerConfig config, BankingStore store, ServerMetrics metrics,
                                  SystemLogService logService, StatusBuilder statusBuilder) {
        this(config, store, metrics, logService, statusBuilder, null);
    }

    public ReplicationServiceImpl(ServerConfig config, BankingStore store, ServerMetrics metrics,
                                  SystemLogService logService, StatusBuilder statusBuilder,
                                  ClientSessionManager sessions) {
        this.config = config;
        this.store = store;
        this.metrics = metrics;
        this.logService = logService;
        this.statusBuilder = statusBuilder;
        this.sessions = sessions;
    }

    public void setPostProcessor(IngestPostProcessor p) {
        this.postProcessor = p;
    }

    private boolean authorized(String caller, String secret) {
        boolean ok = caller != null && secret != null
                && caller.equals(config.getPeerId())
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                config.getReplicationSecret().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            long n = authFailures.incrementAndGet();
            if (n <= 20 || n % 100 == 0) {
                logService.logEvent(EventSeverity.WARN, "REPLICATION", "Rejected unauthenticated replication call from '"
                        + SystemLogService.sanitize(caller) + "' (failure #" + n + ")");
            }
        }
        return ok;
    }

    @Override
    public OperationResult<ReplicationAck> replicateBatch(String sourceServerId, String replicationSecret,
                                                          List<TransactionDTO> entries) {
        if (!authorized(sourceServerId, replicationSecret)) {
            return OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Replication caller not authorized");
        }
        if (entries == null || entries.size() > MAX_BATCH) {
            return OperationResult.failure(ErrorCodes.VALIDATION, "batch must contain at most " + MAX_BATCH + " entries");
        }
        List<TransactionDTO> valid = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        for (TransactionDTO m : entries) {
            String problem = Validation.replicatedEntry(m, sourceServerId, config.getServerId());
            if (problem == null) valid.add(m);
            else rejected.put(m == null || m.getTransactionId() == null ? "<invalid>" : m.getTransactionId(), problem);
        }
        try {
            IngestResult r = store.ingestReplicated(sourceServerId, valid);
            rejected.putAll(r.rejected());
            if (postProcessor != null) postProcessor.handle(r);
            else metrics.addEntriesReplicatedIn(r.applied().size());
            if (!rejected.isEmpty()) {
                metrics.addRejectedEntries(rejected.size());
                logService.logEvent(EventSeverity.WARN, "REPLICATION", rejected.size() + " replicated entr(ies) rejected");
            }
            return OperationResult.success(new ReplicationAck(r.accepted(), rejected));
        } catch (StorageException e) {
            logService.logEvent(EventSeverity.ERROR, "REPLICATION", "Could not store replicated batch: " + e.getMessage());
            return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "Receiver storage unavailable; nothing was stored");
        }
    }

    @Override
    public OperationResult<List<TransactionDTO>> getTransactionsAfter(String requesterServerId, String replicationSecret,
                                                                  long afterOriginSequence, int limit) {
        if (!authorized(requesterServerId, replicationSecret)) {
            return OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Replication caller not authorized");
        }
        if (afterOriginSequence < 0 || limit < 1 || limit > MAX_BATCH) {
            return OperationResult.failure(ErrorCodes.VALIDATION, "invalid cursor or limit");
        }
        try {
            return OperationResult.success(store.findByOriginAfter(config.getServerId(), afterOriginSequence, limit));
        } catch (StorageException e) {
            return OperationResult.failure(ErrorCodes.STORAGE_UNAVAILABLE, "storage unavailable");
        }
    }

    @Override
    public OperationResult<ServerStatusDTO> getPeerStatus(String requesterServerId, String replicationSecret) {
        if (!authorized(requesterServerId, replicationSecret)) {
            return OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Replication caller not authorized");
        }
        return OperationResult.success(statusBuilder.build());
    }

    @Override
    public OperationResult<Integer> evictAccountSession(String requesterServerId, String replicationSecret,
                                                        String accountNumber, String exceptClientInstanceId, String reason) {
        if (!authorized(requesterServerId, replicationSecret)) {
            return OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Replication caller not authorized");
        }
        if (sessions == null || accountNumber == null || accountNumber.isBlank()) {
            return OperationResult.success(0);
        }
        int evicted = sessions.evictByAccount(accountNumber, exceptClientInstanceId, reason).size();
        if (evicted > 0) {
            logService.logEvent(EventSeverity.INFO, "SESSION",
                    "Evicted " + evicted + " session(s) of account=" + accountNumber + " via peer request from " + requesterServerId);
        }
        return OperationResult.success(evicted);
    }
}
