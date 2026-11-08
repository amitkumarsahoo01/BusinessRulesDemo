package com.dmnrules.decision.author;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.core.DMNType;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Imported data types must produce DMN the engine compiles, without changing existing decisions. */
class DataTypeImportXmlTest {

    private static final String SCHEMA = """
            {"title": "Guarantor", "type": "object",
             "properties": {
               "name": {"type": "string"},
               "creditScore": {"type": "integer", "minimum": 300, "maximum": 850},
               "relationship": {"type": "string", "enum": ["PARENT", "SPOUSE", "OTHER"]},
               "address": {"type": "object", "properties": {"city": {"type": "string"}}},
               "phones": {"type": "array", "items": {"type": "string"}}
             }}""";

    private final DmnXmlEditor editor = new DmnXmlEditor();

    private static String seed() throws Exception {
        return Files.readString(Path.of("src/main/resources/seed/dmn/loan-eligibility-1.1.0.dmn"), StandardCharsets.UTF_8);
    }

    private static JsonSchemaConverter.Result convert(String schema, boolean inputs) throws Exception {
        return JsonSchemaConverter.convert(new ObjectMapper().readTree(schema),
                new JsonSchemaConverter.Options(null, inputs));
    }

    private static DMNRuntime compile(String xml) {
        Resource resource = ResourceFactory.newByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
        resource.setResourceType(ResourceType.DMN);
        return DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(resource))
                .getOrElseThrow(e -> new RuntimeException("compile failed", e));
    }

    private static DMNResult evaluate(DMNRuntime runtime) {
        DMNModel model = runtime.getModels().get(0);
        DMNContext context = runtime.newContext();
        context.set("Applicant", Map.of("age", 34, "creditScore", 650, "income", 72000, "employmentYears", 5));
        context.set("Loan", Map.of("amount", 10000, "termMonths", 48));
        return runtime.evaluateAll(model, context);
    }

    @Test
    void importedTypesCompileAndLeaveExistingDecisionsUnchanged() throws Exception {
        String original = seed();
        String imported = editor.addDataTypes(original, convert(SCHEMA, false).types(), List.of(), false);

        assertThat(editor.dataTypeNames(imported)).containsExactly("tApplicant", "tLoan", "Guarantor");

        DMNRuntime runtime = compile(imported);
        DMNModel model = runtime.getModels().get(0);
        assertThat(model.getMessages(org.kie.dmn.api.core.DMNMessage.Severity.ERROR)).isEmpty();
        DMNType guarantor = model.getItemDefinitionByName("Guarantor").getType();
        assertThat(guarantor.getFields()).containsKeys("name", "creditScore", "relationship", "address", "phones");
        assertThat(guarantor.getFields().get("phones").isCollection()).isTrue();
        // The range constraint is real: the engine enforces creditScore's allowed values.
        DMNType creditScore = guarantor.getFields().get("creditScore");
        assertThat(creditScore.isAssignableValue(new java.math.BigDecimal("700"))).isTrue();
        assertThat(creditScore.isAssignableValue(new java.math.BigDecimal("900"))).isFalse();

        DMNResult before = evaluate(compile(original));
        DMNResult after = evaluate(runtime);
        assertThat(after.hasErrors()).isFalse();
        assertThat(after.getDecisionResultByName("Eligibility").getResult())
                .isEqualTo(before.getDecisionResultByName("Eligibility").getResult());
    }

    @Test
    void createInputsAddsTypedInputDataNodesTheEngineAccepts() throws Exception {
        var converted = convert(SCHEMA, true);
        String imported = editor.addDataTypes(seed(), converted.types(), converted.inputs(), false);

        assertThat(editor.drgElementNames(imported)).contains("name", "creditScore", "relationship", "address", "phones");
        DMNModel model = compile(imported).getModels().get(0);
        assertThat(model.getMessages(org.kie.dmn.api.core.DMNMessage.Severity.ERROR)).isEmpty();
        assertThat(model.getInputByName("address").getType().getName()).isEqualTo("Address");
        assertThat(model.getInputByName("phones").getType().isCollection()).isTrue();
        assertThat(model.getInputByName("creditScore").getType().getName()).isEqualTo("CreditScore");
    }

    @Test
    void existingTypeNamesAreRefusedUnlessReplacing() throws Exception {
        String schema = """
                {"title": "tLoan", "type": "object", "properties": {"amount": {"type": "number"}}}""";
        var types = convert(schema, false).types();

        assertThatThrownBy(() -> editor.addDataTypes(seed(), types, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tLoan");

        String replaced = editor.addDataTypes(seed(), types, List.of(), true);
        assertThat(editor.dataTypeNames(replaced)).containsExactly("tApplicant", "tLoan");
        DMNModel model = compile(replaced).getModels().get(0);
        assertThat(model.getItemDefinitionByName("tLoan").getType().getFields()).containsOnlyKeys("amount");
    }

    @Test
    void inputsWhoseNameIsTakenAreSkipped() throws Exception {
        String schema = """
                {"type": "object", "properties": {"Applicant": {"type": "string"}, "Guarantor": {"type": "string"}}}""";
        var converted = convert(schema, true);
        String imported = editor.addDataTypes(seed(), converted.types(), converted.inputs(), false);
        assertThat(editor.drgElementNames(imported).stream().filter("Applicant"::equals)).hasSize(1);
        assertThat(editor.drgElementNames(imported)).contains("Guarantor");
    }
}
