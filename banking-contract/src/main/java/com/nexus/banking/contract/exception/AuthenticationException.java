package com.nexus.banking.contract.exception;

/**
 * Thrown when administrative authentication or user validation fails.
 */
public class AuthenticationException extends NexusBankingException {
    public AuthenticationException(String message) {
        super("AUTH_FAILED", message);
    }
}
