package com.nexus.banking.client.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Animated picture of the two branches and the replication link; the serving branch is highlighted. */
final class MeshCanvas extends Pane {
    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private volatile int serving = 0;
    private volatile boolean linkUp = true;
    private volatile int pending = 0;

    MeshCanvas() {
        setMinHeight(230);
        setPrefHeight(230);
        getChildren().add(canvas);
        widthProperty().addListener((o, a, b) -> canvas.setWidth(b.doubleValue()));
        heightProperty().addListener((o, a, b) -> canvas.setHeight(b.doubleValue()));
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                draw(now / 1_000_000_000.0);
            }
        };
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) timer.stop();
            else timer.start();
        });
    }

    void update(int servingIndex, boolean linkUp, int pending) {
        this.serving = servingIndex;
        this.linkUp = linkUp;
        this.pending = pending;
    }

    private void draw(double t) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 100 || h < 100) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double ax = w * 0.2, bx = w * 0.8, y = h * 0.42, r = 34;
        // link
        g.setLineWidth(2.5);
        g.setStroke(linkUp ? Color.web("#22D3EE", 0.55) : Color.web("#FB7185", 0.7));
        g.setLineDashes(linkUp ? 0 : 8);
        g.strokeLine(ax + r, y, bx - r, y);
        g.setLineDashes(0);
        if (linkUp) {
            for (int i = 0; i < 4; i++) {
                double p = ((t * 0.5 + i * 0.25) % 1.0);
                double x1 = ax + r + (bx - ax - 2 * r) * p;
                g.setFill(Color.web("#22D3EE", 0.9));
                g.fillOval(x1 - 4, y - 4 - 0, 8, 8);
                double p2 = 1 - ((t * 0.5 + i * 0.25 + 0.125) % 1.0);
                double x2 = ax + r + (bx - ax - 2 * r) * p2;
                g.setFill(Color.web("#6366F1", 0.9));
                g.fillOval(x2 - 3, y + 6 - 3, 6, 6);
            }
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(linkUp ? Color.web("#93A0C0") : Color.web("#FB7185"));
        g.setFont(Font.font("System", 12));
        g.fillText(linkUp ? (pending > 0 ? "đang đồng bộ " + pending + " bút toán" : "sổ cái đồng bộ 2 chiều") : "mất liên kết - giao dịch xếp hàng chờ", w / 2, y - 18);
        node(g, ax, y, r, "HÀ NỘI", "Node A", serving == 0, t);
        node(g, bx, y, r, "TP.HCM", "Node B", serving == 1, t);
        // client marker
        double cx = serving == 0 ? ax : bx;
        g.setFill(Color.web("#E8ECF8"));
        g.setFont(Font.font("System", FontWeight.BOLD, 12));
        g.fillText("▲ Bạn đang ở đây", cx, y + r + 62);
    }

    private void node(GraphicsContext g, double x, double y, double r, String name, String sub, boolean active, double t) {
        if (active) {
            double pulse = 6 + 4 * Math.sin(t * 3);
            g.setStroke(Color.web("#22D3EE", 0.45));
            g.setLineWidth(2);
            g.strokeOval(x - r - pulse, y - r - pulse, 2 * (r + pulse), 2 * (r + pulse));
        }
        g.setFill(active ? Color.web("#16335A") : Color.web("#141D38"));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setStroke(active ? Color.web("#22D3EE") : Color.web("#33426B"));
        g.setLineWidth(2);
        g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        g.setFill(Color.web("#E8ECF8"));
        g.setFont(Font.font("System", FontWeight.BOLD, 14));
        g.fillText(name.substring(0, 2).equals("HÀ") ? "HN" : "HCM", x, y + 5);
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.fillText(name, x, y + r + 20);
        g.setFill(Color.web("#93A0C0"));
        g.setFont(Font.font("System", 11));
        g.fillText(sub, x, y + r + 36);
    }
}
