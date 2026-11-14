package com.nexus.banking.contract.rmi;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;

/**
 * RMI client socket factory applying a connect timeout and a read timeout. Servers export remote
 * objects with this factory, so the (serialized) factory travels inside every stub and the clients and
 * peer nodes get bounded remote calls without relying on JDK-internal system properties.
 */
public final class TimeoutClientSocketFactory implements RMIClientSocketFactory, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TimeoutClientSocketFactory() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public TimeoutClientSocketFactory(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TimeoutClientSocketFactory other
                && other.connectTimeoutMs == connectTimeoutMs && other.readTimeoutMs == readTimeoutMs;
    }

    @Override
    public int hashCode() {
        return 31 * connectTimeoutMs + readTimeoutMs;
    }
}
