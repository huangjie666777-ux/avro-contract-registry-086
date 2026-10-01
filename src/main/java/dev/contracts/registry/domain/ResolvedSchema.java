package dev.contracts.registry.domain;

import java.util.List;

/** A fully parsed schema together with its closed, version-pinned dependency set. */
public record ResolvedSchema(String canonicalJson, List<SchemaRef> references) {}
