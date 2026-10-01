package dev.contracts.registry.service;

/** 400 — malformed JSON, bad schema, illegal default, invalid request shape. */
public class InvalidSchemaException extends RegistryException {
    public InvalidSchemaException(String message) { super(400, "INVALID_SCHEMA", message); }
    public InvalidSchemaException(String message, Throwable cause) { super(400, "INVALID_SCHEMA", message + ": " + cause.getMessage()); }
}
