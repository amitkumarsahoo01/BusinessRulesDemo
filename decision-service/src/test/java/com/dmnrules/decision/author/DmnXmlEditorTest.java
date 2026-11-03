package com.dmnrules.decision.author;

import org.junit.jupiter.api.Test;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DmnXmlEditorTest {

    private final DmnXmlEditor editor = new DmnXmlEditor();

    private String seedXml(String fileName) throws Exception {
        return Files.readString(Path.of("src/main/resources/seed/dmn/" + fileName), StandardCharsets.UTF_8);
    }

    private DMNResult evaluate(String xml, Map<String, Object> applicant, Map<String, Object> loan) {
        Resource resource = ResourceFactory.newByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
        resource.setResourceType(ResourceType.DMN);
        DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration()
                .fromResources(List.of(resource))
                .getOrElseThrow(e -> new RuntimeException("compile failed", e));
        DMNModel model = runtime.getModels().get(0);
        DMNContext context = runtime.newContext();
        context.set("Applicant", applicant);
        context.set("Loan", loan);
        return runtime.evaluateAll(model, context);
    }

    @Test
    void readDecisionTable_returnsAllRowsWithDescriptions() throws Exception {
        String xml = seedXml("loan-eligibility-1.1.0.dmn");
        DecisionTableDto table = editor.readDecisionTable(xml, "Risk Category");

        assertThat(table.hitPolicy()).isEqualTo("UNIQUE");
        assertThat(table.inputs()).extracting(DecisionTableColumnDto::expression)
                .containsExactly("Applicant.creditScore", "Debt Ratio");
        assertThat(table.output().label()).isEqualTo("Risk Category");
        assertThat(table.rules()).hasSize(5);
        assertThat(table.rules().get(0).description()).contains("low-risk bar");
        assertThat(table.rules().get(0).inputEntries()).containsExactly(">=720", "<0.35");
        assertThat(table.rules().get(0).outputEntry()).isEqualTo("\"LOW\"");
    }

    @Test
    void roundTrip_unchangedRows_producesSameEvaluationOutcomes() throws Exception {
        String originalXml = seedXml("loan-eligibility-1.1.0.dmn");
        DecisionTableDto table = editor.readDecisionTable(originalXml, "Risk Category");

        String rewrittenXml = editor.replaceDecisionTableRows(
                originalXml, "Risk Category", table.hitPolicy(), table.rules());

        Map<String, Object> applicant = new HashMap<>();
        applicant.put("age", 34);
        applicant.put("creditScore", 650);
        applicant.put("income", 72000);
        applicant.put("employmentYears", 5);
        Map<String, Object> loan = new HashMap<>();
        loan.put("amount", 10000);
        loan.put("termMonths", 48);

        DMNResult before = evaluate(originalXml, applicant, loan);
        DMNResult after = evaluate(rewrittenXml, applicant, loan);

        assertThat(after.getDecisionResultByName("Risk Category").getResult())
                .isEqualTo(before.getDecisionResultByName("Risk Category").getResult());
        assertThat(after.getDecisionResultByName("Eligibility").getResult())
                .isEqualTo(before.getDecisionResultByName("Eligibility").getResult());
        assertThat(after.hasErrors()).isFalse();
    }

    @Test
    void editingARow_changesTheEvaluationOutcome() throws Exception {
        String originalXml = seedXml("loan-eligibility-1.1.0.dmn");
        DecisionTableDto table = editor.readDecisionTable(originalXml, "Eligibility");

        // erule1: age < 21 -> REVIEW. Widen it to age < 25 and change the outcome to DECLINED.
        List<DecisionRuleDto> editedRules = new ArrayList<>(table.rules());
        DecisionRuleDto firstRule = editedRules.get(0);
        assertThat(firstRule.inputEntries().get(0)).isEqualTo("<21");
        editedRules.set(0, new DecisionRuleDto(
                firstRule.id(), "Widened in this test: under 25 is declined outright.",
                List.of("<25", "-", "-"), "\"DECLINED\""));

        String editedXml = editor.replaceDecisionTableRows(originalXml, "Eligibility", table.hitPolicy(), editedRules);

        Map<String, Object> applicant = new HashMap<>();
        applicant.put("age", 22); // was REVIEW under the old rule, now DECLINED
        applicant.put("creditScore", 750);
        applicant.put("income", 100000);
        applicant.put("employmentYears", 3);
        Map<String, Object> loan = new HashMap<>();
        loan.put("amount", 5000);
        loan.put("termMonths", 24);

        DMNResult before = evaluate(originalXml, applicant, loan);
        DMNResult after = evaluate(editedXml, applicant, loan);

        // age=22 doesn't trip the original "<21" rule, so it falls through to LOW risk -> APPROVED.
        assertThat(before.getDecisionResultByName("Eligibility").getResult()).isEqualTo("APPROVED");
        // The edited rule widens the cutoff to "<25", so age=22 now matches it first -> DECLINED.
        assertThat(after.getDecisionResultByName("Eligibility").getResult()).isEqualTo("DECLINED");
    }
}
