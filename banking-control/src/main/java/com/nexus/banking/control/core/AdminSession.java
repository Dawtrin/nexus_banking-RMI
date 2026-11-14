package com.nexus.banking.control.core;

import com.nexus.banking.control.core.ClusterMonitor.NodeEndpoint;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Everything the pages share: endpoints, operator, token (memory only) and the latest snapshots. */
public final class AdminSession {
    private final ClusterMonitor monitor;
    private final Executor background;
    private volatile List<NodeEndpoint> nodes = List.of();
    private volatile String token = "";
    private volatile String operator = "";
    private volatile List<NodeSnapshot> latest = List.of();

    public AdminSession(ClusterMonitor.Connector connector) {
        this.background = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nexus-control-worker");
            t.setDaemon(true);
            return t;
        });
        this.monitor = new ClusterMonitor(connector, background);
    }

    public static NodeEndpoint parse(String label, String hostPort) {
        String[] p = hostPort.trim().split(":");
        int port;
        try {
            port = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1099;
        } catch (NumberFormatException e) {
            port = -1;
        }
        return new NodeEndpoint(label, p[0].trim(), port);
    }

    public void configure(String nodeA, String nodeB, String operator, String token) {
        this.nodes = new ArrayList<>(List.of(parse("Node A", nodeA), parse("Node B", nodeB)));
        this.operator = operator.trim();
        this.token = token;
    }

    public void updateNodes(String nodeA, String nodeB) {
        this.nodes = new ArrayList<>(List.of(parse("Node A", nodeA), parse("Node B", nodeB)));
    }

    public void setOperator(String operator) { this.operator = operator.trim(); }
    public ClusterMonitor monitor() { return monitor; }
    public Executor background() { return background; }
    public List<NodeEndpoint> nodes() { return nodes; }
    public String token() { return token; }
    public String operator() { return operator; }
    public List<NodeSnapshot> latest() { return latest; }
    public void setLatest(List<NodeSnapshot> snaps) { this.latest = snaps; }

    public void signOut() {
        token = "";
        latest = List.of();
    }
}
