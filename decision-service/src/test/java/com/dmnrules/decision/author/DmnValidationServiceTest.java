package com.dmnrules.decision.author;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DmnValidationServiceTest {

    private final DmnXmlEditor editor = new DmnXmlEditor();
    private final DmnValidationService validationService = new DmnValidationService();

    private String seedXml(String fileName) throws Exception {
        return Files.readString(Path.of("src/main/resources/seed/dmn/" + fileName), StandardCharsets.UTF_8);
    }

    @Test
    void validLoanEligibilityModel_hasNoErrors() throws Exception {
        var messages = validationService.validate(seedXml("loan-eligibility-1.1.0.dmn"));
        assertThat(validationService.hasErrors(messages)).isFalse();
    }

    @Test
    void introducingAnOverlap_isCaughtByTheAnalyser() throws Exception {
        String xml = seedXml("loan-eligibility-1.1.0.dmn");
        DecisionTableDto table = editor.readDecisionTable(xml, "Risk Category");

        // Widen rule 1 so it now overlaps with rule 3 (both match creditScore=650, debtRatio=0.2).
        var rules = new java.util.ArrayList<>(table.rules());
        var first = rules.get(0);
        rules.set(0, new DecisionRuleDto(first.id(), first.description(), java.util.List.of(">=600", "<0.35"), first.outputEntry()));

        String brokenXml = editor.replaceDecisionTableRows(xml, "Risk Category", "UNIQUE", rules);
        var messages = validationService.validate(brokenXml);

        assertThat(messages).anySatisfy(m ->
                assertThat(m.message().toLowerCase()).containsAnyOf("overlap", "unique"));
    }
}
