package dev.contracts.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.Schema;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses and validates raw Avro schema JSON.
 *
 * The Apache Avro parser accepts some default values that violate the Avro specification
 * (enum symbols, fixed-size byte strings, and union defaults outside the first branch).
 * Those cases are validated explicitly here. {@code logicalType} is rejected everywhere.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /** Parses a fully inlined (reference-free) schema document. */
    public static Schema parseInlined(String json) {
        JsonNode tree = Json.parse(json);
        rejectLogicalType(tree, "");
        Schema schema;
        try {
            schema = new Schema.Parser().parse(json);
        } catch (Exception e) {
            throw new InvalidSchemaException("illegal Avro schema", e);
        }
        validateDefaults(tree, tree, tree.path("namespace").asText(""), "");
        return schema;
    }

    /** Recursively forbids any {@code logicalType} usage. */
    static void rejectLogicalType(JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode lt = node.get("logicalType");
            if (lt != null && !lt.isNull()) {
                throw new InvalidSchemaException("logicalType is not supported at " + (path.isEmpty() ? "<root>" : path));
            }
            node.fields().forEachRemaining(e -> rejectLogicalType(e.getValue(), path + "/" + e.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) rejectLogicalType(node.get(i), path + "/" + i);
        }
    }

    /**
     * Walks every record field and checks its default value against the declared type.
     * Namespace tracking mirrors Avro name resolution for named inline defaults.
     */
    static void validateDefaults(JsonNode root, JsonNode node, String namespace, String path) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray())
                for (int i = 0; i < node.size(); i++)
                    validateDefaults(root, node.get(i), namespace, path + "/" + i);
            return;
        }
        String typeName = node.path("type").asText("");
        if ("record".equals(typeName)) {
            String ns = effectiveNamespace(node, namespace);
            JsonNode fields = node.get("fields");
            if (fields == null || !fields.isArray())
                throw new InvalidSchemaException("record at " + (path.isEmpty() ? "<root>" : path) + " must declare a fields array");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < fields.size(); i++) {
                JsonNode field = fields.get(i);
                String fname = field.path("name").asText("");
                if (!names.add(fname)) throw new InvalidSchemaException("duplicate field name " + fname);
                String fpath = path + "/fields/" + fname;
                validateDefaults(root, field.get("type"), ns, fpath);
                JsonNode def = field.get("default");
                if (def != null) checkDefault(field.get("type"), def, ns, root, fpath);
            }
        } else if ("array".equals(typeName)) {
            validateDefaults(root, node.get("items"), namespace, path + "/items");
        } else if ("map".equals(typeName)) {
            validateDefaults(root, node.get("values"), namespace, path + "/values");
        } else if (typeName.startsWith("")) {
            // union or primitive string form handled by the caller/checkDefault
        }
    }

    static String effectiveNamespace(JsonNode named, String parentNamespace) {
        JsonNode ns = named.get("namespace");
        if (ns != null && !ns.asText("").isEmpty()) return ns.asText();
        String name = named.path("name").asText("");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) return name.substring(0, dot);
        return parentNamespace;
    }

    /** Validates one field default against its Avro type node. */
    static void checkDefault(JsonNode typeNode, JsonNode def, String namespace, JsonNode root, String path) {
        if (typeNode == null) throw new InvalidSchemaException("field " + path + " has no type");
        if (typeNode.isArray()) {
            // Union default: must match the FIRST branch (Avro spec).
            if (typeNode.size() == 0) throw new InvalidSchemaException("empty union at " + path);
            JsonNode first = typeNode.get(0);
            if (!defaultMatches(first, def, namespace, root, path)) {
                throw new InvalidSchemaException("default for union field " + path + " must match its first branch");
            }
            for (int i = 0; i < typeNode.size(); i++)
                validateDefaults(root, typeNode.get(i), namespace, path + "/" + i);
            return;
        }
        if (typeNode.isTextual()) {
            String t = typeNode.asText();
            if (isPrimitive(t)) {
                if (!primitiveDefaultOk(t, def))
                    throw new InvalidSchemaException("default " + def + " at " + path + " is not a valid " + t);
                return;
            }
            // Named reference: resolve to its definition in the document for record defaults.
            String full = t.contains(".") ? t : (namespace.isEmpty() ? t : namespace + "." + t);
            JsonNode resolved = findNamed(root, full);
            if (resolved != null) {
                checkDefault(resolved, def, effectiveNamespace(resolved, namespace), root, path);
            }
            return; // unknown names are reported by the Avro parser
        }
        if (typeNode.isObject()) {
            String kind = typeNode.path("type").asText("");
            switch (kind) {
                case "record" -> {
                    validateDefaults(root, typeNode, effectiveNamespace(typeNode, namespace), path);
                    checkRecordDefault(typeNode, def, effectiveNamespace(typeNode, namespace), root, path);
                }
                case "enum" -> {
                    if (!def.isTextual() || !containsSymbol(typeNode.get("symbols"), def.asText()))
                        throw new InvalidSchemaException("default " + def + " at " + path + " is not one of the enum symbols " + typeNode.get("symbols"));
                }
                case "fixed" -> {
                    int size = typeNode.path("size").asInt(-1);
                    if (size < 0) throw new InvalidSchemaException("fixed type at " + path + " needs a non-negative size");
                    if (!def.isTextual() || def.asText().getBytes(StandardCharsets.UTF_8).length != size)
                        throw new InvalidSchemaException("default " + def + " at " + path + " must be a " + size + "-byte UTF-8 string");
                }
                case "array" -> {
                    if (!def.isArray() || !def.isEmpty())
                        throw new InvalidSchemaException("array default at " + path + " must be []");
                    validateDefaults(root, typeNode.get("items"), namespace, path + "/items");
                }
                case "map" -> {
                    if (!def.isObject() || def.size() != 0)
                        throw new InvalidSchemaException("map default at " + path + " must be {}");
                    validateDefaults(root, typeNode.get("values"), namespace, path + "/values");
                }
                default -> {
                    if (isPrimitive(kind) && !primitiveDefaultOk(kind, def))
                        throw new InvalidSchemaException("default " + def + " at " + path + " is not a valid " + kind);
                }
            }
        }
    }

    private static boolean defaultMatches(JsonNode typeNode, JsonNode def, String ns, JsonNode root, String path) {
        if (typeNode.isTextual()) {
            String t = typeNode.asText();
            if (isPrimitive(t)) return primitiveDefaultOk(t, def);
            JsonNode r = findNamed(root, t.contains(".") ? t : ns + "." + t);
            if (r == null) return true; // parser reports unresolvable names
            try { checkDefault(r, def, effectiveNamespace(r, ns), root, path); return true; }
            catch (InvalidSchemaException e) { return false; }
        }
        try { checkDefault(typeNode, def, ns, root, path); return true; }
        catch (InvalidSchemaException e) { return false; }
    }

    private static void checkRecordDefault(JsonNode record, JsonNode def, String ns, JsonNode root, String path) {
        if (!def.isObject()) throw new InvalidSchemaException("record default at " + path + " must be a JSON object");
        JsonNode fields = record.get("fields");
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            String fname = field.path("name").asText();
            if (field.has("default")) {
                JsonNode fdef = def.has(fname) ? def.get(fname) : field.get("default");
                checkDefault(field.get("type"), fdef, ns, root, path + "/" + fname);
            } else if (def.has(fname)) {
                checkDefault(field.get("type"), def.get(fname), ns, root, path + "/" + fname);
            }
        }
    }

    private static boolean containsSymbol(JsonNode symbols, String value) {
        if (symbols == null || !symbols.isArray()) return false;
        for (JsonNode s : symbols) if (s.asText("").equals(value)) return true;
        return false;
    }

    private static boolean isPrimitive(String t) {
        return Arrays.asList("null", "boolean", "int", "long", "float", "double", "bytes", "string").contains(t);
    }

    private static boolean primitiveDefaultOk(String t, JsonNode def) {
        return switch (t) {
            case "null" -> def.isNull();
            case "boolean" -> def.isBoolean();
            case "int", "long" -> def.isIntegralNumber();
            case "float", "double" -> def.isNumber();
            case "bytes" -> def.isTextual();
            case "string" -> def.isTextual();
            default -> true;
        };
    }

    private static JsonNode findNamed(JsonNode node, String fullname) {
        if (node == null) return null;
        if (node.isObject()) {
            String type = node.path("type").asText("");
            if (("record".equals(type) || "enum".equals(type) || "fixed".equals(type))
                    && fullname(node).equals(fullname)) return node;
            for (var it = node.fields(); it.hasNext(); ) {
                JsonNode found = findNamed(it.next().getValue(), fullname);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findNamed(child, fullname);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String fullname(JsonNode named) {
        String name = named.path("name").asText("");
        if (name.contains(".")) return name;
        String ns = named.path("namespace").asText("");
        return ns.isEmpty() ? name : ns + "." + name;
    }
}
