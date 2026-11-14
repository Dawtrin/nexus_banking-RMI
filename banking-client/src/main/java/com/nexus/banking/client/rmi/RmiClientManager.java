package com.nexus.banking.client.rmi;

import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.contract.rmi.RmiSocketFactories;
import com.nexus.banking.client.config.ClientPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.ConnectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/** Looks up the chat service on a server's RMI registry with bounded connect/read timeouts and optional SSL/TLS. */
public final class RmiClientManager {
    private static final Logger log = LoggerFactory.getLogger(RmiClientManager.class);
    public static final String CHAT_SERVICE_NAME = "NexusChatService";

    private RmiClientManager() {
    }

    public static ChatRemote connect(String host, int port, boolean useSsl) throws RemoteException {
        log.info("Connecting to RMI registry at {}:{} (ssl={})", host, port, useSsl);
        try {
            Registry registry = LocateRegistry.getRegistry(host, port, RmiSocketFactories.clientFactory(useSsl));
            return (ChatRemote) registry.lookup(CHAT_SERVICE_NAME);
        } catch (ConnectException e) {
            throw new RemoteException("Could not connect to " + host + ":" + port + ". Check host, port, firewall and SSL settings.", e);
        } catch (NotBoundException e) {
            throw new RemoteException("Service '" + e.getMessage() + "' is not bound on " + host + ":" + port, e);
        }
    }

    public static ChatRemote connect(String host, int port) throws RemoteException {
        return connect(host, port, ClientPreferences.sslEnabled());
    }
}
