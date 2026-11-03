package com.dmnrules.decision.model;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast, DB-free check that the hand-authored seed DMN files (used by SeedRunner) actually compile
 * against the real Apache KIE DMN engine and behave as the seed story intends. Full end-to-end
 * coverage (DB-backed registry, evaluate/simulate HTTP endpoints, trace persistence) lives in
 * DecisionServiceIntegrationTest.
 */
class DmnSeedFixturesTest {

    private static final String SEED_DIR = "src/main/resources/seed/dmn/";

    private DMNRuntime load(String fileName) throws Exception {
        String xml = Files.readString(Path.of(SEED_DIR + fileName), StandardCharsets.UTF_8);
        Resource resource = ResourceFactory.newByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
        resource.setSourcePath(fileName);
        resource.setResourceType(ResourceType.DMN);
        var either = DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(resource));
        return either.getOrElseThrow(e -> new RuntimeException("DMN compile failed for " + fileName, e));
    }

    @Test
    void loanEligibility100CompilesAndEvaluates() throws Exception {
        DMNRuntime runtime = load("loan-eligibility-1.0.0.dmn");
        DMNModel model = runtime.getModels().get(0);
        assertThat(model.getMessages()).isEmpty();

        Map<String, Object> applicant = new HashMap<>();
        applicant.put("age", 34);
        applicant.put("creditScore", 640);
        applicant.put("income", 72000);
        applicant.put("employmentYears", 5);
        Map<String, Object> loan = new HashMap<>();
        loan.put("amount", 25000);
        loan.put("termMonths", 48);

        DMNContext context = runtime.newContext();
        context.set("Applicant", applicant);
        context.set("Loan", loan);

        DMNResult result = runtime.evaluateAll(model, context);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getDecisionResultByName("Risk Category").getResult()).isEqualTo("MEDIUM");
        assertThat(result.getDecisionResultByName("Eligibility").getResult()).isEqualTo("APPROVED");
    }

    @Test
    void loanEligibility110TightenedThresholds_changeOutcomeForBorderlineCreditScore() throws Exception {
        DMNRuntime runtime100 = load("loan-eligibility-1.0.0.dmn");
        DMNRuntime runtime110 = load("loan-eligibility-1.1.0.dmn");

        Map<String, Object> applicant = new HashMap<>();
        applicant.put("age", 34);
        applicant.put("creditScore", 630); // between old (620) and new (640) fair-credit floor
        applicant.put("income", 72000);
        applicant.put("employmentYears", 5);
        Map<String, Object> loan = new HashMap<>();
        loan.put("amount", 10000);
        loan.put("termMonths", 48);

        DMNModel model100 = runtime100.getModels().get(0);
        DMNContext ctx100 = runtime100.newContext();
        ctx100.set("Applicant", applicant);
        ctx100.set("Loan", loan);
        assertThat(runtime100.evaluateAll(model100, ctx100).getDecisionResultByName("Risk Category").getResult())
                .isEqualTo("MEDIUM");

        DMNModel model110 = runtime110.getModels().get(0);
        DMNContext ctx110 = runtime110.newContext();
        ctx110.set("Applicant", applicant);
        ctx110.set("Loan", loan);
        assertThat(runtime110.evaluateAll(model110, ctx110).getDecisionResultByName("Risk Category").getResult())
                .isEqualTo("HIGH");
    }

    @Test
    void loanEligibility120DraftCompiles() throws Exception {
        DMNRuntime runtime = load("loan-eligibility-1.2.0.dmn");
        assertThat(runtime.getModels()).hasSize(1);
        assertThat(runtime.getModels().get(0).getMessages()).isEmpty();
    }

    @Test
    void insurancePremium100CompilesAndEvaluates() throws Exception {
        DMNRuntime runtime = load("insurance-premium-1.0.0.dmn");
        DMNModel model = runtime.getModels().get(0);
        assertThat(model.getMessages()).isEmpty();

        Map<String, Object> driver = new HashMap<>();
        driver.put("age", 22);
        driver.put("yearsLicensed", 4);
        Map<String, Object> vehicle = new HashMap<>();
        vehicle.put("value", 20000);

        DMNContext context = runtime.newContext();
        context.set("Driver", driver);
        context.set("Vehicle", vehicle);
        DMNResult result = runtime.evaluateAll(model, context);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getDecisionResultByName("Premium Tier").getResult()).isEqualTo("HIGH_RISK");
    }
}
