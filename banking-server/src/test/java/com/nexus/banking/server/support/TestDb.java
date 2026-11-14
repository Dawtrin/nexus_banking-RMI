package com.nexus.banking.server.support;

import com.nexus.banking.server.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Environment-dependent database for integration tests. Set NEXUS_TEST_DB_HOST, NEXUS_TEST_DB_PORT,
 * NEXUS_TEST_DB_USER, NEXUS_TEST_DB_PASSWORD (an account allowed to CREATE/DROP databases) and optionally
 * NEXUS_TEST_JDBC_PREFIX (default jdbc:mysql). Without them these tests are SKIPPED, never reported as passed.
 */
public final class TestDb {
    private TestDb() {
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public static boolean available() {
        String h = System.getenv("NEXUS_TEST_DB_HOST");
        return h != null && !h.isBlank();
    }

    private static String prefix() {
        return env("NEXUS_TEST_JDBC_PREFIX", "jdbc:mysql");
    }

    private static String url(String host, int port, String db) {
        String extra = prefix().equals("jdbc:mysql") ? "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" : "";
        return prefix() + "://" + host + ":" + port + "/" + db + extra;
    }

    /** Drops and recreates {@code dbName}; returns a config pointing at it. */
    public static DatabaseConfig fresh(String dbName) throws Exception {
        String host = env("NEXUS_TEST_DB_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("NEXUS_TEST_DB_PORT", "3306"));
        String user = env("NEXUS_TEST_DB_USER", "root");
        String pw = env("NEXUS_TEST_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url(host, port, ""), user, pw); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4");
        }
        return config(true, host, port, dbName, user, pw);
    }

    public static DatabaseConfig reopen(String dbName) {
        return config(true, env("NEXUS_TEST_DB_HOST", "127.0.0.1"), Integer.parseInt(env("NEXUS_TEST_DB_PORT", "3306")),
                dbName, env("NEXUS_TEST_DB_USER", "root"), env("NEXUS_TEST_DB_PASSWORD", ""));
    }

    /** Same database, reached through another host/port (e.g. a TcpProxy) so outages can be simulated. */
    public static DatabaseConfig viaPortFor(String dbName, int port) {
        return viaPort(dbName, port);
    }

    public static DatabaseConfig viaPort(String dbName, int port) {
        return config(true, "127.0.0.1", port, dbName, env("NEXUS_TEST_DB_USER", "root"), env("NEXUS_TEST_DB_PASSWORD", ""));
    }

    public static int realPort() {
        return Integer.parseInt(env("NEXUS_TEST_DB_PORT", "3306"));
    }

    public static DatabaseConfig unreachable() {
        return config(true, "127.0.0.1", 1, "nexus_missing", "x", "x");
    }

    public static Connection admin(String dbName) throws Exception {
        return DriverManager.getConnection(url(env("NEXUS_TEST_DB_HOST", "127.0.0.1"),
                Integer.parseInt(env("NEXUS_TEST_DB_PORT", "3306")), dbName),
                env("NEXUS_TEST_DB_USER", "root"), env("NEXUS_TEST_DB_PASSWORD", ""));
    }

    public static DatabaseConfig disabled() {
        return new DatabaseConfig(false, "127.0.0.1", 3306, "unused", "x", "x", 1);
    }

    private static DatabaseConfig config(boolean enabled, String host, int port, String db, String user, String pw) {
        return new DatabaseConfig(enabled, host, port, db, user, pw, 8) {
            @Override
            public String getJdbcUrl() {
                return url(host, port, db);
            }
        };
    }
}
