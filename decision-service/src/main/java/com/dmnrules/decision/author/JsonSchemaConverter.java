package com.dmnrules.decision.author;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a JSON Schema into DMN data types (item definitions). Pure and stateless per call, so the
 * mapping is unit-testable without a model or a database.
 *
 * <p>Mapping: {@code object} with properties becomes a structure, {@code array} a list,
 * string/number/integer/boolean the FEEL built-ins, {@code format: date|date-time|time} the FEEL
 * date types, {@code enum}/{@code const} and {@code minimum}/{@code maximum} become allowed values,
 * and local {@code $ref}s ({@code #/$defs/X}, {@code #/definitions/X}) become references to named
 * types. Things DMN cannot express ({@code oneOf}, {@code pattern}, {@code additionalProperties},
 * ...) are skipped with a warning rather than guessed at.
 */
public final class JsonSchemaConverter {

    static final int MAX_TYPES = 300;
    static final int MAX_DEPTH = 12;

    /** FEEL built-in type names: a named type may not reuse them. */
    private static final Set<String> BUILT_INS = Set.of("string", "number", "boolean", "date", "time",
            "date and time", "days and time duration", "years and months duration", "any", "context", "list",
            "function", "range");
    private static final Pattern NOT_NAME_CHAR = Pattern.compile("[^A-Za-z0-9_ .\\-]");

    public record Options(String rootName, boolean createInputs) {
    }

    /** One input data node to create: its name and the (named) type it uses. */
    public record InputSpec(String name, String typeRef) {
    }

    public record Result(List<ItemDefinitionDto> types, List<InputSpec> inputs, List<String> warnings) {
    }

    private record Shape(String typeRef, boolean collection, String allowedValues, List<ItemDefinitionDto> components) {

        static Shape of(String typeRef) {
            return new Shape(typeRef, false, null, List.of());
        }

        ItemDefinitionDto named(String name) {
            return new ItemDefinitionDto(name, typeRef, collection, allowedValues, components);
        }
    }

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> defNames = new LinkedHashMap<>();
    private final Set<String> usedNames = new LinkedHashSet<>();
    private String rootTypeName;
    private boolean sawRequired;
    private int typeCount;

    private JsonSchemaConverter() {
    }

    public static Result convert(JsonNode schema, Options options) {
        return new JsonSchemaConverter().run(schema, options);
    }

    private Result run(JsonNode schema, Options options) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("The JSON Schema must be a JSON object.");
        }
        JsonNode defs = definitions(schema);
        // Register every named type first so $refs (including forward and self references) resolve.
        for (Iterator<String> it = defs.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            defNames.put(key, uniqueTypeName(key, "$defs/" + key));
        }
        boolean rootIsObject = isObjectSchema(schema);
        if (rootIsObject) {
            String requested = blankToNull(options.rootName());
            String fallback = blankToNull(schema.path("title").asText(null));
            rootTypeName = uniqueTypeName(requested != null ? requested : fallback != null ? fallback : "Root", "root");
        }
        if (!rootIsObject && defNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "The schema defines no object types to import: give it \"type\": \"object\" with \"properties\", or named types under \"$defs\".");
        }

        List<ItemDefinitionDto> types = new ArrayList<>();
        if (rootIsObject) {
            types.add(objectShape(schema, rootTypeName, 0).named(rootTypeName));
        }
        defNames.forEach((key, name) -> types.add(shape(defs.get(key), name, 0).named(name)));

        List<InputSpec> inputs = List.of();
        if (options.createInputs()) {
            if (rootIsObject) {
                inputs = hoistInputs(types);
            } else {
                warnings.add("Input data nodes were not created: the schema has no top-level object.");
            }
        }
        if (sawRequired) {
            warnings.add("\"required\" was ignored: DMN data types have no required fields (any field can be null; "
                    + "a decision can test for null instead).");
        }
        return new Result(List.copyOf(types), inputs, List.copyOf(warnings));
    }

    // ---------------------------------------------------------------- shapes

    private Shape shape(JsonNode node, String path, int depth) {
        count(path);
        if (depth > MAX_DEPTH) {
            warnings.add(path + ": nested deeper than " + MAX_DEPTH + " levels; mapped to Any.");
            return Shape.of("Any");
        }
        if (node == null || !node.isObject()) {
            return Shape.of("Any");
        }
        if (node.has("$ref")) {
            return Shape.of(resolveRef(node.get("$ref").asText(), path));
        }
        if (node.has("allOf")) {
            JsonNode all = node.get("allOf");
            if (all.isArray() && all.size() == 1) {
                return shape(all.get(0), path, depth + 1);
            }
            warnings.add(path + ": \"allOf\" with several schemas is not supported; mapped to Any.");
            return Shape.of("Any");
        }
        for (String combinator : List.of("oneOf", "anyOf")) {
            if (node.has(combinator)) {
                JsonNode nonNull = singleNonNull(node.get(combinator));
                if (nonNull != null) {
                    return shape(nonNull, path, depth + 1);
                }
                warnings.add(path + ": \"" + combinator + "\" has no DMN equivalent; mapped to Any.");
                return Shape.of("Any");
            }
        }
        for (String skipped : List.of("not", "if", "patternProperties", "dependentSchemas")) {
            if (node.has(skipped)) {
                warnings.add(path + ": \"" + skipped + "\" is not supported in DMN and was skipped.");
            }
        }

        String type = typeOf(node, path);
        return switch (type) {
            case "object" -> objectShape(node, path, depth);
            case "array" -> arrayShape(node, path, depth);
            case "string" -> withConstraints(Shape.of(stringType(node, path)), node, path, true);
            case "number", "integer" -> withConstraints(Shape.of("number"), node, path, false);
            case "boolean" -> withConstraints(Shape.of("boolean"), node, path, false);
            default -> {
                if (!"null".equals(type)) {
                    warnings.add(path + ": no type given; mapped to Any.");
                }
                yield Shape.of("Any");
            }
        };
    }

    private Shape objectShape(JsonNode node, String path, int depth) {
        if (node.has("required")) {
            sawRequired = true;
        }
        JsonNode additional = node.get("additionalProperties");
        if (additional != null && !(additional.isBoolean() && !additional.asBoolean())) {
            warnings.add(path + ": \"additionalProperties\" is not supported in DMN and was skipped.");
        }
        JsonNode properties = node.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            warnings.add(path + ": object without properties; mapped to Any.");
            return Shape.of("Any");
        }
        List<ItemDefinitionDto> components = new ArrayList<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = sanitize(field.getKey(), path + "." + field.getKey());
            if (!fieldNames.add(name.toLowerCase(Locale.ROOT))) {
                warnings.add(path + "." + field.getKey() + ": duplicates another field after renaming; skipped.");
                continue;
            }
            components.add(shape(field.getValue(), path + "." + name, depth + 1).named(name));
        }
        return new Shape(null, false, null, List.copyOf(components));
    }

    private Shape arrayShape(JsonNode node, String path, int depth) {
        JsonNode items = node.get("items");
        if (items == null || !items.isObject()) {
            if (items != null && items.isArray()) {
                warnings.add(path + ": tuple arrays are not supported; mapped to a list of Any.");
            }
            return new Shape("Any", true, null, List.of());
        }
        Shape item = shape(items, path + "[]", depth + 1);
        if (item.collection()) {
            warnings.add(path + ": lists of lists need a named type in DMN; mapped to a list of Any.");
            return new Shape("Any", true, null, List.of());
        }
        if (node.has("minItems") || node.has("maxItems") || node.has("uniqueItems")) {
            warnings.add(path + ": list size and uniqueness limits are not supported and were skipped.");
        }
        return new Shape(item.typeRef(), true, item.allowedValues(), item.components());
    }

    private String stringType(JsonNode node, String path) {
        String format = node.path("format").asText("");
        return switch (format) {
            case "date" -> "date";
            case "date-time" -> "date and time";
            case "time" -> "time";
            case "duration" -> "days and time duration";
            default -> {
                if (node.has("pattern")) {
                    warnings.add(path + ": \"pattern\" (regular expression) is not supported in DMN and was skipped.");
                }
                if (node.has("minLength") || node.has("maxLength")) {
                    warnings.add(path + ": string length limits are not supported and were skipped.");
                }
                yield "string";
            }
        };
    }

    /** enum / const take precedence; otherwise numeric bounds become a FEEL range or comparison. */
    private Shape withConstraints(Shape base, JsonNode node, String path, boolean quoted) {
        List<JsonNode> values = new ArrayList<>();
        if (node.has("const")) {
            values.add(node.get("const"));
        } else if (node.path("enum").isArray()) {
            node.get("enum").forEach(values::add);
        }
        if (!values.isEmpty()) {
            List<String> literals = new ArrayList<>();
            for (JsonNode v : values) {
                if (v.isNull()) {
                    continue;
                }
                literals.add(v.isTextual() || quoted ? quote(v.asText()) : v.asText());
            }
            return literals.isEmpty() ? base : new Shape(base.typeRef(), false, String.join(", ", literals), List.of());
        }
        if (node.has("multipleOf")) {
            warnings.add(path + ": \"multipleOf\" is not supported and was skipped.");
        }
        String range = range(node);
        return range == null ? base : new Shape(base.typeRef(), false, range, List.of());
    }

    private static String range(JsonNode node) {
        String min = numberText(node, "minimum");
        String max = numberText(node, "maximum");
        String xMin = numberText(node, "exclusiveMinimum");
        String xMax = numberText(node, "exclusiveMaximum");
        String low = xMin != null ? xMin : min;
        String high = xMax != null ? xMax : max;
        if (low != null && high != null) {
            return (xMin != null ? "(" : "[") + low + ".." + high + (xMax != null ? ")" : "]");
        }
        if (low != null) {
            return (xMin != null ? "> " : ">= ") + low;
        }
        if (high != null) {
            return (xMax != null ? "< " : "<= ") + high;
        }
        return null;
    }

    private static String numberText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.asText() : null;
    }

    // ---------------------------------------------------------------- inputs

    /**
     * One input data node per top-level property. An input's type must be a named type, so inline
     * structures and lists are hoisted into named types and the root field points at them.
     */
    private List<InputSpec> hoistInputs(List<ItemDefinitionDto> types) {
        ItemDefinitionDto root = types.get(0);
        List<InputSpec> inputs = new ArrayList<>();
        List<ItemDefinitionDto> newRootComponents = new ArrayList<>();
        for (ItemDefinitionDto field : root.components()) {
            String typeRef = field.typeRef();
            if (!field.components().isEmpty() || field.collection() || field.allowedValues() != null) {
                String hoisted = uniqueTypeName(capitalize(field.name()) + (field.collection() ? "List" : ""),
                        rootTypeName + "." + field.name());
                types.add(new ItemDefinitionDto(hoisted, field.typeRef(), field.collection(), field.allowedValues(),
                        field.components()));
                typeRef = hoisted;
                newRootComponents.add(new ItemDefinitionDto(field.name(), hoisted, false, null, List.of()));
            } else {
                newRootComponents.add(field);
            }
            inputs.add(new InputSpec(field.name(), typeRef));
        }
        types.set(0, new ItemDefinitionDto(root.name(), root.typeRef(), root.collection(), root.allowedValues(),
                List.copyOf(newRootComponents)));
        return List.copyOf(inputs);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode definitions(JsonNode schema) {
        JsonNode defs = schema.path("$defs");
        if (defs.isObject() && !defs.isEmpty()) {
            return defs;
        }
        return schema.path("definitions").isObject() ? schema.path("definitions") : schema.path("$defs");
    }

    private static boolean isObjectSchema(JsonNode schema) {
        String type = schema.path("type").asText("");
        return "object".equals(type) || (type.isEmpty() && schema.path("properties").isObject());
    }

    private String resolveRef(String ref, String path) {
        if ("#".equals(ref) && rootTypeName != null) {
            return rootTypeName;
        }
        for (String prefix : List.of("#/$defs/", "#/definitions/")) {
            if (ref.startsWith(prefix)) {
                String name = defNames.get(ref.substring(prefix.length()));
                if (name != null) {
                    return name;
                }
            }
        }
        warnings.add(path + ": reference \"" + ref + "\" is not a local $defs/definitions entry; mapped to Any.");
        return "Any";
    }

    private String typeOf(JsonNode node, String path) {
        JsonNode type = node.get("type");
        if (type == null) {
            if (node.path("properties").isObject()) {
                return "object";
            }
            if (node.has("items")) {
                return "array";
            }
            JsonNode sample = node.has("const") ? node.get("const")
                    : node.path("enum").isArray() && !node.get("enum").isEmpty() ? node.get("enum").get(0) : null;
            if (sample != null) {
                return sample.isNumber() ? "number" : sample.isBoolean() ? "boolean" : "string";
            }
            return "";
        }
        if (type.isArray()) {
            List<String> kinds = new ArrayList<>();
            type.forEach(t -> {
                if (!"null".equals(t.asText())) {
                    kinds.add(t.asText());
                }
            });
            if (kinds.size() == 1) {
                return kinds.get(0);
            }
            if (kinds.isEmpty()) {
                return "null";
            }
            warnings.add(path + ": several types " + kinds + " have no DMN equivalent; mapped to Any.");
            return "";
        }
        return type.asText();
    }

    private static JsonNode singleNonNull(JsonNode options) {
        if (options == null || !options.isArray()) {
            return null;
        }
        JsonNode found = null;
        for (JsonNode option : options) {
            if ("null".equals(option.path("type").asText())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = option;
        }
        return found;
    }

    private String uniqueTypeName(String raw, String path) {
        String name = sanitize(raw, path);
        if (BUILT_INS.contains(name.toLowerCase(Locale.ROOT))) {
            warnings.add(path + ": \"" + name + "\" is a built-in FEEL type name; renamed to \"" + name + "Type\".");
            name = name + "Type";
        }
        String unique = name;
        for (int i = 2; usedNames.contains(unique.toLowerCase(Locale.ROOT)); i++) {
            unique = name + i;
        }
        usedNames.add(unique.toLowerCase(Locale.ROOT));
        return unique;
    }

    private String sanitize(String raw, String path) {
        String trimmed = raw == null ? "" : raw.trim();
        String name = NOT_NAME_CHAR.matcher(trimmed).replaceAll("_");
        if (name.isEmpty()) {
            name = "Field";
        }
        if (Character.isDigit(name.charAt(0)) || name.charAt(0) == '.' || name.charAt(0) == '-') {
            name = "_" + name;
        }
        if (!name.equals(trimmed)) {
            warnings.add(path + ": renamed \"" + trimmed + "\" to \"" + name + "\" (not a valid DMN name).");
        }
        return name;
    }

    private void count(String path) {
        if (++typeCount > MAX_TYPES) {
            throw new IllegalArgumentException("The schema is too large: more than " + MAX_TYPES
                    + " types and fields (stopped at " + path + ").");
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
