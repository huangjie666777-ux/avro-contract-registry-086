package dev.contracts.registry.service;

import java.util.List;

/** 422 — schema failed compatibility checks against one or more historical versions. */
public class IncompatibleSchemaException extends RegistryException {
    public final List<Failure> failures;

    public IncompatibleSchemaException(List<Failure> failures) {
        super(422, "INCOMPATIBLE_SCHEMA", failures.size() + " compatibility failure(s)");
        this.failures = failures;
    }

    public record Failure(String subject, int historicalVersion, String direction, String path, String reason) {
        @Override public String toString() {
            return "subject=" + subject + " version=" + historicalVersion + " direction=" + direction
                    + " path=" + (path.isEmpty() ? "<root>" : path) + " reason=" + reason;
        }
    }
}
