package com.nexus.banking.contract.rmi;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * An RMI client socket factory that establishes secure TLS/SSL encrypted connections with configurable
 * connect and read timeouts.
 * <p>
 * Stubs exported by an SSL-enabled server carry this factory. When deserialized on clients and peer nodes,
 * remote invocations transparently travel over TLS.
 * <p>
 * In development or non-CA lab environments, setting {@code -Dnexus.ssl.trustSelfSigned=true} or passing
 * {@code trustSelfSigned=true} enables permissive certificate validation for self-signed certificates.
 */
public final class SslTimeoutClientSocketFactory implements RMIClientSocketFactory, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 20_000;

    private static volatile SSLSocketFactory TRUST_ALL_SOCKET_FACTORY;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean trustSelfSigned;

    public SslTimeoutClientSocketFactory() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, false);
    }

    public SslTimeoutClientSocketFactory(boolean trustSelfSigned) {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, trustSelfSigned);
    }

    public SslTimeoutClientSocketFactory(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, false);
    }

    public SslTimeoutClientSocketFactory(int connectTimeoutMs, int readTimeoutMs, boolean trustSelfSigned) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.trustSelfSigned = trustSelfSigned;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        SSLSocketFactory factory = resolveSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw e;
        }
    }

    private SSLSocketFactory resolveSocketFactory() {
        boolean permissive = trustSelfSigned
                || Boolean.getBoolean("nexus.ssl.trustSelfSigned")
                || Boolean.getBoolean("nexus.ssl.trustAll");
        if (!permissive) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        if (TRUST_ALL_SOCKET_FACTORY == null) {
            synchronized (SslTimeoutClientSocketFactory.class) {
                if (TRUST_ALL_SOCKET_FACTORY == null) {
                    try {
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, new TrustManager[]{new PermissiveTrustManager()}, new SecureRandom());
                        TRUST_ALL_SOCKET_FACTORY = ctx.getSocketFactory();
                    } catch (Exception e) {
                        return (SSLSocketFactory) SSLSocketFactory.getDefault();
                    }
                }
            }
        }
        return TRUST_ALL_SOCKET_FACTORY;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public boolean isTrustSelfSigned() {
        return trustSelfSigned;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SslTimeoutClientSocketFactory other
                && other.connectTimeoutMs == connectTimeoutMs
                && other.readTimeoutMs == readTimeoutMs
                && other.trustSelfSigned == trustSelfSigned;
    }

    @Override
    public int hashCode() {
        int result = 31 * connectTimeoutMs + readTimeoutMs;
        return 31 * result + (trustSelfSigned ? 1 : 0) + 7919;
    }

    private static class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
