package com.nexus.banking.client.config;

/** Defaults used when nothing was saved yet. Real hosts are typed on the login screen (or saved preferences). */
public final class ClientConfig {
    public static final String DEFAULT_NODE_A_HOST = "127.0.0.1";
    public static final int DEFAULT_NODE_A_PORT = 1099;
    public static final String DEFAULT_NODE_B_HOST = "127.0.0.1";
    public static final int DEFAULT_NODE_B_PORT = 2099;

    private ClientConfig() {
    }
}
