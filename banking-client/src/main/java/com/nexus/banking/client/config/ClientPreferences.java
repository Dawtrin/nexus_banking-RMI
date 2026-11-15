package com.nexus.banking.client.config;

import java.util.prefs.Preferences;

/** Non-sensitive client preferences kept between launches (NEVER the PIN). */
public final class ClientPreferences {
    private static final Preferences PREFS = Preferences.userNodeForPackage(ClientPreferences.class);

    private ClientPreferences() {
    }

    public static String nodeA() { return PREFS.get("nodeA", ClientConfig.DEFAULT_NODE_A_HOST + ":" + ClientConfig.DEFAULT_NODE_A_PORT); }
    public static String nodeB() { return PREFS.get("nodeB", ClientConfig.DEFAULT_NODE_B_HOST + ":" + ClientConfig.DEFAULT_NODE_B_PORT); }
    /** 0 = Hà Nội (A), 1 = TP.HCM (B). */
    public static int preferredBranch() { return PREFS.getInt("preferredBranch", 0); }
    public static String lastAccount() { return PREFS.get("lastAccount", ""); }
    public static boolean autoReconnect() { return PREFS.getBoolean("autoReconnect", true); }
    public static boolean sslEnabled() {
        return Boolean.parseBoolean(System.getProperty("nexus.client.ssl", String.valueOf(PREFS.getBoolean("ssl", false))));
    }
    public static boolean showDemoAccounts() { return PREFS.getBoolean("demoAccounts", true); }

    public static void saveConnection(String nodeA, String nodeB, int preferred, String account) {
        PREFS.put("nodeA", nodeA);
        PREFS.put("nodeB", nodeB);
        PREFS.putInt("preferredBranch", preferred);
        PREFS.put("lastAccount", account == null ? "" : account);
    }

    public static void setAutoReconnect(boolean v) { PREFS.putBoolean("autoReconnect", v); }
    public static void setSslEnabled(boolean v) { PREFS.putBoolean("ssl", v); }
    public static void setShowDemoAccounts(boolean v) { PREFS.putBoolean("demoAccounts", v); }
}
