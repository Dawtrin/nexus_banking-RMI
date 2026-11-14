package com.nexus.banking.control;

import com.nexus.banking.control.core.ClusterMonitor;
import com.nexus.banking.control.core.ClusterMonitor.NodeEndpoint;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.DatabaseHealthDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.dto.ServerEventDTO;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.ThroughputPointDTO;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.contract.remote.MonitoringRemote;
import org.junit.jupiter.api.Test;

import java.rmi.RemoteException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterMonitorTest {
    private static final NodeEndpoint A = new NodeEndpoint("Node A", "10.0.0.1", 1099);
    private static final NodeEndpoint B = new NodeEndpoint("Node B", "10.0.0.2", 1099);

    private static ServerStatusDTO status(String id) {
        return new ServerStatusDTO(id, "host-" + id, "10.0.0.1", "all", "10.0.0.1", 1099, 1100, ServerStatus.ONLINE,
                ServerStatus.ONLINE, ServerStatus.ONLINE, 5, Instant.now(), 2, 0, null, "MYSQL_DURABLE");
    }

    private static MonitoringRemote healthy(String id) {
        return new Stub() {
            @Override
            public OperationResult<ServerStatusDTO> getServerStatus(String t) {
                return t.equals("good-token") ? OperationResult.success(status(id)) : OperationResult.failure(ErrorCodes.UNAUTHORIZED, "Invalid admin token");
            }
        };
    }

    private abstract static class Stub implements MonitoringRemote {
        @Override
        public OperationResult<List<ClientSessionDTO>> getActiveSessions(String t) {
            return OperationResult.success(List.of());
        }

        @Override
        public OperationResult<List<ServerEventDTO>> getRecentEvents(String t, int l) {
            return OperationResult.success(List.of());
        }

        @Override
        public OperationResult<DatabaseHealthDTO> getDatabaseHealth(String t) {
            return OperationResult.success(new DatabaseHealthDTO("db", true, ServerStatus.ONLINE, 1, 1, 2, "", Instant.now(), "MYSQL_DURABLE"));
        }

        @Override
        public OperationResult<ReplicationStatusDTO> getReplicationStatus(String t) {
            return OperationResult.success(new ReplicationStatusDTO("a", "b", "10.0.0.2:1099", ServerStatus.ONLINE, null, 0, 0, null, null, null, "", 0, 0));
        }

        @Override
        public OperationResult<List<ThroughputPointDTO>> getThroughput(String t, int minutes) {
            return OperationResult.success(List.of(new ThroughputPointDTO(Instant.now(), 10, 4, 0, 0)));
        }
    }

    private static ClusterMonitor.NodeRemotes remotes(MonitoringRemote m) {
        return new ClusterMonitor.NodeRemotes(m, null);
    }

    @Test
    void healthyNodesAreReportedWithRealData() throws Exception {
        ClusterMonitor m = new ClusterMonitor((h, p) -> remotes(healthy(h.equals("10.0.0.1") ? "server-a" : "server-b")), Runnable::run);
        List<NodeSnapshot> snaps = m.refresh(List.of(A, B), "good-token").orElseThrow().get();
        assertThat(snaps).allMatch(NodeSnapshot::ok);
        assertThat(snaps.get(0).status().getServerId()).isEqualTo("server-a");
        assertThat(snaps.get(1).status().getServerId()).isEqualTo("server-b");
    }

    @Test
    void wrongTokenIsAnExplicitErrorNotAHealthyNode() throws Exception {
        ClusterMonitor m = new ClusterMonitor((h, p) -> remotes(healthy("server-a")), Runnable::run);
        NodeSnapshot s = m.refresh(List.of(A), "bad").orElseThrow().get().get(0);
        assertThat(s.ok()).isFalse();
        assertThat(s.errorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(s.status()).isNull();
    }

    @Test
    void oneUnreachableNodeDoesNotHideTheOther() throws Exception {
        ClusterMonitor m = new ClusterMonitor((h, p) -> {
            if (h.equals("10.0.0.2")) throw new RemoteException("connection refused");
            return remotes(healthy("server-a"));
        }, Runnable::run);
        List<NodeSnapshot> snaps = m.refresh(List.of(A, B), "good-token").orElseThrow().get();
        assertThat(snaps.get(0).ok()).isTrue();
        assertThat(snaps.get(1).ok()).isFalse();
        assertThat(snaps.get(1).errorCode()).isEqualTo("UNREACHABLE");
        assertThat(snaps.get(1).error()).contains("connection refused");
    }

    @Test
    void dataFromAnEarlierSuccessIsMarkedStaleAfterAFailure() throws Exception {
        boolean[] down = {false};
        ClusterMonitor m = new ClusterMonitor((h, p) -> {
            if (down[0]) throw new RemoteException("timeout");
            return remotes(healthy("server-a"));
        }, Runnable::run);
        NodeSnapshot good = m.refresh(List.of(A), "good-token").orElseThrow().get().get(0);
        down[0] = true;
        NodeSnapshot bad = m.refresh(List.of(A), "good-token").orElseThrow().get().get(0);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.stale()).isTrue();
        assertThat(bad.lastGoodAt()).isEqualTo(good.lastGoodAt());
        assertThat(bad.status()).isNotNull();   // last known values stay visible, flagged as stale
    }

    @Test
    void refreshesNeverOverlap() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ClusterMonitor m = new ClusterMonitor((h, p) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return remotes(healthy("server-a"));
        }, r -> new Thread(r).start());
        CompletableFuture<List<NodeSnapshot>> first = m.refresh(List.of(A), "good-token").orElseThrow();
        assertThat(m.isRefreshing()).isTrue();
        assertThat(m.refresh(List.of(A), "good-token")).isEmpty();    // skipped while one is in flight
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        Thread.sleep(50);
        assertThat(m.isRefreshing()).isFalse();
        assertThat(m.refresh(List.of(A), "good-token")).isPresent();
    }

    @Test
    void callsRunOnEveryNodeAndReportUnreachableOnesAsFailures() {
        ClusterMonitor m = new ClusterMonitor((h, p) -> {
            if (h.equals("10.0.0.2")) throw new RemoteException("down");
            return remotes(healthy("server-a"));
        }, Runnable::run);
        var results = m.onAllNodes(List.of(A, B), r -> r.monitoring().getServerStatus("good-token"));
        assertThat(results.keySet()).containsExactly("Node A", "Node B");
        assertThat(results.get("Node A").isSuccess()).isTrue();
        assertThat(results.get("Node B").isSuccess()).isFalse();
        assertThat(results.get("Node B").getErrorCode()).isEqualTo("UNREACHABLE");
    }

    @Test
    void throughputIsPartOfTheSnapshot() throws Exception {
        ClusterMonitor m = new ClusterMonitor((h, p) -> remotes(healthy("server-a")), Runnable::run);
        NodeSnapshot s = m.refresh(List.of(A), "good-token").orElseThrow().get().get(0);
        assertThat(s.throughput()).hasSize(1);
    }
}
