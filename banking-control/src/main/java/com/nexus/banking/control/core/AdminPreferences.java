package com.nexus.banking.control.core;

import java.util.prefs.Preferences;

/** Non-sensitive NEXUS CONTROL preferences. The admin token is never stored. */
public final class AdminPreferences {
    private static final Preferences P = Preferences.userNodeForPackage(AdminPreferences.class);

    private AdminPreferences() {
    }

    private static String envOr(String env, String key, String def) {
        String v = System.getenv(env);
        return v == null || v.isBlank() ? P.get(key, def) : v;
    }

    /** "host:port" for node A / B. */
    public static String nodeA() { return envOr("NEXUS_NODE_A", "nodeA", "127.0.0.1:1099"); }
    public static String nodeB() { return envOr("NEXUS_NODE_B", "nodeB", "127.0.0.1:2099"); }
    public static String operator() { return P.get("operator", ""); }
    public static int refreshSeconds() { return P.getInt("refreshSeconds", 3); }
    public static boolean sslEnabled() { return Boolean.parseBoolean(envOr("NEXUS_ADMIN_SSL", "ssl", "false")); }

    public static void save(String nodeA, String nodeB, String operator) {
        P.put("nodeA", nodeA);
        P.put("nodeB", nodeB);
        P.put("operator", operator);
    }

    public static void setSslEnabled(boolean v) { P.putBoolean("ssl", v); }
    public static void setRefreshSeconds(int s) { P.putInt("refreshSeconds", s); }
}
