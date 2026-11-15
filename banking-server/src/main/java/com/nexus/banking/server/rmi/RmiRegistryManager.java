package com.nexus.banking.server.rmi;

import com.nexus.banking.contract.rmi.RmiSocketFactories;
import com.nexus.banking.server.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the RMI registry and service exports. Services are exported on the fixed service port with bounded
 * connect/read timeouts and optional TLS/SSL encryption.
 * Ports in use: registry port (default 1099) and one shared service port (default 1100) for all services.
 */
public class RmiRegistryManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RmiRegistryManager.class);

    public static final String BANKING_SERVICE_NAME = "NexusBankingService";
    public static final String REPLICATION_SERVICE_NAME = "NexusReplicationService";
    public static final String MONITORING_SERVICE_NAME = "NexusMonitoringService";
    public static final String ADMIN_SERVICE_NAME = "NexusAdminService";

    private final ServerConfig config;
    private final RMIClientSocketFactory clientFactory;
    private final RMIServerSocketFactory serverFactory;
    private Registry registry;
    private boolean createdLocally = false;
    private final List<Remote> exportedServices = new ArrayList<>();

    public RmiRegistryManager(ServerConfig config) {
        this.config = config;
        this.clientFactory = RmiSocketFactories.clientFactory(config.isSslEnabled());
        if (config.isSslEnabled()) {
            this.serverFactory = new SslRMIServerSocketFactory(null, null, config.isSslNeedClientAuth());
            log.info("Initialized RMI Registry Manager with TLS/SSL encryption (client auth={})",
                    config.isSslNeedClientAuth());
        } else {
            this.serverFactory = null;
        }
    }

    public synchronized void start() throws Exception {
        String advertisedHost = config.getAdvertisedHost();
        System.setProperty("java.rmi.server.hostname", advertisedHost);
        log.info("Configured java.rmi.server.hostname = {}", advertisedHost);
        int registryPort = config.getRmiRegistryPort();
        try {
            if (config.isSslEnabled()) {
                this.registry = LocateRegistry.createRegistry(registryPort, clientFactory, serverFactory);
                log.info("Created local TLS/SSL-secured RMI Registry on port {}", registryPort);
            } else {
                this.registry = LocateRegistry.createRegistry(registryPort);
                log.info("Created local RMI Registry on port {}", registryPort);
            }
            this.createdLocally = true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create RMI registry on port " + registryPort
                    + " (already in use?): " + e.getMessage(), e);
        }
    }

    public synchronized <T extends Remote> Remote exportAndBind(String serviceName, T impl, int exportPort) throws Exception {
        if (registry == null) {
            throw new IllegalStateException("RMI Registry is not started");
        }
        Remote stub = UnicastRemoteObject.exportObject(impl, exportPort, clientFactory, serverFactory);
        registry.rebind(serviceName, stub);
        exportedServices.add(impl);
        log.info("Bound service '{}' (service port {}, ssl={})", serviceName, exportPort, config.isSslEnabled());
        return stub;
    }

    @Override
    public synchronized void close() {
        log.info("Shutting down RMI services...");
        for (Remote service : exportedServices) {
            try {
                UnicastRemoteObject.unexportObject(service, true);
            } catch (Exception e) {
                log.warn("Error unexporting service: {}", e.getMessage());
            }
        }
        exportedServices.clear();
        if (createdLocally && registry != null) {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (Exception e) {
                log.warn("Error unexporting RMI Registry: {}", e.getMessage());
            }
        }
    }
}
