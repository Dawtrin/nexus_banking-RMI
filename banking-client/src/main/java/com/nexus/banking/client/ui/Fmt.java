package com.nexus.banking.client.ui;

import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatting and small node factories shared by the screens. */
final class Fmt {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE);
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);

    private Fmt() {
    }

    static String dateTime(Instant i) { return i == null ? "" : DATE_TIME.format(i); }

    static String shortTime(Instant i) { return i == null ? "" : SHORT.format(i); }

    static String typeIcon(TransactionType t) {
        return switch (t) {
            case DEPOSIT -> "↓";
            case WITHDRAW -> "↑";
            case TRANSFER -> "→";
            case TRANSFER_IN -> "←";
        };
    }

    static String typeColor(TransactionType t) {
        return t.isCredit() ? "#34D399" : "#FB7185";
    }

    static String typeTint(TransactionType t) {
        return t.isCredit() ? "rgba(52,211,153,0.16)" : "rgba(251,113,133,0.16)";
    }

    static String title(TransactionDTO t) {
        return switch (t.getType()) {
            case DEPOSIT -> "Nạp tiền";
            case WITHDRAW -> "Rút tiền";
            case TRANSFER -> "Chuyển đến " + t.getReferenceAccount();
            case TRANSFER_IN -> "Nhận từ " + t.getReferenceAccount();
        };
    }

    static Label chip(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", "chip-" + kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    static Label syncChip(TransactionSyncState s) {
        return switch (s) {
            case SYNCED -> chip("✓ Đã đồng bộ", "ok");
            case PENDING -> chip("◔ Chờ đồng bộ", "warn");
            case FROM_PEER -> chip("⇄ Từ chi nhánh khác", "info");
            case FAILED -> chip("! Lỗi đồng bộ", "danger");
        };
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] p = name.trim().split("\\s+");
        return (p.length == 1 ? p[0].substring(0, 1) : p[p.length - 2].substring(0, 1) + p[p.length - 1].substring(0, 1)).toUpperCase();
    }
}
