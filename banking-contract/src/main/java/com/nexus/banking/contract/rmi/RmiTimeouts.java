package com.nexus.banking.contract.rmi;

/**
 * Bounds the RMI connection handshake. The JDK applies its own handshake timeout (default 60 s) that
 * overrides a socket factory's read timeout while connecting, so it must be lowered through the
 * documented {@code sun.rmi.transport.tcp.handshakeTimeout} property. Call {@link #install()} at the very
 * start of main(), before any RMI class is used (the JDK reads the property once).
 */
public final class RmiTimeouts {
    public static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    private RmiTimeouts() {
    }

    public static void install() {
        install(HANDSHAKE_TIMEOUT_MS);
    }

    public static void install(int handshakeMs) {
        if (System.getProperty("sun.rmi.transport.tcp.handshakeTimeout") == null) {
            System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", String.valueOf(handshakeMs));
        }
    }
}
