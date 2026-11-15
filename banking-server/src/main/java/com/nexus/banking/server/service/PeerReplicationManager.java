package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.enums.PeerSyncStatus;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.contract.remote.BankingReplicationService;
import com.nexus.banking.contract.rmi.RmiSocketFactories;
import com.nexus.banking.contract.rmi.TimeoutClientSocketFactory;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.rmi.RmiRegistryManager;
import com.nexus.banking.server.store.BankingStore;
import com.nexus.banking.server.store.IngestResult;
import com.nexus.banking.server.store.OutboxEntry;
import com.nexus.banking.server.store.OutboxStats;
import com.nexus.banking.server.store.StorageException;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends outbox ledger entries to the peer branch and pulls missed history from it (catch-up).
 *
 * <ul>
 *   <li>Push: outbox rows are sent in order; an entry is marked delivered only for transaction ids the peer
 *       lists in {@code acceptedTransactionIds} (peer committed them durably). Peer rejections are counted per
 *       entry with exponential backoff and end in DEAD (visible, requeueable) - they never block others.</li>
 *   <li>Pull (catch-up): from the persisted inbound checkpoint, page by page until the peer has nothing
 *       newer; runs on start, after every recovery, while incomplete, and periodically as reconciliation.</li>
 *   <li>Peer unreachable: exponential backoff with jitter on the connection only; items are not penalized.</li>
 * </ul>
 */
public class PeerReplicationManager implements AutoCloseable {
    /** Opens a (possibly remote) replication endpoint of the peer. */
    @FunctionalInterface
    public interface PeerConnector {
        BankingReplicationService connect() throws Exception;
    }

    private static final long RECONCILE_INTERVAL_MILLIS = 10_000;
    private static final int MAX_PAGES_PER_TICK = 50;
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final ServerConfig config;
    private final BankingStore store;
    private final ServerMetrics metrics;
    private final SystemLogService logService;
    private final PeerConnector connector;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);

    private BankingReplicationService peer;
    private volatile ServerStatus peerStatus = ServerStatus.UNKNOWN;
    private volatile PeerSyncStatus syncStatus = PeerSyncStatus.UNKNOWN;
    private volatile String lastError = "";
    private volatile Instant lastSuccessAt;
    private volatile Instant lastCheckedAt;
    private volatile int consecutiveFailures = 0;
    private volatile Instant nextPeerAttemptAt = Instant.EPOCH;
    private volatile boolean needCatchUp = true;
    private volatile IngestPostProcessor postProcessor;
    private volatile Instant lastCatchUpAt = Instant.EPOCH;

    public PeerReplicationManager(ServerConfig config, BankingStore store, ServerMetrics metrics,
                                  SystemLogService logService, PeerConnector connector, boolean scheduled) {
        this.config = config;
        this.store = store;
        this.metrics = metrics;
        this.logService = logService;
        this.connector = connector;
        if (scheduled) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nexus-replication");
                t.setDaemon(true);
                return t;
            });
        } else {
            executor = null;
        }
    }

    /** Default connector: the peer's RMI registry, with bounded connect/read timeouts. */
    public static PeerConnector rmiConnector(ServerConfig config) {
        return () -> {
            Registry reg = LocateRegistry.getRegistry(config.getPeerHost(), config.getPeerRegistryPort(),
                    RmiSocketFactories.clientFactory(config.isPeerSslEnabled()));
            return (BankingReplicationService) reg.lookup(RmiRegistryManager.REPLICATION_SERVICE_NAME);
        };
    }

    public void setPostProcessor(IngestPostProcessor p) {
        this.postProcessor = p;
    }

    public void start() {
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::runOnce, 500, config.getReplicationPollMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Evicts any session of the account on the peer branch over RMI (cluster-wide single session). */
    public void evictPeerSession(String accountNumber, String exceptClientInstanceId, String reason) {
        Runnable task = () -> {
            try {
                BankingReplicationService remote = peer();
                if (remote != null) {
                    remote.evictAccountSession(config.getServerId(), config.getReplicationSecret(), accountNumber,
                            exceptClientInstanceId, reason);
                }
            } catch (Exception e) {
                peer = null;
                logService.logEvent(EventSeverity.WARN, "REPLICATION",
                        "Could not notify peer of session eviction: " + e.getMessage());
            }
        };
        if (executor != null && !executor.isShutdown()) {
            executor.execute(task);
        } else {
            java.util.concurrent.ForkJoinPool.commonPool().execute(task);
        }
    }

    /** Called after a local commit so delivery does not wait for the next poll. */
    public void wake() {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(this::runOnce);
        }
    }

    /** One replication cycle. Safe to call concurrently (extra calls are coalesced). */
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        try {
            do {
                rerun.set(false);
                cycle();
            } while (rerun.get());
        } catch (RuntimeException e) {
            lastError = "internal error: " + e;
            logService.logEvent(EventSeverity.ERROR, "REPLICATION", "Unexpected replication error: " + e);
        } finally {
            running.set(false);
        }
    }

    private void cycle() {
        Instant now = Instant.now();
        if (now.isBefore(nextPeerAttemptAt)) return;
        lastCheckedAt = now;
        try {
            BankingReplicationService remote = peer();
            OperationResult<?> ping = remote.getPeerStatus(config.getServerId(), config.getReplicationSecret());
            if (!ping.isSuccess()) {
                onPeerProblem(ServerStatus.DEGRADED, "peer rejected status request: " + ping.getErrorCode() + " " + ping.getMessage(), false);
                return;
            }
            if (peerStatus != ServerStatus.ONLINE) {
                logService.logEvent(EventSeverity.INFO, "REPLICATION", "Peer '" + config.getPeerId() + "' is reachable");
                needCatchUp = true;
            }
            peerStatus = ServerStatus.ONLINE;
            consecutiveFailures = 0;
            if (needCatchUp || now.isAfter(lastCatchUpAt.plusMillis(RECONCILE_INTERVAL_MILLIS))) {
                catchUp(remote);
            }
            drainOutbox(remote);
            if (peerStatus == ServerStatus.ONLINE) {
                OutboxStats stats = store.outboxStats(config.getPeerId());
                syncStatus = (stats.pending() == 0 && !needCatchUp) ? PeerSyncStatus.SYNCED : PeerSyncStatus.IN_PROGRESS;
                lastError = stats.dead() > 0 ? stats.dead() + " entr(ies) dead-lettered; requeue from NEXUS CONTROL" : "";
                recordState(syncStatus, null, false);
            }
        } catch (StorageException e) {
            // Local storage problem: not the peer's fault, do not back the peer off.
            lastError = "local storage error: " + e.getMessage();
            syncStatus = PeerSyncStatus.FAILED;
            metrics.incrementReplicationErrors();
            logService.logEvent(EventSeverity.ERROR, "REPLICATION", lastError);
        } catch (Exception e) {
            onPeerProblem(ServerStatus.UNREACHABLE, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private BankingReplicationService peer() throws Exception {
        if (peer == null) peer = connector.connect();
        return peer;
    }

    private void onPeerProblem(ServerStatus status, String error, boolean dropStub) {
        if (dropStub) peer = null;
        if (peerStatus == ServerStatus.ONLINE) {
            logService.logEvent(EventSeverity.WARN, "REPLICATION", "Peer '" + config.getPeerId() + "' became " + status + ": " + error);
        }
        peerStatus = status;
        syncStatus = PeerSyncStatus.FAILED;
        lastError = error;
        metrics.incrementReplicationErrors();
        consecutiveFailures++;
        long delay = Math.min(config.getBackoffMaxMillis(),
                config.getBackoffBaseMillis() * (1L << Math.min(consecutiveFailures - 1, 20)));
        long jitter = (long) (delay * 0.2 * ThreadLocalRandom.current().nextDouble());
        nextPeerAttemptAt = Instant.now().plusMillis(delay + jitter);
        needCatchUp = true;
        try {
            recordState(PeerSyncStatus.FAILED, error, false);
        } catch (StorageException ignored) {
            // already degraded; the error is kept in memory above
        }
    }

    private void recordState(PeerSyncStatus status, String error, boolean success) throws StorageException {
        store.recordSyncState(config.getPeerId(), status, error, success);
    }

    // ---- pull ----

    private void catchUp(BankingReplicationService remote) throws Exception {
        int pages = 0;
        while (pages++ < MAX_PAGES_PER_TICK) {
            long checkpoint = store.getInboundCheckpoint(config.getPeerId());
            OperationResult<List<TransactionDTO>> res = remote.getTransactionsAfter(
                    config.getServerId(), config.getReplicationSecret(), checkpoint, config.getCatchUpPageSize());
            if (!res.isSuccess()) {
                onPeerProblem(ServerStatus.DEGRADED, "catch-up refused: " + res.getErrorCode() + " " + res.getMessage(), false);
                return;
            }
            List<TransactionDTO> page = res.getData() == null ? List.of() : res.getData();
            if (page.isEmpty()) {
                needCatchUp = false;
                lastCatchUpAt = Instant.now();
                return;
            }
            List<TransactionDTO> valid = new ArrayList<>();
            for (TransactionDTO m : page) {
                String problem = Validation.replicatedEntry(m, config.getPeerId(), config.getServerId());
                if (problem == null) valid.add(m);
                else {
                    metrics.addRejectedEntries(1);
                    logService.logEvent(EventSeverity.WARN, "REPLICATION", "Ignored invalid ledger entry from peer during catch-up: " + problem);
                }
            }
            IngestResult r = store.ingestReplicated(config.getPeerId(), valid);
            if (postProcessor != null) postProcessor.handle(r);
            else metrics.addEntriesReplicatedIn(r.applied().size());
            long after = store.getInboundCheckpoint(config.getPeerId());
            long maxSeqInPage = page.stream().mapToLong(TransactionDTO::getOriginSequence).max().orElse(checkpoint);
            if (after <= checkpoint && maxSeqInPage > checkpoint) {
                // Nothing in the page could be stored (all invalid/rejected): stop instead of looping forever.
                logService.logEvent(EventSeverity.ERROR, "REPLICATION", "Catch-up made no progress at origin sequence " + checkpoint
                        + "; " + r.rejected().size() + " rejected. Check peer data.");
                lastCatchUpAt = Instant.now();
                needCatchUp = false;
                return;
            }
            lastSuccessAt = Instant.now();
            if (page.size() < config.getCatchUpPageSize()) {
                needCatchUp = false;
                lastCatchUpAt = Instant.now();
                return;
            }
        }
        needCatchUp = true; // more pages remain; continue next cycle
    }

    // ---- push ----

    private void drainOutbox(BankingReplicationService remote) throws Exception {
        for (int batchNo = 0; batchNo < MAX_BATCHES_PER_TICK; batchNo++) {
            List<OutboxEntry> due = store.dueOutbox(config.getPeerId(), Instant.now(), config.getReplicationBatchSize());
            if (due.isEmpty()) return;
            List<TransactionDTO> payload = new ArrayList<>();
            for (OutboxEntry e : due) payload.add(e.entry());
            OperationResult<ReplicationAck> res = remote.replicateBatch(config.getServerId(), config.getReplicationSecret(), payload);
            if (!res.isSuccess() || res.getData() == null) {
                // Whole batch refused/failed transiently (e.g. peer storage down): keep everything pending, back off.
                onPeerProblem(ServerStatus.DEGRADED, "peer could not store batch: " + res.getErrorCode() + " " + res.getMessage(), false);
                return;
            }
            ReplicationAck ack = res.getData();
            Set<String> accepted = new HashSet<>(ack.getAcceptedTransactionIds());
            Map<String, String> rejected = ack.getRejected();
            List<Long> delivered = new ArrayList<>();
            boolean progress = false;
            for (OutboxEntry e : due) {
                String id = e.entry().getTransactionId();
                if (accepted.contains(id)) {
                    delivered.add(e.outboxId());
                } else if (rejected.containsKey(id)) {
                    boolean dead = store.rejectOutbox(e.outboxId(), rejected.get(id), Instant.now(),
                            config.getBackoffBaseMillis(), config.getBackoffMaxMillis(), config.getMaxRejectedAttempts());
                    progress = true;
                    if (dead) {
                        logService.logEvent(EventSeverity.ERROR, "REPLICATION", "Ledger entry " + id + " dead-lettered after "
                                + config.getMaxRejectedAttempts() + " rejections: " + rejected.get(id));
                    }
                }
                // not mentioned in the ack: stays PENDING and is retried
            }
            store.markDelivered(delivered);
            metrics.addEntriesReplicatedOut(delivered.size());
            metrics.addRejectedEntries(rejected.size());
            if (!delivered.isEmpty()) {
                lastSuccessAt = Instant.now();
                progress = true;
            }
            if (!progress) return;
        }
    }

    // ---- admin / status ----

    public ServerStatus getPeerStatus() {
        return peerStatus;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public ReplicationStatusDTO getStatus() {
        long pending = -1, dead = -1, checkpoint = -1;
        Instant oldest = null;
        String err = lastError;
        try {
            OutboxStats s = store.outboxStats(config.getPeerId());
            pending = s.pending();
            dead = s.dead();
            oldest = s.oldestPendingAt();
            checkpoint = store.getInboundCheckpoint(config.getPeerId());
        } catch (StorageException e) {
            err = "storage unavailable: " + e.getMessage();
        }
        return new ReplicationStatusDTO(config.getServerId(), config.getPeerId(),
                config.getPeerHost() + ":" + config.getPeerRegistryPort(), peerStatus, syncStatus, pending, dead,
                oldest, lastSuccessAt, lastCheckedAt, err, checkpoint, consecutiveFailures);
    }

    public int requeueDead() throws StorageException {
        int n = store.requeueDead(config.getPeerId(), Instant.now());
        if (n > 0) {
            logService.logEvent(EventSeverity.INFO, "REPLICATION", "Requeued " + n + " dead-lettered entr(ies)");
            wake();
        }
        return n;
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }
}
