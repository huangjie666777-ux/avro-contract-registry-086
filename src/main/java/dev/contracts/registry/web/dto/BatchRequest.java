package dev.contracts.registry.web.dto;

import java.util.ArrayList;
import java.util.List;

/** Request body for /precheck and /publish. */
public class BatchRequest {
    public List<SubjectSchema> schemas = new ArrayList<>();
    public String compatibility; // BACKWARD | FORWARD | FULL; per-schema override below

    public static class SubjectSchema {
        public String subject;
        /** Expected latest version currently stored; 0 when the subject is new. */
        public int expectedLatestVersion;
        /** Avro schema as JSON text. */
        public String schema;
        /** Optional per-subject compatibility; falls back to batch-level value. */
        public String compatibility;
    }
}
