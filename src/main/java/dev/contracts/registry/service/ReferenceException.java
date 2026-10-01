package dev.contracts.registry.service;

/** 422 — missing reference, name clash, cross-subject cycle. Request itself is well-formed. */
public class ReferenceException extends RegistryException {
    public ReferenceException(String message) { super(422, "UNRESOLVABLE_REFERENCE", message); }
}
