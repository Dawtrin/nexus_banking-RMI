package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.enums.ServerStatus;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;

/** Live topology. Node colours and link lanes are computed from each node's own report; unknown stays grey. */
public class TopologyPage implements AdminPage {
    private final VBox root = new VBox();
    private final Pane holder = new Pane();
    private final Canvas canvas = new Canvas();
    private final Label summary = new Label();
    private List<NodeSnapshot> last = List.of();

    public TopologyPage(AdminSession session) {
        holder.getChildren().add(canvas);
        holder.widthProperty().addListener((o, a, b) -> draw());
        holder.heightProperty().addListener((o, a, b) -> draw());
        VBox.setVgrow(holder, Priority.ALWAYS);
        summary.getStyleClass().add("text-muted");
        summary.setWrapText(true);
        VBox body = new VBox(12, holder, summary);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Cluster topology", "How the two servers are connected right now"), body);
    }

    @Override
    public String title() { return "Cluster topology"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        last = snaps;
        draw();
        StringBuilder sb = new StringBuilder();
        for (NodeSnapshot s : snaps) {
            ReplicationStatusDTO r = s.replication();
            sb.append(s.endpoint().label()).append(": ");
            if (r == null) sb.append(s.ok() ? "no replication report" : "unavailable - " + s.error());
            else {
                sb.append("last push or pull success ").append(Ui.time(r.getLastSuccessAt())).append(", state ").append(r.getSyncStatus());
                if (r.getLastError() != null && !r.getLastError().isBlank()) sb.append(", last error: ").append(r.getLastError());
            }
            sb.append("\n");
        }
        summary.setText(sb.toString().trim());
    }

    private static Color nodeColor(NodeSnapshot s) {
        if (s == null || s.status() == null) return Color.web("#71717A");
        if (!s.ok()) return Color.web("#FA174A");
        ServerStatus st = s.status().getServerStatus();
        return st == ServerStatus.ONLINE ? Color.web("#22C55E") : st == ServerStatus.DEGRADED ? Color.web("#F59E0B") : Color.web("#FA174A");
    }

    private void draw() {
        double w = holder.getWidth(), h = holder.getHeight();
        if (w < 200 || h < 150) return;
        canvas.setWidth(w);
        canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        NodeSnapshot a = last.size() > 0 ? last.get(0) : null;
        NodeSnapshot b = last.size() > 1 ? last.get(1) : null;
        double bw = Math.min(260, w * 0.28), bh = 150;
        double ax = 40, bx = w - 40 - bw, y = h / 2 - bh / 2;
        node(g, a, "Node A", ax, y, bw, bh);
        node(g, b, "Node B", bx, y, bw, bh);
        lane(g, a, ax + bw, y + bh * 0.33, bx, true);
        lane(g, b, ax + bw, y + bh * 0.70, bx, false);
    }

    private void node(GraphicsContext g, NodeSnapshot s, String label, double x, double y, double w, double h) {
        Color c = nodeColor(s);
        g.setFill(Color.web("#1C1C24"));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setStroke(c);
        g.setLineWidth(2);
        g.strokeRoundRect(x, y, w, h, 8, 8);
        g.setFill(Color.web("#F4F4F6"));
        g.setFont(Font.font("System", FontWeight.BOLD, 16));
        g.fillText(label, x + 16, y + 28);
        g.setFont(Font.font("System", 12));
        g.setFill(Color.web("#A1A1AA"));
        g.fillText(s == null ? "" : s.endpoint().address(), x + 16, y + 48);
        g.setFill(c);
        String state = s == null || s.status() == null ? "No data yet" : !s.ok() ? "Unavailable" : s.status().getServerStatus().name().charAt(0) + s.status().getServerStatus().name().substring(1).toLowerCase();
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.fillText(state + (s != null && s.stale() ? " (last known)" : ""), x + 16, y + 74);
        g.setFill(Color.web("#A1A1AA"));
        g.setFont(Font.font("System", 12));
        if (s != null && s.status() != null) {
            g.fillText("Storage: " + s.status().getPersistenceMode(), x + 16, y + 98);
            g.fillText("People signed in: " + s.status().getActiveSessionCount(), x + 16, y + 118);
            g.fillText("Server id: " + s.status().getServerId(), x + 16, y + 138);
        }
    }

    /** One replication lane: from the sending node's own report. */
    private void lane(GraphicsContext g, NodeSnapshot sender, double x1, double y, double x2, boolean leftToRight) {
        ReplicationStatusDTO r = sender == null ? null : sender.replication();
        Color c;
        String text;
        boolean dashed = false;
        if (sender == null || !sender.ok() || r == null) {
            c = Color.web("#71717A");
            text = "unknown";
            dashed = true;
        } else if (r.getPeerStatus() != ServerStatus.ONLINE) {
            c = Color.web("#FA174A");
            text = "no link (" + r.getPeerStatus().name().toLowerCase() + ")";
            dashed = true;
        } else if (r.getDeadLetterCount() > 0) {
            c = Color.web("#FA174A");
            text = r.getDeadLetterCount() + " dead-lettered";
        } else if (r.getPendingCount() > 0) {
            c = Color.web("#F59E0B");
            text = r.getPendingCount() + " waiting";
        } else {
            c = Color.web("#22C55E");
            text = "in sync";
        }
        g.setStroke(c);
        g.setLineWidth(3);
        g.setLineDashes(dashed ? new double[]{9, 7} : null);
        double xs = leftToRight ? x1 + 8 : x2 - 8, xe = leftToRight ? x2 - 8 : x1 + 8;
        g.strokeLine(xs, y, xe, y);
        g.setLineDashes((double[]) null);
        double d = leftToRight ? -1 : 1;
        g.setFill(c);
        g.fillPolygon(new double[]{xe, xe + 12 * d, xe + 12 * d}, new double[]{y, y - 6, y + 6}, 3);
        g.setFont(Font.font("System", 12));
        g.setFill(Color.web("#F4F4F6"));
        String label = (leftToRight ? "A to B: " : "B to A: ") + text;
        g.fillText(label, (x1 + x2) / 2 - label.length() * 3.0, y - 10);
    }
}
