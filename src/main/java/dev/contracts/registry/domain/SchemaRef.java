package dev.contracts.registry.domain;

/** A resolved reference to an exact version of another subject. */
public record SchemaRef(String subject, int version) {}
