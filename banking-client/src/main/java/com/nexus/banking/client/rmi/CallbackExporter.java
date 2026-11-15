package com.nexus.banking.client.rmi;

import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.rmi.TimeoutClientSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;

/**
 * Exports the app's {@link BankingCallback} so the branch server can push balance changes to this device. The branch
 * must be able to connect BACK to this machine, so the address advertised inside the stub is the local address that
 * the OS uses to reach the branch (not whatever hostname Windows happens to report). With TLS the callback endpoint
 * itself needs a certificate: the same keystore the server uses can be passed with -Djavax.net.ssl.keyStore.
 */
public final class CallbackExporter implements com.nexus.banking.client.core.BankingClientCore.CallbackExporter {
    private static final Logger log = LoggerFactory.getLogger(CallbackExporter.class);

    private final java.util.function.BooleanSupplier ssl;
    private volatile String advertisedHost;

    public CallbackExporter(java.util.function.BooleanSupplier ssl) {
        this.ssl = ssl;
    }

    /** Detects the local address used to reach {@code serverHost} and advertises it (unless the user forced one). */
    @Override
    public void prepareFor(String serverHost, int serverPort) {
        String forced = System.getProperty("nexus.client.callback.host");
        if (forced != null && !forced.isBlank()) {
            advertisedHost = forced;
        } else {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(serverHost, serverPort), 3000);
                advertisedHost = probe.getLocalAddress().getHostAddress();
            } catch (Exception e) {
                log.warn("Could not detect callback address towards {}:{} ({}); using the default", serverHost, serverPort, e.getMessage());
                return;
            }
        }
        System.setProperty("java.rmi.server.hostname", advertisedHost);
        log.info("Callback endpoint will be advertised as {}", advertisedHost);
    }

    @Override
    public boolean canExport() {
        if (!ssl.getAsBoolean()) return true;
        return System.getProperty("javax.net.ssl.keyStore") != null;
    }

    @Override
    public BankingCallback export(BankingCallback impl) throws Exception {
        RMIClientSocketFactory csf = new TimeoutClientSocketFactory();
        RMIServerSocketFactory ssf = ssl.getAsBoolean() ? new SslRMIServerSocketFactory(null, null, false) : null;
        if (ssl.getAsBoolean()) csf = com.nexus.banking.contract.rmi.RmiSocketFactories.clientFactory(true);
        return (BankingCallback) UnicastRemoteObject.exportObject(impl, 0, csf, ssf);
    }

    @Override
    public void unexport(BankingCallback impl) {
        try {
            UnicastRemoteObject.unexportObject(impl, true);
        } catch (Exception ignored) {
            // already gone
        }
    }
}
