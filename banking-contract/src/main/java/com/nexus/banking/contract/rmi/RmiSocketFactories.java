package com.nexus.banking.contract.rmi;

import java.rmi.server.RMIClientSocketFactory;

/**
 * Utility factory for constructing standard and SSL-enabled RMI client socket factories.
 */
public final class RmiSocketFactories {

    private RmiSocketFactories() {
    }

    /**
     * Returns an {@link RMIClientSocketFactory} configured with default timeouts.
     *
     * @param ssl whether to wrap connections in TLS/SSL
     * @return the appropriate socket factory
     */
    public static RMIClientSocketFactory clientFactory(boolean ssl) {
        return ssl ? new SslTimeoutClientSocketFactory() : new TimeoutClientSocketFactory();
    }

    /**
     * Returns an {@link RMIClientSocketFactory} with explicit timeouts and SSL options.
     *
     * @param ssl              whether to wrap connections in TLS/SSL
     * @param connectTimeoutMs connect timeout in milliseconds
     * @param readTimeoutMs    read timeout in milliseconds
     * @param trustSelfSigned  whether to accept self-signed certificates in dev mode
     * @return the configured socket factory
     */
    public static RMIClientSocketFactory clientFactory(boolean ssl, int connectTimeoutMs, int readTimeoutMs, boolean trustSelfSigned) {
        if (ssl) {
            return new SslTimeoutClientSocketFactory(connectTimeoutMs, readTimeoutMs, trustSelfSigned);
        } else {
            return new TimeoutClientSocketFactory(connectTimeoutMs, readTimeoutMs);
        }
    }
}
