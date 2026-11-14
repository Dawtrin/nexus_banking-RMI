package com.nexus.banking.control.core;

import com.nexus.banking.contract.remote.AdminRemote;
import com.nexus.banking.contract.remote.MonitoringRemote;
import com.nexus.banking.contract.rmi.RmiSocketFactories;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/** RMI lookup of the monitoring and admin services with bounded timeouts and optional TLS/SSL. */
public final class AdminRmi {
    public static final String MONITORING_SERVICE_NAME = "NexusMonitoringService";
    public static final String ADMIN_SERVICE_NAME = "NexusAdminService";

    private AdminRmi() {
    }

    public static ClusterMonitor.NodeRemotes connect(String host, int port, boolean useSsl) throws Exception {
        Registry registry = LocateRegistry.getRegistry(host, port, RmiSocketFactories.clientFactory(useSsl));
        return new ClusterMonitor.NodeRemotes((MonitoringRemote) registry.lookup(MONITORING_SERVICE_NAME),
                (AdminRemote) registry.lookup(ADMIN_SERVICE_NAME));
    }

    public static ClusterMonitor.NodeRemotes connect(String host, int port) throws Exception {
        return connect(host, port, AdminPreferences.sslEnabled());
    }
}
