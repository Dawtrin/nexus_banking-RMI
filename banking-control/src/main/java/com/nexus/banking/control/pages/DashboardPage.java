package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.ThroughputPointDTO;
import com.nexus.banking.contract.enums.ServerStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tổng quan cụm: trạng thái 2 chi nhánh, kiểm tra sức khỏe từ phản hồi thật, và biểu đồ dòng bút toán. */
public class DashboardPage implements AdminPage {
    private final VBox root = new VBox();
    private final HBox tiles = new HBox(14);
    private final VBox checks = new VBox(8);
    private final Canvas gauge = new Canvas(150, 150);
    private final Pane chartHolder = new Pane();
    private final Canvas chart = new Canvas();
    private List<NodeSnapshot> last = List.of();

    public DashboardPage(AdminSession session) {
        Label hint = Ui.muted("Mọi số liệu lấy từ phản hồi thật của hai chi nhánh. Chi nhánh không phản hồi được hiển thị là không khả dụng.");
        VBox healthBox = new VBox(14, sectionTitle("Sức khỏe cụm"), new HBox(20, gauge, checks));
        healthBox.setMinWidth(400);
        healthBox.setPrefWidth(420);
        chartHolder.getChildren().add(chart);
        chartHolder.setMinHeight(260);
        chartHolder.widthProperty().addListener((o, a, b) -> drawChart());
        chartHolder.heightProperty().addListener((o, a, b) -> drawChart());
        VBox chartBox = new VBox(10, sectionTitle("Bút toán trong 30 phút gần nhất"), chartHolder);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);
        HBox.setHgrow(chartBox, Priority.ALWAYS);
        HBox lower = new HBox(36, healthBox, chartBox);
        VBox.setVgrow(lower, Priority.ALWAYS);
        VBox body = new VBox(18, hint, tiles, lower);
        body.setPadding(new Insets(22, 28, 22, 28));
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Tổng quan cụm", "Sức khỏe và lưu lượng giao dịch của hai chi nhánh"), body);
        onSnapshot(List.of());
    }

    private static Label sectionTitle(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("title-md");
        return l;
    }

    @Override
    public String title() { return "Tổng quan"; }

    @Override
    public Parent view() { return root; }

    private VBox tile(String label, String value, String kind, String sub) {
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        Label v = new Label(value);
        v.getStyleClass().add(value.length() > 8 ? "text-mono" : "stat-value");
        v.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox t = new VBox(4, l, v);
        if (kind != null) t.getChildren().add(Ui.chip(sub, kind));
        else if (sub != null) t.getChildren().add(Ui.muted(sub));
        t.getStyleClass().add("stat-tile");
        HBox.setHgrow(t, Priority.ALWAYS);
        t.setMaxWidth(Double.MAX_VALUE);
        return t;
    }

    @Override
    public void onSnapshot(List<NodeSnapshot> snaps) {
        last = snaps;
        tiles.getChildren().clear();
        long sessions = 0;
        long storedLastMinute = 0;
        for (NodeSnapshot s : snaps) {
            String kind;
            String word;
            if (s.status() == null) {
                kind = "danger";
                word = "Không có dữ liệu";
            } else if (!s.ok()) {
                kind = "danger";
                word = "Không khả dụng";
            } else {
                ServerStatus st = s.status().getServerStatus();
                kind = st == ServerStatus.ONLINE ? "ok" : st == ServerStatus.DEGRADED ? "warn" : "danger";
                word = st.name().charAt(0) + st.name().substring(1).toLowerCase();
            }
            tiles.getChildren().add(tile(s.endpoint().label(), s.endpoint().address(), kind, word + (s.stale() ? " (số liệu cũ)" : "")));
            if (s.ok() && s.status() != null) sessions += s.status().getActiveSessionCount();
            if (s.ok()) {
                List<ThroughputPointDTO> t = s.throughput();
                for (int i = Math.max(0, t.size() - 6); i < t.size(); i++) storedLastMinute += t.get(i).getStored();
            }
        }
        for (NodeSnapshot s : snaps) {
            if (s.ok() && s.ledger() != null) {
                tiles.getChildren().add(tile("Tổng số dư " + s.endpoint().label().replace("Node ", "CN "),
                        com.nexus.banking.contract.dto.Money.format(s.ledger().getTotalBalance()), null,
                        s.ledger().getTransactionCount() + " bút toán trong sổ"));
            }
        }
        tiles.getChildren().add(tile("Phiên đang đăng nhập", String.valueOf(sessions), null, "trên các chi nhánh phản hồi"));
        tiles.getChildren().add(tile("Bút toán, 1 phút qua", String.valueOf(storedLastMinute), null, "ghi nhận tại cả hai chi nhánh"));
        drawHealth();
        drawChart();
    }

    private Map<String, Boolean> healthChecks() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        boolean allOk = !last.isEmpty() && last.stream().allMatch(NodeSnapshot::ok);
        m.put("Cả hai chi nhánh phản hồi", allOk);
        m.put("CSDL đã kết nối và bền vững (MySQL)", allOk && last.stream().allMatch(s -> s.status().getDatabaseStatus() == ServerStatus.ONLINE
                && "MYSQL_DURABLE".equals(s.status().getPersistenceMode())));
        m.put("Hai chi nhánh thấy nhau (mesh)", allOk && last.stream().allMatch(s -> s.replication() != null
                && s.replication().getPeerStatus() == ServerStatus.ONLINE));
        m.put("Không còn bút toán chờ đồng bộ", allOk && last.stream().allMatch(s -> s.replication() != null && s.replication().getPendingCount() == 0));
        m.put("Không có bút toán bị kẹt (dead-letter)", allOk && last.stream().allMatch(s -> s.replication() != null && s.replication().getDeadLetterCount() == 0));
        boolean converged = allOk && last.size() >= 2 && last.get(0).ledger() != null && last.get(1).ledger() != null
                && last.get(0).ledger().getBalanceDigest().equals(last.get(1).ledger().getBalanceDigest());
        m.put("Số dư mọi tài khoản khớp giữa 2 chi nhánh", converged);
        boolean noOverdraft = allOk && last.stream().allMatch(s -> s.ledger() != null && s.ledger().getOverdrawnAccounts().isEmpty());
        m.put("Không có tài khoản âm sau hợp nhất", noOverdraft);
        return m;
    }

    private void drawHealth() {
        Map<String, Boolean> m = healthChecks();
        checks.getChildren().clear();
        int pass = 0;
        for (Map.Entry<String, Boolean> e : m.entrySet()) {
            if (e.getValue()) pass++;
            Label mark = Ui.chip(e.getValue() ? "Đạt" : last.isEmpty() ? "Chưa rõ" : "Kiểm tra", e.getValue() ? "ok" : last.isEmpty() ? "neutral" : "warn");
            HBox row = new HBox(10, mark, new Label(e.getKey()));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("check-row");
            checks.getChildren().add(row);
        }
        GraphicsContext g = gauge.getGraphicsContext2D();
        g.clearRect(0, 0, 150, 150);
        g.setLineWidth(14);
        g.setStroke(Color.web("#252530"));
        g.strokeArc(17, 17, 116, 116, 0, 360, ArcType.OPEN);
        double frac = m.isEmpty() ? 0 : (double) pass / m.size();
        g.setStroke(last.isEmpty() ? Color.web("#71717A") : frac == 1 ? Color.web("#22C55E") : frac >= 0.6 ? Color.web("#F59E0B") : Color.web("#FA174A"));
        g.strokeArc(17, 17, 116, 116, 90, -360 * frac, ArcType.OPEN);
        g.setFill(Color.web("#F4F4F6"));
        g.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 30));
        String txt = last.isEmpty() ? "-" : pass + "/" + m.size();
        g.fillText(txt, 75 - txt.length() * 9, 82);
        g.setFont(javafx.scene.text.Font.font("System", 11));
        g.setFill(Color.web("#A1A1AA"));
        g.fillText("mục đạt", 46, 102);
    }

    private void drawChart() {
        double w = chartHolder.getWidth(), h = chartHolder.getHeight();
        if (w < 80 || h < 80) return;
        chart.setWidth(w);
        chart.setHeight(h);
        GraphicsContext g = chart.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double left = 40, bottom = 34, top = 26;
        double pw = w - left - 10, ph = h - bottom - top;
        List<ThroughputPointDTO> a = last.size() > 0 && last.get(0).ok() ? last.get(0).throughput() : List.of();
        List<ThroughputPointDTO> b = last.size() > 1 && last.get(1).ok() ? last.get(1).throughput() : List.of();
        int n = Math.max(a.size(), b.size());
        if (n < 2) {
            g.setFill(Color.web("#71717A"));
            g.fillText("Chưa có dữ liệu: chưa chi nhánh nào phản hồi.", left, h / 2);
            return;
        }
        double[] sent = new double[n], ab = new double[n], ba = new double[n];
        for (int i = 0; i < n; i++) {
            int ia = i - (n - a.size()), ib = i - (n - b.size());
            if (ia >= 0) { sent[i] += a.get(ia).getStored(); ab[i] = a.get(ia).getReplicatedOut(); }
            if (ib >= 0) { sent[i] += b.get(ib).getStored(); ba[i] = b.get(ib).getReplicatedOut(); }
        }
        double max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, Math.max(sent[i], Math.max(ab[i], ba[i])));
        max = Math.ceil(max / 4) * 4;
        g.setStroke(Color.web("#252530"));
        g.setLineWidth(1);
        g.setFill(Color.web("#71717A"));
        for (int k = 0; k <= 4; k++) {
            double y = top + ph - ph * k / 4;
            g.strokeLine(left, y, left + pw, y);
            g.fillText(String.valueOf((int) (max * k / 4)), 6, y + 4);
        }
        g.fillText("30 phút trước", left, h - 10);
        g.fillText("nay", left + pw - 20, h - 10);
        plot(g, sent, max, left, top, pw, ph, Color.web("#FA174A"));
        plot(g, ab, max, left, top, pw, ph, Color.web("#00F0FF"));
        plot(g, ba, max, left, top, pw, ph, Color.web("#22C55E"));
        double lx = left;
        double step = Math.min(140, pw / 3);
        for (Object[] leg : new Object[][]{{"Ghi sổ", "#9D8CFF"}, {"A sang B", "#3B82F6"}, {"B sang A", "#22C55E"}}) {
            g.setFill(Color.web((String) leg[1]));
            g.fillOval(lx, 8, 9, 9);
            g.setFill(Color.web("#A1A1AA"));
            g.fillText((String) leg[0], lx + 14, 16);
            lx += step;
        }
    }

    private static void plot(GraphicsContext g, double[] v, double max, double left, double top, double pw, double ph, Color c) {
        g.setStroke(c);
        g.setLineWidth(2);
        g.beginPath();
        for (int i = 0; i < v.length; i++) {
            double x = left + pw * i / (v.length - 1);
            double y = top + ph - ph * v[i] / max;
            if (i == 0) g.moveTo(x, y);
            else g.lineTo(x, y);
        }
        g.stroke();
    }
}
