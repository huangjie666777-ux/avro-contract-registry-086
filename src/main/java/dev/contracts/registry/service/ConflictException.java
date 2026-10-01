package dev.contracts.registry.service;

/** 409 — expectedLatestVersion no longer matches the stored tip. */
public class ConflictException extends RegistryException {
    public ConflictException(String message) { super(409, "VERSION_CONFLICT", message); }
}
