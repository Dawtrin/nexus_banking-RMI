package com.nexus.banking.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server configuration with an explicit, predictable precedence (highest first):
 * <ol>
 *   <li>command line {@code --key=value}</li>
 *   <li>JVM system property {@code -Dkey=value}</li>
 *   <li>environment variable</li>
 *   <li>{@code application.properties} in the working directory (values may use {@code ${ENV_VAR}})</li>
 *   <li>built-in default (only for non-security settings)</li>
 * </ol>
 * Security-relevant settings have NO defaults: {@code database.enabled} must be stated explicitly, the
 * replication secret is mandatory, and the admin token is optional (admin API disabled when absent).
 */
public class ServerConfig {
    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    public static final int MIN_SECRET_LENGTH = 16;
    public static final int MIN_ADMIN_TOKEN_LENGTH = 12;

    private final Map<String, String> cli;
    private final Map<String, String> sysProps;
    private final Map<String, String> env;
    private final Properties file;

    private final String serverId;
    private final String advertisedHost;
    private final int rmiRegistryPort;
    private final int rmiServicePort;
    private final String adminToken;
    private final String replicationSecret;
    private final String peerId;
    private final String peerHost;
    private final int peerRegistryPort;
    private final DatabaseConfig databaseConfig;
    private final boolean schemaAutoApply;
    private final int replicationBatchSize;
    private final long replicationPollMillis;
    private final int catchUpPageSize;
    private final int maxRejectedAttempts;
    private final long backoffBaseMillis;
    private final long backoffMaxMillis;
    private final boolean sslEnabled;
    private final String sslKeyStorePath;
    private final String sslKeyStorePassword;
    private final String sslTrustStorePath;
    private final String sslTrustStorePassword;
    private final boolean sslNeedClientAuth;
    private final boolean peerSslEnabled;
    private final boolean singleSessionEnabled;
    private final String branchName;
    private final java.time.ZoneId businessZone;

    /** Production constructor: reads real CLI args, system properties, environment and application.properties. */
    public ServerConfig(String[] args) {
        this(parseArgs(args), systemProperties(), System.getenv(), loadFile());
    }

    /** Test/embedding constructor with fully injected sources. */
    public ServerConfig(Map<String, String> cli, Map<String, String> sysProps, Map<String, String> env, Properties file) {
        this.cli = cli == null ? Map.of() : cli;
        this.sysProps = sysProps == null ? Map.of() : sysProps;
        this.env = env == null ? Map.of() : env;
        this.file = file == null ? new Properties() : file;

        this.serverId = get("server.id", "NEXUS_SERVER_ID", "server-a");
        this.peerId = get("server.peer.id", "NEXUS_PEER_ID", "server-b");
        this.advertisedHost = get("server.advertised.host", "NEXUS_SERVER_HOST", "127.0.0.1");
        this.peerHost = get("server.peer.host", "NEXUS_PEER_HOST", "127.0.0.1");
        this.rmiRegistryPort = port("server.rmi.registry.port", "NEXUS_RMI_REGISTRY_PORT", "1099");
        this.rmiServicePort = port("server.rmi.service.port", "NEXUS_RMI_SERVICE_PORT", "1100");
        this.peerRegistryPort = port("server.peer.registry.port", "NEXUS_PEER_REGISTRY_PORT", "2099");
        this.adminToken = get("server.admin.token", "NEXUS_ADMIN_TOKEN", null);
        this.replicationSecret = get("server.replication.secret", "NEXUS_REPLICATION_SECRET", null);

        this.sslEnabled = Boolean.parseBoolean(get("server.ssl.enabled", "NEXUS_SSL_ENABLED", "false"));
        this.sslKeyStorePath = get("server.ssl.keystore.path", "NEXUS_SSL_KEYSTORE_PATH", null);
        this.sslKeyStorePassword = get("server.ssl.keystore.password", "NEXUS_SSL_KEYSTORE_PASSWORD", null);
        this.sslTrustStorePath = get("server.ssl.truststore.path", "NEXUS_SSL_TRUSTSTORE_PATH", null);
        this.sslTrustStorePassword = get("server.ssl.truststore.password", "NEXUS_SSL_TRUSTSTORE_PASSWORD", null);
        this.sslNeedClientAuth = Boolean.parseBoolean(get("server.ssl.need.client.auth", "NEXUS_SSL_NEED_CLIENT_AUTH", "false"));
        this.peerSslEnabled = Boolean.parseBoolean(get("server.peer.ssl.enabled", "NEXUS_PEER_SSL_ENABLED", String.valueOf(this.sslEnabled)));
        // Banking: one customer session per account across the whole cluster is the default.
        this.singleSessionEnabled = Boolean.parseBoolean(get("server.session.single", "NEXUS_SINGLE_SESSION", "true"));
        this.branchName = get("server.branch.name", "NEXUS_BRANCH_NAME", serverId);
        String zone = get("server.business.timezone", "NEXUS_TIMEZONE", "Asia/Ho_Chi_Minh");
        try {
            this.businessZone = java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            throw new ConfigException("server.business.timezone is not a valid time zone: " + zone);
        }

        if (this.sslEnabled) {
            configureSslSystemProperties();
        }

        String dbFlag = get("database.enabled", "NEXUS_DB_ENABLED", null);
        if (dbFlag == null) {
            throw new ConfigException("database.enabled must be set explicitly to true or false "
                    + "(false = RAM-only demo mode, NOT durable).");
        }
        if (!dbFlag.equalsIgnoreCase("true") && !dbFlag.equalsIgnoreCase("false")) {
            throw new ConfigException("database.enabled must be 'true' or 'false' but was '" + dbFlag + "'");
        }
        this.databaseConfig = new DatabaseConfig(
                Boolean.parseBoolean(dbFlag),
                get("database.host", "NEXUS_DB_HOST", "127.0.0.1"),
                port("database.port", "NEXUS_DB_PORT", "3306"),
                get("database.name", "NEXUS_DB_NAME", "nexus_banking"),
                get("database.username", "DB_USERNAME", "nexus_user"),
                get("database.password", "DB_PASSWORD", ""),
                intValue("database.pool.max-size", "NEXUS_DB_POOL_MAX", "10", 1, 100));
        this.schemaAutoApply = Boolean.parseBoolean(get("database.schema.auto", "NEXUS_DB_SCHEMA_AUTO", "true"));
        this.replicationBatchSize = intValue("replication.batch.size", "NEXUS_REPL_BATCH", "50", 1, 500);
        this.replicationPollMillis = intValue("replication.poll.millis", "NEXUS_REPL_POLL_MS", "1000", 50, 60000);
        this.catchUpPageSize = intValue("replication.catchup.page", "NEXUS_REPL_CATCHUP_PAGE", "200", 1, 1000);
        this.maxRejectedAttempts = intValue("replication.max.rejected.attempts", "NEXUS_REPL_MAX_REJECTED", "20", 1, 1000);
        this.backoffBaseMillis = intValue("replication.backoff.base.millis", "NEXUS_REPL_BACKOFF_BASE_MS", "1000", 10, 600000);
        this.backoffMaxMillis = intValue("replication.backoff.max.millis", "NEXUS_REPL_BACKOFF_MAX_MS", "60000", 100, 3600000);
        validate();
        log.info("ServerConfig: id={}, advertisedHost={}, registryPort={}, servicePort={}, peer={}@{}:{}, "
                        + "databaseEnabled={}, adminApi={}, ssl={}",
                serverId, advertisedHost, rmiRegistryPort, rmiServicePort, peerId, peerHost, peerRegistryPort,
                databaseConfig.isEnabled(), adminToken == null ? "DISABLED (no token configured)" : "enabled",
                sslEnabled ? "ENABLED (TLS)" : "DISABLED (plain TCP)");
    }

    private void configureSslSystemProperties() {
        if (sslKeyStorePath != null && System.getProperty("javax.net.ssl.keyStore") == null) {
            System.setProperty("javax.net.ssl.keyStore", sslKeyStorePath);
        }
        if (sslKeyStorePassword != null && System.getProperty("javax.net.ssl.keyStorePassword") == null) {
            System.setProperty("javax.net.ssl.keyStorePassword", sslKeyStorePassword);
        }
        if (sslTrustStorePath != null && System.getProperty("javax.net.ssl.trustStore") == null) {
            System.setProperty("javax.net.ssl.trustStore", sslTrustStorePath);
        }
        if (sslTrustStorePassword != null && System.getProperty("javax.net.ssl.trustStorePassword") == null) {
            System.setProperty("javax.net.ssl.trustStorePassword", sslTrustStorePassword);
        }
    }

    private void validate() {
        if (serverId.isBlank() || peerId.isBlank()) {
            throw new ConfigException("server.id and server.peer.id must not be blank");
        }
        if (serverId.equalsIgnoreCase(peerId)) {
            throw new ConfigException("server.id and server.peer.id must be different");
        }
        if (!isLoopback(peerHost) && isLoopback(advertisedHost)) {
            throw new ConfigException("server.advertised.host must not be a loopback address ("
                    + advertisedHost + ") when peer is remote (" + peerHost + ")");
        }
        if (replicationSecret == null || replicationSecret.length() < MIN_SECRET_LENGTH) {
            throw new ConfigException("server.replication.secret (NEXUS_REPLICATION_SECRET) must be set and at least "
                    + MIN_SECRET_LENGTH + " characters long (current: "
                    + (replicationSecret == null ? "null" : replicationSecret.length()) + ")");
        }
        if (adminToken != null && adminToken.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new ConfigException("server.admin.token (NEXUS_ADMIN_TOKEN), if set, must be at least "
                    + MIN_ADMIN_TOKEN_LENGTH + " characters long (current: " + adminToken.length() + ")");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.startsWith("127.");
    }

    private String get(String key, String envKey, String defaultValue) {
        String v = clean(cli.get(key));
        if (v == null) v = clean(sysProps.get(key));
        if (v == null) v = clean(env.get(envKey));
        if (v == null) v = clean(expand(file.getProperty(key)));
        return v != null ? v : defaultValue;
    }

    private String expand(String raw) {
        if (raw == null) return null;
        Matcher m = ENV_REF.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String repl = env.get(m.group(1));
            if (repl == null) {
                // An unset variable makes the setting "not configured" (required settings then fail with their own message).
                log.warn("Configuration references environment variable ${{}} which is not set; treating the setting as unset", m.group(1));
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String clean(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private int port(String key, String envKey, String def) {
        return intValue(key, envKey, def, 1, 65535);
    }

    private int intValue(String key, String envKey, String def, int min, int max) {
        String raw = get(key, envKey, def);
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) {
                throw new ConfigException(key + " must be between " + min + " and " + max + " but was " + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer but was '" + raw + "'");
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--") && arg.contains("=")) {
                    String[] parts = arg.substring(2).split("=", 2);
                    map.put(parts[0], parts[1]);
                }
            }
        }
        return map;
    }

    private static Map<String, String> systemProperties() {
        Map<String, String> map = new HashMap<>();
        System.getProperties().forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return map;
    }

    private static Properties loadFile() {
        Properties props = new Properties();
        File local = new File("application.properties");
        if (local.exists()) {
            try (InputStream is = new FileInputStream(local)) {
                props.load(is);
                log.info("Loaded configuration file {}", local.getAbsolutePath());
            } catch (Exception e) {
                throw new ConfigException("Cannot read " + local.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return props;
    }

    // ---- accessors ----
    public String getServerId() { return serverId; }
    public String getAdvertisedHost() { return advertisedHost; }
    public int getRmiRegistryPort() { return rmiRegistryPort; }
    public int getRmiServicePort() { return rmiServicePort; }
    /** May be null: the administrative API is then disabled. */
    public String getAdminToken() { return adminToken; }
    public String getReplicationSecret() { return replicationSecret; }
    public String getPeerId() { return peerId; }
    public String getPeerHost() { return peerHost; }
    public int getPeerRegistryPort() { return peerRegistryPort; }
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public boolean isSchemaAutoApply() { return schemaAutoApply; }
    public int getReplicationBatchSize() { return replicationBatchSize; }
    public long getReplicationPollMillis() { return replicationPollMillis; }
    public int getCatchUpPageSize() { return catchUpPageSize; }
    public int getMaxRejectedAttempts() { return maxRejectedAttempts; }
    public long getBackoffBaseMillis() { return backoffBaseMillis; }
    public long getBackoffMaxMillis() { return backoffMaxMillis; }
    public boolean isSslEnabled() { return sslEnabled; }
    public boolean isSingleSessionEnabled() { return singleSessionEnabled; }
    /** Human readable branch served by this node (shown to customers and operators). */
    public String getBranchName() { return branchName; }
    /** Time zone that defines "today" for the daily withdrawal/transfer limit. */
    public java.time.ZoneId getBusinessZone() { return businessZone; }
    public String getSslKeyStorePath() { return sslKeyStorePath; }
    public String getSslKeyStorePassword() { return sslKeyStorePassword; }
    public String getSslTrustStorePath() { return sslTrustStorePath; }
    public String getSslTrustStorePassword() { return sslTrustStorePassword; }
    public boolean isSslNeedClientAuth() { return sslNeedClientAuth; }
    public boolean isPeerSslEnabled() { return peerSslEnabled; }
}
