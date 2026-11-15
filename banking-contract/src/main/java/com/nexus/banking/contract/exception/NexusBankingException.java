package com.nexus.banking.contract.exception;

/**
 * Base checked exception for business and communication errors in NEXUS Banking.
 */
public class NexusBankingException extends Exception {
    private final String errorCode;

    public NexusBankingException(String message) {
        super(message);
        this.errorCode = "GENERAL_ERROR";
    }

    public NexusBankingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public NexusBankingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
