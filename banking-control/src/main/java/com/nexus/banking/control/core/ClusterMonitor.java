package com.nexus.banking.control.core;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.DatabaseHealthDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.dto.ServerEventDTO;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.ThroughputPointDTO;
import com.nexus.banking.contract.remote.AdminRemote;
import com.nexus.banking.contract.remote.MonitoringRemote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queries every configured node with the admin token and reports what is really known. A failed query
 * yields an error snapshot; if an earlier query succeeded its data is kept but marked {@code stale}, so the
 * dashboard can never present old values as current/healthy. Only one refresh runs at a time.
 */
public final class ClusterMonitor {

    public record NodeEndpoint(String label, String host, int port) {
        public String address() {
            return host + ":" + port;
        }
    }

    /** The two remote services of one node. */
    public record NodeRemotes(MonitoringRemote monitoring, AdminRemote admin) {
    }

    @FunctionalInterface
    public interface Connector {
        NodeRemotes connect(String host, int port) throws Exception;
    }

    /** {@code error != null} means the node could not be queried right now. */
    public record NodeSnapshot(NodeEndpoint endpoint, Instant fetchedAt, String errorCode, String error, String warnings,
                               boolean stale, Instant lastGoodAt, ServerStatusDTO status, ReplicationStatusDTO replication,
                               DatabaseHealthDTO database, List<ClientSessionDTO> sessions, List<ServerEventDTO> events,
                               List<ThroughputPointDTO> throughput) {
        public boolean ok() {
            return error == null;
        }
    }

    private final Connector connector;
    private final Executor executor;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Map<String, NodeSnapshot> lastGood = new HashMap<>();

    public ClusterMonitor(Connector connector, Executor executor) {
        this.connector = connector;
        this.executor = executor;
    }

    public boolean isRefreshing() {
        return refreshing.get();
    }

    /** Empty when a refresh is already running (the caller should simply wait for it). */
    public Optional<CompletableFuture<List<NodeSnapshot>>> refresh(List<NodeEndpoint> nodes, String adminToken) {
        if (!refreshing.compareAndSet(false, true)) return Optional.empty();
        List<CompletableFuture<NodeSnapshot>> parts = new ArrayList<>();
        for (NodeEndpoint n : nodes) parts.add(CompletableFuture.supplyAsync(() -> query(n, adminToken), executor));
        CompletableFuture<List<NodeSnapshot>> all = CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .handle((v, t) -> parts.stream().map(CompletableFuture::join).toList());
        all.whenComplete((r, t) -> refreshing.set(false));
        return Optional.of(all);
    }

    /** A remote call that may throw (RMI). */
    @FunctionalInterface
    public interface NodeCall<T> {
        OperationResult<T> call(NodeRemotes remotes) throws Exception;
    }

    /** Runs one call against one node; an unreachable node yields an UNREACHABLE failure, never an exception. */
    public <T> OperationResult<T> onNode(NodeEndpoint node, NodeCall<T> call) {
        try {
            return call.call(connector.connect(node.host(), node.port()));
        } catch (Exception e) {
            return OperationResult.failure("UNREACHABLE", describe(e));
        }
    }

    /** Runs the call on every node in parallel; the map is keyed by node label in the given order. */
    public <T> Map<String, OperationResult<T>> onAllNodes(List<NodeEndpoint> nodes, NodeCall<T> call) {
        List<CompletableFuture<OperationResult<T>>> parts = new ArrayList<>();
        for (NodeEndpoint n : nodes) parts.add(CompletableFuture.supplyAsync(() -> onNode(n, call), executor));
        Map<String, OperationResult<T>> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) out.put(nodes.get(i).label(), parts.get(i).join());
        return out;
    }

    private NodeSnapshot query(NodeEndpoint n, String token) {
        Instant now = Instant.now();
        try {
            NodeRemotes remotes = connector.connect(n.host(), n.port());
            MonitoringRemote mon = remotes.monitoring();
            OperationResult<ServerStatusDTO> st = mon.getServerStatus(token);
            if (!st.isSuccess()) return failure(n, now, st.getErrorCode(), st.getMessage());
            StringBuilder warn = new StringBuilder();
            ReplicationStatusDTO repl = unwrap(mon.getReplicationStatus(token), "replication", warn);
            DatabaseHealthDTO db = unwrap(mon.getDatabaseHealth(token), "database", warn);
            List<ClientSessionDTO> sessions = unwrap(mon.getActiveSessions(token), "sessions", warn);
            List<ServerEventDTO> events = unwrap(mon.getRecentEvents(token, 100), "events", warn);
            List<ThroughputPointDTO> throughput = unwrap(mon.getThroughput(token, 30), "throughput", warn);
            NodeSnapshot ok = new NodeSnapshot(n, now, null, null, warn.length() == 0 ? null : warn.toString(), false, now,
                    st.getData(), repl, db, sessions == null ? List.of() : sessions, events == null ? List.of() : events,
                    throughput == null ? List.of() : throughput);
            synchronized (lastGood) {
                lastGood.put(n.label(), ok);
            }
            return ok;
        } catch (Exception e) {
            return failure(n, now, "UNREACHABLE", describe(e));
        }
    }

    private static <T> T unwrap(OperationResult<T> r, String what, StringBuilder warn) {
        if (r.isSuccess()) return r.getData();
        warn.append(what).append(": ").append(r.getErrorCode()).append("; ");
        return null;
    }

    private NodeSnapshot failure(NodeEndpoint n, Instant now, String code, String message) {
        NodeSnapshot prev;
        synchronized (lastGood) {
            prev = lastGood.get(n.label());
        }
        if (prev == null) return new NodeSnapshot(n, now, code, message, null, false, null, null, null, null, List.of(), List.of(), List.of());
        return new NodeSnapshot(n, now, code, message, null, true, prev.lastGoodAt(), prev.status(), prev.replication(),
                prev.database(), prev.sessions(), prev.events(), prev.throughput());
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return m == null || m.isBlank() ? root.getClass().getSimpleName() : m;
    }
}
