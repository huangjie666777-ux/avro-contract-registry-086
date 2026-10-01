package dev.contracts.registry.web.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchResult {
    public String id;
    public List<Assigned> results = new ArrayList<>();
    public boolean dryRun;

    public BatchResult(String id, boolean dryRun) {
        this.id = id;
        this.dryRun = dryRun;
    }

    public static class Assigned {
        public String subject;
        public int version;
        public String compatibility;
        public List<RefDTO> references = new ArrayList<>();
    }

    public static class RefDTO {
        public String subject;
        public int version;
        public RefDTO(String subject, int version) { this.subject = subject; this.version = version; }
    }
}
