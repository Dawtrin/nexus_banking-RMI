package com.nexus.banking.contract.dto;

/** Stable error codes carried in {@link OperationResult#getErrorCode()}. */
public final class ErrorCodes {
    public static final String VALIDATION = "VALIDATION_ERROR";
    public static final String INVALID_SESSION = "INVALID_SESSION";
    public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
    public static final String NOT_IN_ROOM = "NOT_IN_ROOM";
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String ADMIN_DISABLED = "ADMIN_DISABLED";
    public static final String PEER_REJECTED = "PEER_REJECTED";
    public static final String SESSION_EVICTED = "SESSION_EVICTED";
    public static final String INTERNAL = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
