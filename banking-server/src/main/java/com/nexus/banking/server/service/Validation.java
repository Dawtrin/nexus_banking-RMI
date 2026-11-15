package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.Money;
import com.nexus.banking.contract.dto.TransactionDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Server-side input validation. Every method returns null when valid, otherwise a short reason. */
public final class Validation {
    public static final int MAX_NOTE = 100;
    public static final int MAX_PAGE = 200;

    private static final Pattern ACCOUNT = Pattern.compile("^\\d{6,20}$");
    private static final Pattern PIN = Pattern.compile("^\\d{4,8}$");
    private static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9\\-]{8,64}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");
    private static final Pattern BRANCH_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");

    private Validation() {
    }

    public static String accountNumber(String v) {
        return v != null && ACCOUNT.matcher(v).matches() ? null : "Số tài khoản phải gồm 6-20 chữ số";
    }

    public static String pin(String v) {
        return v != null && PIN.matcher(v).matches() ? null : "Mã PIN phải gồm 4-8 chữ số";
    }

    public static String instanceId(String v) {
        return v != null && INSTANCE_ID.matcher(v).matches() ? null : "clientInstanceId must be 8-64 characters: letters, digits, '-'";
    }

    public static String requestId(String v) {
        return v != null && REQUEST_ID.matcher(v).matches() ? null : "requestId must be 1-64 characters: letters, digits, '_', '-'";
    }

    public static String amount(java.math.BigDecimal v) {
        return Money.validateAmount(v);
    }

    /** Returns the normalized note (CR removed, trimmed, possibly empty). */
    public static String normalizeNote(String raw) {
        return raw == null ? "" : raw.replace("\r", "").replace("\n", " ").trim();
    }

    public static String note(String raw) {
        String t = normalizeNote(raw);
        if (t.length() > MAX_NOTE) return "Nội dung không quá " + MAX_NOTE + " ký tự";
        for (int i = 0; i < t.length(); i++) {
            if (Character.isISOControl(t.charAt(i))) return "Nội dung chứa ký tự điều khiển";
        }
        return null;
    }

    public static String pageSize(int limit) {
        return limit >= 1 && limit <= MAX_PAGE ? null : "limit must be between 1 and " + MAX_PAGE;
    }

    public static String ownerName(String v) {
        return v != null && v.trim().length() >= 2 && v.trim().length() <= 100 && v.chars().noneMatch(Character::isISOControl)
                ? null : "Tên chủ tài khoản phải gồm 2-100 ký tự";
    }

    public static String branchId(String v) {
        return v != null && BRANCH_ID.matcher(v).matches() ? null : "branchId must be 1-32 characters: letters, digits, '_', '-'";
    }

    public static String actor(String v) {
        return v != null && v.trim().length() >= 1 && v.trim().length() <= 40 && v.chars().noneMatch(Character::isISOControl)
                ? null : "operator name must be 1-40 characters";
    }

    public static String sessionIdFormat(String v) {
        return v != null && v.length() >= 8 && v.length() <= 64 && v.matches("[A-Za-z0-9\\-]+") ? null : "invalid session id";
    }

    /** Structural validation of a ledger entry received from the peer branch. */
    public static String replicatedEntry(TransactionDTO t, String expectedOrigin, String localServerId) {
        if (t == null) return "null entry";
        if (t.getTransactionId() == null || !REQUEST_ID.matcher(t.getTransactionId()).matches()) return "invalid transactionId";
        if (t.getOriginServerId() == null || !NODE_ID.matcher(t.getOriginServerId()).matches()) return "invalid originServerId";
        if (!t.getOriginServerId().equals(expectedOrigin)) return "origin '" + t.getOriginServerId() + "' does not match caller";
        if (t.getOriginServerId().equals(localServerId)) return "entry claims this node as origin";
        if (t.getOriginSequence() <= 0) return "originSequence must be positive";
        if (accountNumber(t.getAccountNumber()) != null) return "invalid accountNumber";
        if (t.getReferenceAccount() != null && !t.getReferenceAccount().isEmpty() && accountNumber(t.getReferenceAccount()) != null) {
            return "invalid referenceAccount";
        }
        if (t.getClientRequestId() != null && !REQUEST_ID.matcher(t.getClientRequestId()).matches()) return "invalid clientRequestId";
        if (t.getNote().length() > 200) return "note too long";
        if (t.getCreatedAt() == null || t.getCreatedAt().isAfter(Instant.now().plus(Duration.ofDays(1)))) return "invalid createdAt";
        return Money.validateAmount(t.getAmount());
    }
}
