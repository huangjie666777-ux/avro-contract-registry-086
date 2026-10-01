package dev.contracts.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.contracts.registry.domain.ResolvedSchema;
import dev.contracts.registry.domain.SchemaRef;
import dev.contracts.registry.domain.StoredSchema;
import dev.contracts.registry.repo.SchemaRepository;
import org.apache.avro.Schema;

import java.sql.Connection;
import java.util.*;

/**
 * Resolves cross-subject {@code $ref} markers into an inlined, valid Avro document.
 *
 * Reference form (any place a type is allowed):
 * <pre>{@code { "$ref": "other-subject" } }</pre> (pinned via {@code version})
 * <pre>{@code { "$ref": "other-subject", "version": 7 } }</pre>
 * <p>A batch may reference a new subject in the same batch (expectedLatestVersion 0 or N).
 * The complete transitive closure is loaded; missing references, duplicate full names
 * coming from different subjects, and cross-subject dependency cycles are rejected.
 */
public class ReferenceResolver {

    public static final String REF_MARKER = "$ref";

    public record PendingSubject(String subject, String rawSchema) {}

    record DocKey(String subject, int version) {}

    /**
     * Resolves one subject. {@code pending} maps subject name to its raw in-batch JSON
     * and assigned (tentative) versions; committed docs are loaded from the repository.
     */
    public ResolvedSchema resolve(Connection tx, SchemaRepository repo, String rootSubject,
                                  String rootRaw, Map<String, Integer> pendingVersions,
                                  Map<String, String> pendingRaw) {
        // 1. Build closure: doc key -> raw JSON tree. Detect missing refs and version pinning.
        Map<DocKey, JsonNode> docs = new LinkedHashMap<>();
        Map<String, Integer> pinnedBySubject = new HashMap<>();
        Deque<DocKey> queue = new ArrayDeque<>();
        queue.add(new DocKey(rootSubject, -1));

        while (!queue.isEmpty()) {
            DocKey key = queue.poll();
            String subject = key.subject();
            int version = key.version();
            JsonNode raw;
            int resolvedVersion;
            if (pendingRaw.containsKey(subject)) {
                if (version > 0)
                    throw new ReferenceException("subject " + subject + " references in-batch subject with an explicit version; omit version");
                resolvedVersion = pendingVersions.get(subject);
                raw = Json.MAPPER.valueToTree(Json.MAPPER.readTree(pendingRaw.get(subject))); // deep copy later on use
            } else {
                Integer latest;
                try { latest = repo.latestVersion(tx, subject); }
                catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                if (latest == null)
                    throw new ReferenceException("missing referenced subject: " + subject);
                resolvedVersion = version > 0 ? version : latest;
                if (resolvedVersion > latest)
                    throw new ReferenceException("subject " + subject + " has no version " + resolvedVersion
                            + " (latest=" + latest + ")");
                StoredSchema stored;
                try { stored = repo.load(tx, subject, resolvedVersion); }
                catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                if (stored == null)
                    throw new ReferenceException("subject " + subject + " version " + resolvedVersion + " not found");
                raw = Json.parse(stored.schemaJson());
            }
            DocKey resolvedKey = new DocKey(subject, resolvedVersion);
            Integer prior = pinnedBySubject.put(subject, resolvedVersion);
            if (prior != null && prior != resolvedVersion)
                throw new ReferenceException("subject " + subject + " referenced at conflicting versions "
                        + prior + " and " + resolvedVersion);
            if (!docs.containsKey(resolvedKey)) {
                docs.put(resolvedKey, raw.deepCopy());
                for (JsonNode ref : collectRefs(raw)) {
                    String refSubject = ref.path(REF_MARKER).asText("");
                    if (refSubject.isEmpty())
                        throw new InvalidSchemaException("$ref marker requires a non-empty subject name");
                    int refVersion = ref.path("version").asInt(0);
                    queue.add(new DocKey(refSubject, refVersion));
                }
            }
        }

        // 2. Cross-subject cycle detection on the dependency graph of resolved docs.
        detectCycles(docs);

        // 3. Name-clash detection: same full name defined by two different subjects is rejected.
        Map<String, String> fullNameOwner = new HashMap<>();
        for (Map.Entry<DocKey, JsonNode> e : docs.entrySet())
            for (String fullName : collectFullNames(e.getValue(), ""))
                if (fullNameOwner.put(fullName, e.getKey().subject()) != null
                        && !fullNameOwner.get(fullName).equals(e.getKey().subject()))
                    throw new ReferenceException("name conflict: " + fullName + " is defined by multiple subjects ("
                            + fullNameOwner.get(fullName) + ", " + e.getKey().subject() + ")");

        // 4. Inline every doc into one self-contained Avro schema.
        JsonNode rootTree = docs.get(new DocKey(rootSubject, pendingVersions.getOrDefault(rootSubject, -1)));
        if (rootTree == null) throw new ReferenceException("internal: root doc missing");
        // Top-level $ref
        if (isRef(rootTree)) {
            rootTree = inlineRef(rootTree.deepCopy(), docs, pendingVersions);
        } else {
            rootTree = rootTree.deepCopy();
            inlineAll(rootTree, docs, pendingVersions, "");
        }
        String inlined = rootTree.toString();
        Schema parsed = SchemaValidator.parseInlined(inlined);

        List<SchemaRef> pinned = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pinnedBySubject.entrySet())
            if (!e.getKey().equals(rootSubject)) pinned.add(new SchemaRef(e.getKey(), e.getValue()));
        pinned.sort(Comparator.comparing(SchemaRef::subject).thenComparingInt(SchemaRef::version));
        return new ResolvedSchema(parsed.toString(), pinned);
    }

    private static boolean isRef(JsonNode node) {
        return node != null && node.isObject() && node.has(REF_MARKER);
    }

    private JsonNode inlineRef(JsonNode refNode, Map<DocKey, JsonNode> docs, Map<String, Integer> pending) {
        String subject = refNode.path(REF_MARKER).asText();
        int requested = refNode.path("version").asInt(0);
        DocKey key = lookupKey(docs, subject, requested, pending);
        JsonNode target = docs.get(key).deepCopy();
        if (isRef(target)) return inlineRef(target, docs, pending);
        inlineAll(target, docs, pending, "");
        return target;
    }

    private static DocKey lookupKey(Map<DocKey, JsonNode> docs, String subject, int requested,
                                    Map<String, Integer> pending) {
        int want = requested > 0 ? requested
                : (pending.containsKey(subject) ? pending.get(subject) : -1);
        for (DocKey key : docs.keySet())
            if (key.subject().equals(subject) && (want < 0 || key.version() == want)) return key;
        throw new ReferenceException("missing referenced subject: " + subject);
    }

    /** Recursively replaces every type-position $ref with a deep copy of the referenced doc. */
    private void inlineAll(JsonNode node, Map<DocKey, JsonNode> docs,
                           Map<String, Integer> pending, String namespace) {
        if (node == null) return;
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (isRef(child)) ((ArrayNode) node).set(i, inlineRef(child, docs, pending));
                else inlineAll(child, docs, pending, namespace);
            }
        } else if (node.isObject()) {
            for (var it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode child = e.getValue();
                if (isRef(child)) {
                    ((ObjectNode) node).set(key, inlineRef(child, docs, pending));
                } else if (child.isContainerNode()) {
                    String ns = namespace;
                    if (child.isObject() && "record".equals(child.path("type").asText()))
                        ns = SchemaValidator.effectiveNamespace(child, namespace);
                    inlineAll(child, docs, pending, ns);
                }
            }
        }
    }

    static List<JsonNode> collectRefs(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        collectRefs(node, out);
        return out;
    }

    private static void collectRefs(JsonNode node, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has(REF_MARKER)) { out.add(node); return; }
            node.fields().forEachRemaining(e -> collectRefs(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(c -> collectRefs(c, out));
        }
    }

    private void detectCycles(Map<DocKey, JsonNode> docs) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (Map.Entry<DocKey, JsonNode> e : docs.entrySet()) {
            Set<String> deps = new HashSet<>();
            for (JsonNode ref : collectRefs(e.getValue())) deps.add(ref.path(REF_MARKER).asText());
            graph.put(e.getKey().subject(), deps);
        }
        Set<String> done = new HashSet<>();
        Set<String> stack = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String s : graph.keySet()) dfsCycle(s, graph, done, stack, path);
    }

    private void dfsCycle(String s, Map<String, Set<String>> graph, Set<String> done,
                          Set<String> stack, Deque<String> path) {
        if (done.contains(s)) return;
        if (!stack.add(s))
            throw new ReferenceException("cross-subject dependency cycle detected: "
                    + cycleText(path, s));
        path.push(s);
        for (String dep : graph.getOrDefault(s, Set.of())) {
            if (graph.containsKey(dep)) dfsCycle(dep, graph, done, stack, path);
        }
        path.pop();
        stack.remove(s);
        done.add(s);
    }

    private static String cycleText(Deque<String> path, String back) {
        List<String> chain = new ArrayList<>(path);
        Collections.reverse(chain);
        int start = chain.indexOf(back);
        List<String> cycle = start >= 0 ? new ArrayList<>(chain.subList(start, chain.size())) : chain;
        cycle.add(back);
        return String.join(" -> ", cycle);
    }

    /** Collects full names of every named type declared in one raw document (excluding $ref targets). */
    static List<String> collectFullNames(JsonNode node, String namespace) {
        List<String> out = new ArrayList<>();
        collectFullNames(node, namespace, out);
        return out;
    }

    private static void collectFullNames(JsonNode node, String namespace, List<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has(REF_MARKER)) return;
            String type = node.path("type").asText("");
            if ("record".equals(type) || "enum".equals(type) || "fixed".equals(type)) {
                String ns = SchemaValidator.effectiveNamespace(node, namespace);
                String name = node.path("name").asText("");
                out.add(name.contains(".") ? name : (ns.isEmpty() ? name : ns + "." + name));
                namespace = ns;
            }
            node.fields().forEachRemaining(e -> collectFullNames(e.getValue(), namespace, out));
        } else if (node.isArray()) {
            node.forEach(c -> collectFullNames(c, namespace, out));
        }
    }
}
