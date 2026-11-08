package com.dmnrules.decision.author;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static JsonSchemaConverter.Result convert(String schema) throws Exception {
        return JsonSchemaConverter.convert(json(schema), new JsonSchemaConverter.Options(null, false));
    }

    private static ItemDefinitionDto field(ItemDefinitionDto type, String name) {
        return type.components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void mapsAnObjectSchemaToAStructureWithFeelTypes() throws Exception {
        var result = convert("""
                {"title": "Applicant", "type": "object", "required": ["age"],
                 "properties": {
                   "name": {"type": "string"},
                   "age": {"type": "integer", "minimum": 18, "maximum": 120},
                   "income": {"type": "number", "exclusiveMinimum": 0},
                   "vip": {"type": "boolean"},
                   "birthDate": {"type": "string", "format": "date"},
                   "appliedAt": {"type": "string", "format": "date-time"},
                   "status": {"type": "string", "enum": ["NEW", "EXISTING"]},
                   "address": {"type": "object", "properties": {"city": {"type": "string"}, "zip": {"type": "string"}}},
                   "tags": {"type": "array", "items": {"type": "string"}},
                   "nickname": {"type": ["string", "null"]}
                 }}""");

        assertThat(result.types()).hasSize(1);
        ItemDefinitionDto applicant = result.types().get(0);
        assertThat(applicant.name()).isEqualTo("Applicant");
        assertThat(applicant.typeRef()).isNull();
        assertThat(field(applicant, "name").typeRef()).isEqualTo("string");
        assertThat(field(applicant, "age").typeRef()).isEqualTo("number");
        assertThat(field(applicant, "age").allowedValues()).isEqualTo("[18..120]");
        assertThat(field(applicant, "income").allowedValues()).isEqualTo("> 0");
        assertThat(field(applicant, "vip").typeRef()).isEqualTo("boolean");
        assertThat(field(applicant, "birthDate").typeRef()).isEqualTo("date");
        assertThat(field(applicant, "appliedAt").typeRef()).isEqualTo("date and time");
        assertThat(field(applicant, "status").allowedValues()).isEqualTo("\"NEW\", \"EXISTING\"");
        assertThat(field(applicant, "address").components()).extracting(ItemDefinitionDto::name)
                .containsExactly("city", "zip");
        assertThat(field(applicant, "tags").collection()).isTrue();
        assertThat(field(applicant, "tags").typeRef()).isEqualTo("string");
        assertThat(field(applicant, "nickname").typeRef()).isEqualTo("string");
        assertThat(result.warnings()).anyMatch(w -> w.contains("\"required\" was ignored"));
    }

    @Test
    void namedDefinitionsBecomeTypesAndRefsPointAtThem() throws Exception {
        var result = convert("""
                {"type": "object", "title": "Order",
                 "properties": {
                   "customer": {"$ref": "#/$defs/Customer"},
                   "lines": {"type": "array", "items": {"$ref": "#/$defs/Line"}}
                 },
                 "$defs": {
                   "Customer": {"type": "object", "properties": {"id": {"type": "string"},
                                 "referredBy": {"$ref": "#/$defs/Customer"}}},
                   "Line": {"type": "object", "properties": {"qty": {"type": "integer", "minimum": 1}}},
                   "Currency": {"type": "string", "enum": ["EUR", "USD"]}
                 }}""");

        assertThat(result.types()).extracting(ItemDefinitionDto::name)
                .containsExactly("Order", "Customer", "Line", "Currency");
        ItemDefinitionDto order = result.types().get(0);
        assertThat(field(order, "customer").typeRef()).isEqualTo("Customer");
        assertThat(field(order, "lines").typeRef()).isEqualTo("Line");
        assertThat(field(order, "lines").collection()).isTrue();
        // Self reference resolves to the named type instead of recursing forever.
        assertThat(field(result.types().get(1), "referredBy").typeRef()).isEqualTo("Customer");
        assertThat(result.types().get(3).typeRef()).isEqualTo("string");
        assertThat(result.types().get(3).allowedValues()).isEqualTo("\"EUR\", \"USD\"");
    }

    @Test
    void skipsWhatDmnCannotExpressWithWarningsInsteadOfGuessing() throws Exception {
        var result = convert("""
                {"type": "object", "title": "Thing", "additionalProperties": {"type": "string"},
                 "properties": {
                   "email": {"type": "string", "pattern": "^.+@.+$"},
                   "payment": {"oneOf": [{"$ref": "#/$defs/Card"}, {"$ref": "#/$defs/Bank"}]},
                   "maybe": {"anyOf": [{"type": "null"}, {"type": "number"}]},
                   "remote": {"$ref": "https://example.com/other.json"},
                   "anything": {}
                 },
                 "$defs": {"Card": {"type": "object", "properties": {"n": {"type": "string"}}},
                           "Bank": {"type": "object", "properties": {"iban": {"type": "string"}}}}}""");

        ItemDefinitionDto thing = result.types().get(0);
        assertThat(field(thing, "email").typeRef()).isEqualTo("string");
        assertThat(field(thing, "payment").typeRef()).isEqualTo("Any");
        assertThat(field(thing, "maybe").typeRef()).isEqualTo("number"); // nullable union collapses
        assertThat(field(thing, "remote").typeRef()).isEqualTo("Any");
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("Thing.email") && w.contains("pattern"))
                .anyMatch(w -> w.contains("Thing.payment") && w.contains("oneOf"))
                .anyMatch(w -> w.contains("Thing.remote") && w.contains("not a local"))
                .anyMatch(w -> w.contains("additionalProperties"));
    }

    @Test
    void renamesInvalidAndReservedNames() throws Exception {
        var result = convert("""
                {"type": "object", "title": "date",
                 "properties": {"2nd line": {"type": "string"}, "e-mail": {"type": "string"}, "a/b": {"type": "string"}}}""");

        assertThat(result.types().get(0).name()).isEqualTo("dateType");
        assertThat(result.types().get(0).components()).extracting(ItemDefinitionDto::name)
                .containsExactly("_2nd line", "e-mail", "a_b");
        assertThat(result.warnings()).anyMatch(w -> w.contains("built-in FEEL type name"));
    }

    @Test
    void createInputsHoistsInlineStructuresListsAndConstrainedFieldsIntoNamedTypes() throws Exception {
        var result = JsonSchemaConverter.convert(json("""
                {"type": "object", "properties": {
                   "applicant": {"type": "object", "properties": {"age": {"type": "number"}}},
                   "amount": {"type": "number"},
                   "items": {"type": "array", "items": {"type": "string"}},
                   "channel": {"type": "string", "enum": ["web", "branch"]}}}"""),
                new JsonSchemaConverter.Options("LoanRequest", true));

        assertThat(result.types()).extracting(ItemDefinitionDto::name)
                .containsExactly("LoanRequest", "Applicant", "ItemsList", "Channel");
        assertThat(result.inputs()).containsExactly(
                new JsonSchemaConverter.InputSpec("applicant", "Applicant"),
                new JsonSchemaConverter.InputSpec("amount", "number"),
                new JsonSchemaConverter.InputSpec("items", "ItemsList"),
                new JsonSchemaConverter.InputSpec("channel", "Channel"));
        ItemDefinitionDto root = result.types().get(0);
        assertThat(field(root, "applicant").typeRef()).isEqualTo("Applicant");
        assertThat(field(root, "applicant").components()).isEmpty();
        ItemDefinitionDto list = result.types().get(2);
        assertThat(list.collection()).isTrue();
        assertThat(list.typeRef()).isEqualTo("string");
    }

    @Test
    void rejectsSchemasWithNothingToImportAndOversizedSchemas() throws Exception {
        assertThatThrownBy(() -> convert("{\"type\": \"string\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no object types");
        assertThatThrownBy(() -> JsonSchemaConverter.convert(json("[1]"), new JsonSchemaConverter.Options(null, false)))
                .isInstanceOf(IllegalArgumentException.class);

        StringBuilder props = new StringBuilder();
        for (int i = 0; i < JsonSchemaConverter.MAX_TYPES + 5; i++) {
            props.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":{\"type\":\"string\"}");
        }
        assertThatThrownBy(() -> convert("{\"type\":\"object\",\"properties\":{" + props + "}}"))
                .hasMessageContaining("too large");
    }

    @Test
    void deepNestingStopsAtTheLimit() throws Exception {
        String leaf = "{\"type\":\"string\"}";
        String node = leaf;
        for (int i = 0; i < JsonSchemaConverter.MAX_DEPTH + 3; i++) {
            node = "{\"type\":\"object\",\"properties\":{\"x\":" + node + "}}";
        }
        var result = convert(node);
        assertThat(result.warnings()).anyMatch(w -> w.contains("nested deeper"));
        assertThat(List.of(result.types().get(0).name())).containsExactly("Root");
    }
}
