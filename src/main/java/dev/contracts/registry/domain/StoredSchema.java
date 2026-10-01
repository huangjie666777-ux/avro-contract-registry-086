package dev.contracts.registry.domain;

import java.util.List;

public record StoredSchema(String subject, int version, String schemaJson,
                          List<SchemaRef> references) {}
