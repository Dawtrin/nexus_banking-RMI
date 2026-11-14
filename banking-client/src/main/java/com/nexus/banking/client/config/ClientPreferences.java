package com.nexus.banking.client.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;

/** Non-sensitive client preferences kept between launches (never credentials). */
public final class ClientPreferences {
    private static final Preferences PREFS = Preferences.userNodeForPackage(ClientPreferences.class);
    private static final Path AVATAR_DIR = Paths.get(System.getProperty("user.home"), ".nexus-chat", "avatars");

    private ClientPreferences() {
    }

    public static String host() { return PREFS.get("host", ClientConfig.DEFAULT_SERVER_A_HOST); }
    public static int port() { return PREFS.getInt("port", ClientConfig.DEFAULT_SERVER_A_PORT); }
    public static String username() { return PREFS.get("username", ""); }
    public static boolean rememberConnection() { return PREFS.getBoolean("remember", true); }
    public static boolean autoReconnect() { return PREFS.getBoolean("autoReconnect", true); }
    public static boolean sslEnabled() { return Boolean.parseBoolean(System.getProperty("nexus.client.ssl", String.valueOf(PREFS.getBoolean("ssl", false)))); }
    /** SMALL, NORMAL or LARGE. */
    public static String fontSize() { return PREFS.get("fontSize", "NORMAL"); }
    public static boolean beepOnMessage() { return PREFS.getBoolean("beep", false); }
    public static boolean unreadInTitle() { return PREFS.getBoolean("unreadTitle", true); }

    public static String avatar(String username) {
        if (username == null || username.isBlank()) return "";
        String cleanUser = username.trim().toLowerCase();
        try {
            Path file = AVATAR_DIR.resolve(cleanUser + ".avatar");
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
        }
        return PREFS.get("avatar_" + cleanUser, "");
    }

    public static void setAvatar(String username, String avatar) {
        if (username == null || username.isBlank()) return;
        String cleanUser = username.trim().toLowerCase();
        if (avatar == null || avatar.isBlank()) {
            PREFS.remove("avatar_" + cleanUser);
            try {
                Path file = AVATAR_DIR.resolve(cleanUser + ".avatar");
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
            return;
        }
        if (avatar.startsWith("preset:")) {
            PREFS.put("avatar_" + cleanUser, avatar);
            try {
                Path file = AVATAR_DIR.resolve(cleanUser + ".avatar");
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        } else {
            try {
                Files.createDirectories(AVATAR_DIR);
                Path file = AVATAR_DIR.resolve(cleanUser + ".avatar");
                Files.writeString(file, avatar, StandardCharsets.UTF_8);
                PREFS.put("avatar_" + cleanUser, "custom:file");
            } catch (Exception ignored) {
                if (avatar.length() < 7000) PREFS.put("avatar_" + cleanUser, avatar);
            }
        }
    }

    public static void remember(String host, int port, String username) {
        PREFS.put("host", host);
        PREFS.putInt("port", port);
        PREFS.put("username", username);
    }

    public static void setRememberConnection(boolean v) { PREFS.putBoolean("remember", v); }
    public static void setAutoReconnect(boolean v) { PREFS.putBoolean("autoReconnect", v); }
    public static void setSslEnabled(boolean v) { PREFS.putBoolean("ssl", v); }
    public static void setFontSize(String v) { PREFS.put("fontSize", v); }
    public static void setBeepOnMessage(boolean v) { PREFS.putBoolean("beep", v); }
    public static void setUnreadInTitle(boolean v) { PREFS.putBoolean("unreadTitle", v); }

    /** Forgets host, port and username (the next login starts blank). */
    public static void forgetConnection() {
        PREFS.remove("host");
        PREFS.remove("port");
        PREFS.remove("username");
    }
}
