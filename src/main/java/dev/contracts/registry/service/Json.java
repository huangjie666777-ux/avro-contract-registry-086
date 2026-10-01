package dev.contracts.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    static JsonNode parse(String raw) {
        try { return MAPPER.readTree(raw); }
        catch (Exception e) { throw new InvalidSchemaException("request/schema body is not valid JSON", e); }
    }
}
