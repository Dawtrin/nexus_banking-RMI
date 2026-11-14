package com.nexus.banking.client.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Random;

/**
 * Opera GX Cyber Network Motif: A futuristic distributed node telemetry mesh
 * connecting Server A and Server B with high-energy glowing replication beams.
 */
public final class MeshPane extends Pane {
    private final Canvas canvas = new Canvas();

    public MeshPane() {
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw(canvas.getGraphicsContext2D(), getWidth(), getHeight());
    }

    private static void draw(GraphicsContext g, double w, double h) {
        g.clearRect(0, 0, w, h);
        if (w < 50 || h < 50) return;

        // Subtle GX background cyber grid crosshairs
        g.setStroke(Color.web("#1E1E28", 0.45));
        g.setLineWidth(1);
        double gridStep = 48;
        for (double gx = 0; gx < w; gx += gridStep) {
            for (double gy = 0; gy < h; gy += gridStep) {
                g.strokeLine(gx - 2, gy, gx + 2, gy);
                g.strokeLine(gx, gy - 2, gx, gy + 2);
            }
        }

        Random rnd = new Random(7);
        int n = 34;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextDouble() * w;
            y[i] = rnd.nextDouble() * h;
        }

        // Distributed mesh links (Cyber Cyan / Steel)
        g.setLineWidth(1);
        double reach = Math.max(w, h) * 0.22;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Math.hypot(x[i] - x[j], y[i] - y[j]);
                if (d < reach) {
                    g.setStroke(Color.web("#00F0FF", 0.18 * (1 - d / reach)));
                    g.strokeLine(x[i], y[i], x[j], y[j]);
                }
            }
        }

        // Background constellation points
        g.setFill(Color.web("#71717A", 0.4));
        for (int i = 0; i < n; i++) g.fillOval(x[i] - 1.5, y[i] - 1.5, 3, 3);

        // Server A and Server B coordinates
        double ax = w * 0.28, ay = h * 0.70;
        double bx = w * 0.74, by = h * 0.34;

        // GX Glowing Red Replication Beam
        g.setStroke(Color.web("#FA174A", 0.22));
        g.setLineWidth(12);
        g.strokeLine(ax, ay, bx, by);

        g.setStroke(Color.web("#FA174A", 0.55));
        g.setLineWidth(5);
        g.strokeLine(ax, ay, bx, by);

        g.setStroke(Color.web("#FFFFFF", 0.95));
        g.setLineWidth(1.5);
        g.strokeLine(ax, ay, bx, by);

        // Center replication status tag
        double mx = (ax + bx) / 2;
        double my = (ay + by) / 2;
        g.setFill(Color.web("#131317", 0.85));
        g.fillRoundRect(mx - 55, my - 12, 110, 24, 4, 4);
        g.setStroke(Color.web("#FA174A", 0.75));
        g.setLineWidth(1);
        g.strokeRoundRect(mx - 55, my - 12, 110, 24, 4, 4);
        g.setFont(Font.font("Cascadia Mono", FontWeight.BOLD, 9));
        g.setFill(Color.web("#FA174A"));
        g.fillText("// REPL_LINK", mx - 34, my + 3);

        // Node A (Cyber Cyan telemetry core)
        g.setFill(Color.web("#00F0FF", 0.18));
        g.fillOval(ax - 20, ay - 20, 40, 40);
        g.setStroke(Color.web("#00F0FF", 0.75));
        g.setLineWidth(1.5);
        g.strokeOval(ax - 12, ay - 12, 24, 24);
        g.setFill(Color.web("#F4F4F6"));
        g.fillOval(ax - 5, ay - 5, 10, 10);

        // Node B (Opera GX Neon Red core)
        g.setFill(Color.web("#FA174A", 0.22));
        g.fillOval(bx - 20, by - 20, 40, 40);
        g.setStroke(Color.web("#FA174A", 0.85));
        g.setLineWidth(1.5);
        g.strokeOval(bx - 12, by - 12, 24, 24);
        g.setFill(Color.web("#F4F4F6"));
        g.fillOval(bx - 5, by - 5, 10, 10);

        // Labels
        g.setFont(Font.font("Cascadia Mono", FontWeight.BOLD, 11));
        g.setFill(Color.web("#00F0FF"));
        g.fillText("// NODE_A : 1099", ax - 45, ay + 34);

        g.setFill(Color.web("#FA174A"));
        g.fillText("// NODE_B : 2099", bx - 45, by - 24);
    }
}
