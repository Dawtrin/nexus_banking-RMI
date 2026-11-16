package com.nexus.banking.control.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Random;

/**
 * Modern Dark Fintech Network Motif: Distributed dual-branch telemetry mesh
 * connecting Chi nhánh Hà Nội (Node A) and Chi nhánh TP.HCM (Node B).
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

        // Subtle background grid crosshairs
        g.setStroke(Color.web("#182340", 0.5));
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

        // Distributed mesh links (Electric Cyan / Digital Indigo)
        g.setLineWidth(1);
        double reach = Math.max(w, h) * 0.22;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Math.hypot(x[i] - x[j], y[i] - y[j]);
                if (d < reach) {
                    g.setStroke(Color.web("#22D3EE", 0.22 * (1 - d / reach)));
                    g.strokeLine(x[i], y[i], x[j], y[j]);
                }
            }
        }

        // Background constellation points
        g.setFill(Color.web("#5F6C8C", 0.5));
        for (int i = 0; i < n; i++) g.fillOval(x[i] - 1.5, y[i] - 1.5, 3, 3);

        // Server A and Server B coordinates
        double ax = w * 0.28, ay = h * 0.70;
        double bx = w * 0.74, by = h * 0.34;

        // Glowing Replication Beam (Cyan to Indigo)
        g.setStroke(Color.web("#22D3EE", 0.25));
        g.setLineWidth(12);
        g.strokeLine(ax, ay, bx, by);

        g.setStroke(Color.web("#6366F1", 0.55));
        g.setLineWidth(5);
        g.strokeLine(ax, ay, bx, by);

        g.setStroke(Color.web("#FFFFFF", 0.95));
        g.setLineWidth(1.5);
        g.strokeLine(ax, ay, bx, by);

        // Center replication status tag
        double mx = (ax + bx) / 2;
        double my = (ay + by) / 2;
        g.setFill(Color.web("#121A2E", 0.9));
        g.fillRoundRect(mx - 60, my - 13, 120, 26, 12, 12);
        g.setStroke(Color.web("#22D3EE", 0.75));
        g.setLineWidth(1);
        g.strokeRoundRect(mx - 60, my - 13, 120, 26, 12, 12);
        g.setFont(Font.font("Cascadia Mono", FontWeight.BOLD, 10));
        g.setFill(Color.web("#22D3EE"));
        g.fillText("// LEDGER_REPL", mx - 38, my + 4);

        // Node A (Electric Cyan telemetry core - Ha Noi)
        g.setFill(Color.web("#22D3EE", 0.2));
        g.fillOval(ax - 22, ay - 22, 44, 44);
        g.setStroke(Color.web("#22D3EE", 0.85));
        g.setLineWidth(2);
        g.strokeOval(ax - 13, ay - 13, 26, 26);
        g.setFill(Color.web("#E8ECF8"));
        g.fillOval(ax - 5, ay - 5, 10, 10);

        // Node B (Digital Indigo telemetry core - TP.HCM)
        g.setFill(Color.web("#6366F1", 0.25));
        g.fillOval(bx - 22, by - 22, 44, 44);
        g.setStroke(Color.web("#6366F1", 0.85));
        g.setLineWidth(2);
        g.strokeOval(bx - 13, by - 13, 26, 26);
        g.setFill(Color.web("#E8ECF8"));
        g.fillOval(bx - 5, by - 5, 10, 10);

        // Labels
        g.setFont(Font.font("Cascadia Mono", FontWeight.BOLD, 11));
        g.setFill(Color.web("#22D3EE"));
        g.fillText("// CHI NHANH A : HN", ax - 45, ay + 36);

        g.setFill(Color.web("#818CF8"));
        g.fillText("// CHI NHANH B : HCM", bx - 45, by - 26);
    }
}
