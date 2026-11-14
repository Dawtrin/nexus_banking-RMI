package com.nexus.banking.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal TCP forwarder used to simulate a database outage (stop) and recovery (start) on a fixed port. */
public final class TcpProxy implements AutoCloseable {
    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private ServerSocket server;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    public TcpProxy(int listenPort, String targetHost, int targetPort) {
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public synchronized void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", listenPort));
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket upstream = new Socket(targetHost, targetPort);
                    sockets.add(client);
                    sockets.add(upstream);
                    pipe(client, upstream);
                    pipe(upstream, client);
                } catch (IOException e) {
                    // closed or upstream down
                }
            }
        }, "tcp-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void pipe(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // connection torn down
            } finally {
                try {
                    from.close();
                    to.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }, "tcp-proxy-pipe");
        t.setDaemon(true);
        t.start();
    }

    /** Drops the listener and every live connection (a hard outage). */
    public synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // best effort
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        sockets.clear();
    }

    @Override
    public void close() {
        stop();
    }
}
