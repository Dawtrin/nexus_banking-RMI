package com.nexus.banking.client.model;

/** High-level connection state of the banking app. */
public enum ConnectionState {
    DISCONNECTED("Chưa kết nối", "#94A3B8"),
    CONNECTING("Đang kết nối...", "#F59E0B"),
    CONNECTED("Đã kết nối", "#22C55E"),
    RECONNECTING("Đang kết nối lại...", "#F59E0B"),
    FAILED("Kết nối thất bại", "#F43F5E"),
    EVICTED("Phiên đã bị đóng", "#F43F5E");

    private final String label;
    private final String colorHex;

    ConnectionState(String label, String colorHex) {
        this.label = label;
        this.colorHex = colorHex;
    }

    public String getLabel() { return label; }

    public String getColorHex() { return colorHex; }
}
