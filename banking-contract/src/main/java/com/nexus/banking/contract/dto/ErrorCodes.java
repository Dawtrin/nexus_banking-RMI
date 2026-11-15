package com.nexus.banking.contract.dto;

/** Stable error codes carried in {@link OperationResult#getErrorCode()}. */
public final class ErrorCodes {
    public static final String VALIDATION = "VALIDATION_ERROR";
    public static final String INVALID_SESSION = "INVALID_SESSION";
    public static final String SESSION_EVICTED = "SESSION_EVICTED";
    public static final String AUTH_FAILED = "AUTH_FAILED";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String NOT_ACCOUNT_OWNER = "NOT_ACCOUNT_OWNER";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String LIMIT_EXCEEDED = "DAILY_LIMIT_EXCEEDED";
    public static final String SAME_ACCOUNT = "SAME_ACCOUNT";
    public static final String REQUEST_ID_REUSED = "REQUEST_ID_REUSED";
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String ADMIN_DISABLED = "ADMIN_DISABLED";
    public static final String PEER_REJECTED = "PEER_REJECTED";
    public static final String INTERNAL = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
