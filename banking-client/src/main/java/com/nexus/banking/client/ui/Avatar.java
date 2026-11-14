package com.nexus.banking.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Opera GX cyber-styled deterministic avatar supporting Base64 custom images and GX color presets. */
public final class Avatar {
    public record Preset(String id, String name, String colorHex) {}

    public static final List<Preset> PRESETS = List.of(
            new Preset("preset:gx-core", "GX Neon Red", "#FA174A"),
            new Preset("preset:cyber-cyan", "Cyber Cyan", "#00F0FF"),
            new Preset("preset:matrix-green", "Matrix Green", "#22C55E"),
            new Preset("preset:violet-haze", "Violet Haze", "#8B5CF6"),
            new Preset("preset:amber-blaze", "Amber Blaze", "#F59E0B"),
            new Preset("preset:crimson-blade", "Crimson Blade", "#E11D48")
    );

    private static final Map<String, String> PRESET_MAP = Map.of(
            "preset:gx-core", "#FA174A",
            "preset:cyber-cyan", "#00F0FF",
            "preset:matrix-green", "#22C55E",
            "preset:violet-haze", "#8B5CF6",
            "preset:amber-blaze", "#F59E0B",
            "preset:crimson-blade", "#E11D48"
    );

    private static final String[] PALETTE = {
            "#FA174A", // GX Neon Red
            "#00F0FF", // Cyber Cyan
            "#8B5CF6", // Cyber Violet
            "#22C55E", // Matrix Green
            "#F59E0B", // Amber
            "#EC4899", // Neon Pink
            "#06B6D4", // Teal
            "#FF3366"  // Crimson
    };

    private Avatar() {
    }

    public static String initials(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        String[] parts = n.split("[\\s._-]+");
        String first = parts[0].substring(0, 1);
        String second = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : (parts[0].length() > 1 ? parts[0].substring(1, 2) : "");
        return (first + second).toUpperCase();
    }

    public static Color colorFor(String key) {
        int h = key == null ? 0 : Math.abs(key.toLowerCase().hashCode());
        return Color.web(PALETTE[h % PALETTE.length]);
    }

    public static StackPane create(String name, double size) {
        return create(name, null, size);
    }

    public static StackPane create(String name, String avatarData, double size) {
        if (avatarData != null && !avatarData.isBlank()) {
            String trimmed = avatarData.trim();
            if (trimmed.startsWith("preset:")) {
                String hex = PRESET_MAP.getOrDefault(trimmed, "#FA174A");
                return createWithColor(name, Color.web(hex), size);
            }
            // Check if it's base64 image data
            if (trimmed.startsWith("data:image/") || looksLikeBase64(trimmed)) {
                StackPane imgPane = createImageAvatar(trimmed, size);
                if (imgPane != null) return imgPane;
            }
        }
        return createWithColor(name, colorFor(name), size);
    }

    private static boolean looksLikeBase64(String s) {
        return s.length() > 30 && (s.startsWith("/9j/") || s.startsWith("iVBOR") || s.startsWith("R0lGO"));
    }

    private static StackPane createImageAvatar(String data, double size) {
        try {
            String b64 = data;
            int idx = data.indexOf(";base64,");
            if (idx >= 0) {
                b64 = data.substring(idx + ";base64,".length());
            }
            byte[] bytes = Base64.getMimeDecoder().decode(b64.trim());
            Image image = new Image(new ByteArrayInputStream(bytes), size * 2, size * 2, true, true);
            if (image.isError() || image.getWidth() <= 0) {
                return null;
            }

            ImageView iv = new ImageView(image);
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(false);
            iv.setSmooth(true);

            Circle clip = new Circle(size / 2, size / 2, size / 2);
            iv.setClip(clip);

            Circle ring = new Circle(size / 2);
            ring.setFill(Color.TRANSPARENT);
            ring.setStroke(Color.web("#FA174A"));
            ring.setStrokeWidth(1.5);

            StackPane pane = new StackPane(iv, ring);
            pane.setAlignment(Pos.CENTER);
            pane.setMinSize(size, size);
            pane.setPrefSize(size, size);
            pane.setMaxSize(size, size);
            return pane;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static StackPane createWithColor(String name, Color bg, double size) {
        Circle c = new Circle(size / 2, bg);
        c.setStroke(Color.web("#2D2D38"));
        c.setStrokeWidth(1.5);
        Label l = new Label(initials(name));
        l.setStyle("-fx-text-fill: white; -fx-font-weight: 800; -fx-font-family: 'Segoe UI', 'Inter', sans-serif; -fx-font-size: " + Math.max(10, size * 0.38) + "px;");
        StackPane p = new StackPane(c, l);
        p.setMinSize(size, size);
        p.setPrefSize(size, size);
        p.setMaxSize(size, size);
        return p;
    }
}
