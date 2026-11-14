package com.nexus.banking.client.model;

/**
 * High-level connection state of the desktop client with Opera GX status telemetry.
 */
public enum ConnectionState {
    DISCONNECTED("Disconnected", "#FA174A"),
    CONNECTING("Connecting...", "#F59E0B"),
    CONNECTED("Connected", "#22C55E"),
    RECONNECTING("Reconnecting...", "#F59E0B"),
    FAILED("Connection Failed", "#EF4444");

    private final String label;
    private final String colorHex;

    ConnectionState(String label, String colorHex) {
        this.label = label;
        this.colorHex = colorHex;
    }

    public String getLabel() {
        return label;
    }

    public String getColorHex() {
        return colorHex;
    }
}
