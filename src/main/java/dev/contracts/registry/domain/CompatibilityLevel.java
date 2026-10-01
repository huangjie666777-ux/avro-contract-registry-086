package dev.contracts.registry.domain;

public enum CompatibilityLevel {
    BACKWARD, FORWARD, FULL;

    public static CompatibilityLevel from(String raw) {
        if (raw == null) throw new IllegalArgumentException("compatibility is required (BACKWARD, FORWARD or FULL)");
        try { return valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported compatibility level: " + raw);
        }
    }
}
