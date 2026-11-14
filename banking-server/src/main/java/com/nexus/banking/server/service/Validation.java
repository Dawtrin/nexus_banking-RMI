package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.enums.MessageType;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Server-side input validation. Every method returns null when valid, otherwise a short reason. */
public final class Validation {
    public static final int MAX_CONTENT = 4000;
    public static final int MAX_FILE_CONTENT = 15_000_000;
    public static final int MAX_AVATAR_LENGTH = 1_000_000;
    public static final int MAX_PAGE = 200;

    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_.\\-]{2,32}$");
    private static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9\\-]{8,64}$");
    private static final Pattern ROOM_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");
    private static final Pattern CLIENT_MSG_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

    private Validation() {
    }

    public static String username(String v) {
        return v != null && USERNAME.matcher(v).matches() ? null
                : "username must be 2-32 characters: letters, digits, '_', '.', '-'";
    }

    public static String instanceId(String v) {
        return v != null && INSTANCE_ID.matcher(v).matches() ? null
                : "clientInstanceId must be 8-64 characters: letters, digits, '-'";
    }

    public static String roomId(String v) {
        return v != null && ROOM_ID.matcher(v).matches() ? null : "invalid roomId";
    }

    public static String clientMessageId(String v) {
        return v != null && CLIENT_MSG_ID.matcher(v).matches() ? null
                : "clientMessageId must be 1-64 characters: letters, digits, '_', '-'";
    }

    /** Returns the normalized content (CR removed, trimmed) or null if invalid; reason via {@link #content(String)}. */
    public static String normalizeContent(String raw) {
        if (raw == null) return null;
        String t = raw.replace("\r", "").trim();
        return t.isEmpty() ? null : t;
    }

    public static String content(String raw) {
        String t = normalizeContent(raw);
        if (t == null) return "message content must not be empty";
        if (t.contains("[NEXUS_FILE:")) {
            if (t.length() > MAX_FILE_CONTENT) return "file message content exceeds maximum allowed size";
            return null;
        }
        if (t.length() > MAX_CONTENT) return "message content exceeds " + MAX_CONTENT + " characters";
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\t') return "message content contains control characters";
        }
        return null;
    }

    public static String avatar(String v) {
        if (v == null || v.isEmpty()) return null;
        if (v.length() > MAX_AVATAR_LENGTH) return "avatar data exceeds maximum size";
        return null;
    }

    public static String pageSize(int limit) {
        return limit >= 1 && limit <= MAX_PAGE ? null : "limit must be between 1 and " + MAX_PAGE;
    }

    public static String cursor(long cursor) {
        return cursor >= 0 ? null : "cursor must not be negative";
    }

    /** Structural validation of a message received from a peer node. */
    public static String replicatedMessage(ChatMessageDTO m, String expectedOrigin, String localServerId) {
        if (m == null) return "null message";
        if (m.getMessageId() == null || !CLIENT_MSG_ID.matcher(m.getMessageId()).matches()) {
            return "invalid messageId";
        }
        if (m.getOriginServerId() == null || !NODE_ID.matcher(m.getOriginServerId()).matches()) return "invalid originServerId";
        if (!m.getOriginServerId().equals(expectedOrigin)) return "origin '" + m.getOriginServerId() + "' does not match caller";
        if (m.getOriginServerId().equals(localServerId)) return "message claims this node as origin";
        if (m.getOriginSequence() <= 0) return "originSequence must be positive";
        if (roomId(m.getRoomId()) != null) return "invalid roomId";
        if (m.getSenderId() == null || m.getSenderId().isBlank() || m.getSenderId().length() > 100) return "invalid senderId";
        if (m.getSenderName() == null || m.getSenderName().isBlank() || m.getSenderName().length() > 100) return "invalid senderName";
        if (m.getMessageType() != MessageType.CHAT) return "only CHAT messages are replicated";
        if (m.getCreatedAt() == null || m.getCreatedAt().isAfter(Instant.now().plus(Duration.ofDays(1)))) return "invalid createdAt";
        return content(m.getContent());
    }

    public static String displayName(String v) {
        if (v == null || v.trim().length() < 2 || v.trim().length() > 40) return "display name must be 2-40 characters";
        for (int i = 0; i < v.length(); i++) if (Character.isISOControl(v.charAt(i))) return "display name contains control characters";
        return null;
    }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,120}\\.[^\\s@]{2,}$");

    /** Empty is allowed (email is optional). */
    public static String email(String v) {
        if (v == null || v.isEmpty()) return null;
        return v.length() <= 120 && EMAIL.matcher(v).matches() ? null : "email address is not valid";
    }

    public static String roomName(String v) {
        return v != null && v.trim().length() >= 1 && v.trim().length() <= 60 && v.chars().noneMatch(Character::isISOControl)
                ? null : "room name must be 1-60 characters";
    }

    public static String roomDescription(String v) {
        return v == null || (v.length() <= 200 && v.chars().noneMatch(Character::isISOControl)) ? null : "description must be at most 200 characters";
    }

    public static String actor(String v) {
        return v != null && v.trim().length() >= 1 && v.trim().length() <= 40 && v.chars().noneMatch(Character::isISOControl)
                ? null : "operator name must be 1-40 characters";
    }

    public static String sessionIdFormat(String v) {
        return v != null && v.length() >= 8 && v.length() <= 64 && v.matches("[A-Za-z0-9\\-]+") ? null : "invalid session id";
    }
}
