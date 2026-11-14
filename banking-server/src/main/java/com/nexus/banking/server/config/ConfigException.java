package com.nexus.banking.server.config;

/** Thrown when the server configuration is missing or invalid; the server must not start. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
