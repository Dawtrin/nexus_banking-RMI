package com.nexus.banking.server.store;

/** A storage operation failed; the operation was NOT durably applied. */
public class StorageException extends Exception {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
