package com.dmnrules.decision.author;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Keeps the shipped sample (seed/samples/mortgage-assessment.dmn) honest: it must import, compile
 * without errors, show its nested types and DRG, and evaluate its FEEL for-loops correctly.
 */
class SampleMortgageModelTest {

    private static final Path DMN = Path.of("../seed/samples/mortgage-assessment.dmn");
    private static final Path INPUT = Path.of("../seed/samples/mortgage-assessment.sample-input.json");

    private final ObjectMapper json = new ObjectMapper();

    private DMNRuntime runtime() throws Exception {
        String xml = Files.readString(DMN, StandardCharsets.UTF_8);
        Resource resource = ResourceFactory.newByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
        resource.setSourcePath("mortgage-assessment.dmn");
        resource.setResourceType(ResourceType.DMN);
        return DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(resource))
                .getOrElseThrow(e -> new RuntimeException("DMN compile failed", e));
    }

    private Map<String, Object> sampleInput() throws Exception {
        return json.readValue(Files.readString(INPUT, StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private DMNResult evaluate(Map<String, Object> input) throws Exception {
        DMNRuntime runtime = runtime();
        DMNModel model = runtime.getModels().get(0);
        DMNContext ctx = runtime.newContext();
        input.forEach(ctx::set);
        return runtime.evaluateAll(model, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sampleInput_isReferredBecauseOfRedFlags() throws Exception {
        DMNRuntime runtime = runtime();
        assertThat(runtime.getModels().get(0).getMessages()).isEmpty();

        DMNResult result = evaluate(sampleInput());
        assertThat(result.hasErrors()).as(result.getMessages().toString()).isFalse();

        // for-loop: 6000 salary + 1000 bonus * 0.5 + unverified rental ignored
        assertThat((BigDecimal) result.getDecisionResultByName("Verified Monthly Income").getResult())
                .isEqualByComparingTo("6500");
        assertThat((BigDecimal) result.getDecisionResultByName("Monthly Liabilities").getResult())
                .isEqualByComparingTo("500");
        assertThat((BigDecimal) result.getDecisionResultByName("Loan To Value").getResult())
                .isEqualByComparingTo("0.75");
        assertThat((BigDecimal) result.getDecisionResultByName("Interest Rate").getResult())
                .isEqualByComparingTo("0.052");

        Map<String, Object> assessment = (Map<String, Object>) result.getDecisionResultByName("Assessment").getResult();
        assertThat(assessment.get("outcome")).isEqualTo("REFER");
        assertThat(((BigDecimal) assessment.get("monthlyPayment")).doubleValue()).isCloseTo(1647.2, within(1.0));
        assertThat((List<String>) assessment.get("flags")).containsExactly(
                "High credit-card balance with Bank X", "Unverified rental income");
    }

    @SuppressWarnings("unchecked")
    @Test
    void cleanApplicant_isApproved() throws Exception {
        Map<String, Object> input = sampleInput();
        Map<String, Object> applicant = (Map<String, Object>) input.get("Applicant");
        List<Map<String, Object>> incomes = (List<Map<String, Object>>) applicant.get("incomes");
        incomes.get(2).put("verified", true);
        List<Map<String, Object>> liabilities = (List<Map<String, Object>>) input.get("Liabilities");
        liabilities.get(1).put("balance", 3000);

        DMNResult result = evaluate(input);
        assertThat(result.hasErrors()).as(result.getMessages().toString()).isFalse();
        Map<String, Object> assessment = (Map<String, Object>) result.getDecisionResultByName("Assessment").getResult();
        assertThat(assessment.get("outcome")).isEqualTo("APPROVED");
        assertThat((List<?>) assessment.get("flags")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void highLoanToValue_isDeclined() throws Exception {
        Map<String, Object> input = sampleInput();
        Map<String, Object> loan = (Map<String, Object>) input.get("Loan Request");
        loan.put("amount", 390000);

        DMNResult result = evaluate(input);
        Map<String, Object> assessment = (Map<String, Object>) result.getDecisionResultByName("Assessment").getResult();
        assertThat(assessment.get("outcome")).isEqualTo("DECLINED");
    }

    @Test
    void importValidator_reportsNoErrorsOrWarnings() throws Exception {
        var messages = new DmnValidationService().validate(Files.readString(DMN, StandardCharsets.UTF_8));
        assertThat(messages).as(messages.toString())
                .noneMatch(m -> m.severity().equals("ERROR") || m.severity().equals("WARN"));
    }

    @Test
    void structureView_showsNestedTypesAndTheWholeGraph() throws Exception {
        ModelGraphDto graph = new DmnXmlEditor().readGraph(Files.readString(DMN, StandardCharsets.UTF_8));

        assertThat(graph.nodes()).filteredOn(n -> n.type().equals("INPUT_DATA")).hasSize(3);
        assertThat(graph.nodes()).filteredOn(n -> n.type().equals("DECISION")).hasSize(11);
        assertThat(graph.nodes()).filteredOn(n -> n.type().equals("BKM")).hasSize(1);
        assertThat(graph.nodes()).filteredOn(n -> n.type().equals("KNOWLEDGE_SOURCE")).hasSize(1);
        assertThat(graph.edges()).filteredOn(e -> e.kind().equals("KNOWLEDGE")).hasSize(1);

        ItemDefinitionDto applicant = graph.itemDefinitions().stream()
                .filter(i -> i.name().equals("tApplicant")).findFirst().orElseThrow();
        ItemDefinitionDto employment = applicant.components().stream()
                .filter(c -> c.name().equals("employment")).findFirst().orElseThrow();
        assertThat(employment.components()).extracting(ItemDefinitionDto::name)
                .contains("employer", "yearsEmployed", "contract", "employerAddress");
        ItemDefinitionDto incomes = applicant.components().stream()
                .filter(c -> c.name().equals("incomes")).findFirst().orElseThrow();
        assertThat(incomes.typeRef()).isEqualTo("tIncomeList");

        assertThat(graph.itemDefinitions()).filteredOn(i -> i.name().equals("tIncomeList"))
                .singleElement().satisfies(i -> assertThat(i.collection()).isTrue());
    }
}
