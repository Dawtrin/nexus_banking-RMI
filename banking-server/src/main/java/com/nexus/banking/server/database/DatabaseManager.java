package com.nexus.banking.server.database;

import com.nexus.banking.contract.dto.DatabaseHealthDTO;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.server.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the connection pool and the connectivity/schema state. The pool is created lazily-tolerant (no
 * fail-fast), so a database that is down at start-up or later is retried automatically: every
 * {@link #getConnection()} re-probes when the last state was "down", and a background probe refreshes the
 * health shown to administrators. {@link #isConnected()} is a pure read of the last probe result.
 */
public class DatabaseManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final long PROBE_INTERVAL_SECONDS = 5;

    private final DatabaseConfig config;
    private final boolean schemaAuto;
    private final HikariDataSource dataSource;
    private final ScheduledExecutorService prober;

    private volatile boolean connected = false;
    private volatile boolean schemaReady = false;
    private volatile String lastError = "not probed yet";
    private volatile Instant lastCheckedAt = Instant.now();

    public DatabaseManager(DatabaseConfig config, boolean schemaAuto) {
        this.config = config;
        this.schemaAuto = schemaAuto;
        if (!config.isEnabled()) {
            this.dataSource = null;
            this.prober = null;
            this.lastError = "database disabled by configuration (RAM-only demo mode)";
            return;
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("nexus-banking-db");
        hc.setJdbcUrl(config.getJdbcUrl());
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(config.getPoolMaxSize());
        hc.setMinimumIdle(Math.min(2, config.getPoolMaxSize()));
        hc.setConnectionTimeout(3000);
        hc.setValidationTimeout(2000);
        hc.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hc);
        this.prober = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-db-probe");
            t.setDaemon(true);
            return t;
        });
        probe();
        prober.scheduleWithFixedDelay(this::probe, PROBE_INTERVAL_SECONDS, PROBE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /** Last probe result; never mutates state. */
    public boolean isConnected() {
        return config.isEnabled() && connected && schemaReady;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    /** Returns a pooled connection or throws; callers must treat the exception as "operation not stored". */
    public Connection getConnection() throws SQLException {
        if (!config.isEnabled()) {
            throw new SQLException("Database is disabled by configuration");
        }
        if (!schemaReady) {
            probe();
            if (!schemaReady) {
                throw new SQLException("Database unavailable: " + lastError);
            }
        }
        try {
            Connection c = dataSource.getConnection();
            if (!connected) {
                connected = true;   // a pooled connection was handed out: the database is reachable again
                lastError = "";
                log.info("Database connection re-established");
            }
            return c;
        } catch (SQLException e) {
            markFailure(e);
            throw e;
        }
    }

    /** Callers report failures they observed so the state reflects reality before the next probe. */
    public void reportFailure(Exception e) {
        markFailure(e);
    }

    private synchronized void probe() {
        try (Connection c = dataSource.getConnection()) {
            if (!schemaReady) {
                if (schemaAuto) {
                    SchemaManager.apply(c);
                } else {
                    SchemaManager.verify(c);
                }
                schemaReady = true;
                log.info("Database schema ready ({})", schemaAuto ? "applied" : "verified");
            }
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            if (!connected) log.info("Database connection established");
            connected = true;
            lastError = "";
        } catch (SQLException | RuntimeException e) {
            markFailure(e);
        } finally {
            lastCheckedAt = Instant.now();
        }
    }

    private void markFailure(Exception e) {
        if (connected) log.error("Database connection lost: {}", e.getMessage());
        else log.warn("Database not available: {}", e.getMessage());
        connected = false;
        lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        lastCheckedAt = Instant.now();
    }

    public DatabaseHealthDTO getHealth() {
        if (!config.isEnabled()) {
            return new DatabaseHealthDTO(config.getDatabaseName(), false, ServerStatus.UNKNOWN, 0, 0, 0,
                    lastError, lastCheckedAt, "RAM_ONLY_DEMO");
        }
        int active = 0, idle = 0, total = 0;
        HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
        if (mx != null) {
            active = mx.getActiveConnections();
            idle = mx.getIdleConnections();
            total = mx.getTotalConnections();
        }
        boolean ok = isConnected();
        return new DatabaseHealthDTO(config.getDatabaseName(), ok, ok ? ServerStatus.ONLINE : ServerStatus.UNREACHABLE,
                active, idle, total, lastError, lastCheckedAt, ok ? "MYSQL_DURABLE" : "DB_UNAVAILABLE");
    }

    @Override
    public void close() {
        if (prober != null) prober.shutdownNow();
        if (dataSource != null) dataSource.close();
    }
}
