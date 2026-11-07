package com.dmnrules.decision.integration;

import com.dmnrules.decision.api.dto.EvaluateResponseDto;
import com.dmnrules.decision.author.CreateTestCaseRequest;
import com.dmnrules.decision.author.DecisionDiffEntry;
import com.dmnrules.decision.author.DecisionRuleDto;
import com.dmnrules.decision.author.ModelSummaryDto;
import com.dmnrules.decision.author.TestRunResult;
import com.dmnrules.decision.author.UpdateDraftRequest;
import com.dmnrules.decision.author.ValidationResult;
import com.dmnrules.decision.model.ModelVersionRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end rule-authoring flow per BUILD_PLAN M3 acceptance: "analyst edits a row, approver
 * approves, publish -> next evaluation uses new version without restart."
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.context.annotation.Import(DemoUserBasicAuth.class)
class ModelLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dmn_rules")
            .withUsername("dmn_rules")
            .withPassword("dmn_rules");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    private static final String MODEL_KEY = "loan-eligibility";
    private static final Map<String, Object> SCENARIO_APPLICANT =
            Map.of("age", 22, "creditScore", 750, "income", 100000, "employmentYears", 5);
    private static final Map<String, Object> SCENARIO_LOAN = Map.of("amount", 5000, "termMonths", 24);
    private static final Map<String, Object> SCENARIO_INPUTS =
            Map.of("Applicant", SCENARIO_APPLICANT, "Loan", SCENARIO_LOAN);

    private <T> ResponseEntity<T> as(String user, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User", user);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @Test
    void fullAuthoringFlow_editValidateTestApprovePublish_hotSwapsWithoutRestart() {
        // Baseline: before any edit, this scenario resolves to LOW risk -> APPROVED.
        ResponseEntity<EvaluateResponseDto> baseline = restTemplate.postForEntity(
                "/api/decisions/" + MODEL_KEY + "/evaluate", SCENARIO_INPUTS, EvaluateResponseDto.class);
        assertThat(baseline.getBody().outputs()).containsEntry("Eligibility", "APPROVED");

        // 1. Analyst edits the "under 21" rule to "under 23" on the Eligibility table.
        ResponseEntity<ModelVersionRow> draftResponse = as("analyst", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts", editAgeThresholdRequest(), ModelVersionRow.class);
        assertThat(draftResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ModelVersionRow draft = draftResponse.getBody();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.authoredBy()).isEqualTo("analyst");
        UUID draftId = draft.id();

        // 2. Validate.
        ResponseEntity<ValidationResult> validation = as("analyst", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts/" + draftId + "/validate", null, ValidationResult.class);
        assertThat(validation.getBody().ok()).isTrue();
        assertThat(validation.getBody().status()).isEqualTo("VALIDATED");

        // 3. Save a regression test case capturing the new expected behaviour, then run it.
        as("analyst", HttpMethod.POST, "/api/models/" + MODEL_KEY + "/test-cases",
                new CreateTestCaseRequest("Under-23 now needs review", SCENARIO_INPUTS, Map.of("Eligibility", "REVIEW")),
                Object.class);

        ResponseEntity<TestRunResult> testRun = as("analyst", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts/" + draftId + "/tests/run", null, TestRunResult.class);
        assertThat(testRun.getBody().allPassed()).isTrue();
        assertThat(testRun.getBody().status()).isEqualTo("TESTED");

        // 4. Four-eyes: the author cannot approve their own draft.
        ResponseEntity<String> selfApprove = as("analyst", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts/" + draftId + "/approve", null, String.class);
        assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 5. A different user approves.
        ResponseEntity<ModelVersionRow> approved = as("approver", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts/" + draftId + "/approve", null, ModelVersionRow.class);
        assertThat(approved.getBody().status()).isEqualTo("APPROVED");
        assertThat(approved.getBody().approvedBy()).isEqualTo("approver");

        // 6. Publish.
        ResponseEntity<ModelVersionRow> published = as("approver", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/drafts/" + draftId + "/publish", null, ModelVersionRow.class);
        assertThat(published.getBody().status()).isEqualTo("PUBLISHED");
        String publishedVersion = published.getBody().version();

        // 7. The very next evaluation (no restart) uses the new version and reflects the edit.
        ResponseEntity<EvaluateResponseDto> afterPublish = restTemplate.postForEntity(
                "/api/decisions/" + MODEL_KEY + "/evaluate", SCENARIO_INPUTS, EvaluateResponseDto.class);
        assertThat(afterPublish.getBody().modelVersion()).isEqualTo(publishedVersion);
        assertThat(afterPublish.getBody().outputs()).containsEntry("Eligibility", "REVIEW");

        // 8. Diff between the previous published version and this one shows the changed rule.
        ResponseEntity<DecisionDiffEntry[]> diff = restTemplate.getForEntity(
                "/api/models/" + MODEL_KEY + "/versions/1.1.0/diff/" + publishedVersion, DecisionDiffEntry[].class);
        assertThat(diff.getBody()).anySatisfy(entry -> {
            if ("Eligibility".equals(entry.decisionName())) {
                assertThat(entry.changeType()).isEqualTo("CHANGED");
                assertThat(entry.ruleDiffs()).anySatisfy(r -> assertThat(r.changeType()).isEqualTo("CHANGED"));
            }
        });

        // 9. Rollback republishes 1.1.0's content as a new version; the edit is gone.
        ResponseEntity<ModelVersionRow> rolledBack = as("approver", HttpMethod.POST,
                "/api/models/" + MODEL_KEY + "/rollback?toVersion=1.1.0", null, ModelVersionRow.class);
        assertThat(rolledBack.getBody().status()).isEqualTo("PUBLISHED");

        ResponseEntity<EvaluateResponseDto> afterRollback = restTemplate.postForEntity(
                "/api/decisions/" + MODEL_KEY + "/evaluate", SCENARIO_INPUTS, EvaluateResponseDto.class);
        assertThat(afterRollback.getBody().modelVersion()).isEqualTo(rolledBack.getBody().version());
        assertThat(afterRollback.getBody().outputs()).containsEntry("Eligibility", "APPROVED");

        // 10. The model summary and change history reflect everything that happened.
        ResponseEntity<ModelSummaryDto[]> summaries = restTemplate.getForEntity("/api/models", ModelSummaryDto[].class);
        assertThat(summaries.getBody()).anySatisfy(s -> {
            if (MODEL_KEY.equals(s.modelKey())) {
                assertThat(s.latestPublishedVersion()).isEqualTo(rolledBack.getBody().version());
            }
        });

        ResponseEntity<Object[]> history = restTemplate.getForEntity("/api/models/" + MODEL_KEY + "/history", Object[].class);
        assertThat(history.getBody()).hasSizeGreaterThanOrEqualTo(5); // edit, validate, test-run, approve, publish, rollback
    }

    private UpdateDraftRequest editAgeThresholdRequest() {
        // The service reads the current draft/published XML itself; we only need to submit the
        // full new row content for the Eligibility table (rule 1's age cutoff widened to <23).
        List<DecisionRuleDto> rules = new ArrayList<>();
        rules.add(new DecisionRuleDto(null, "Widened in M3 test: under 23 needs manual review.",
                List.of("<23", "-", "-"), "\"REVIEW\""));
        rules.add(new DecisionRuleDto(null, "High risk applicants are declined.",
                List.of("-", "-", "\"HIGH\""), "\"DECLINED\""));
        rules.add(new DecisionRuleDto(null, "Medium risk with under a year on the job needs manual review.",
                List.of("-", "<1", "\"MEDIUM\""), "\"REVIEW\""));
        rules.add(new DecisionRuleDto(null, "Medium risk with at least a year on the job is approved.",
                List.of("-", "-", "\"MEDIUM\""), "\"APPROVED\""));
        rules.add(new DecisionRuleDto(null, "Low risk applicants are approved.",
                List.of("-", "-", "\"LOW\""), "\"APPROVED\""));
        rules.add(new DecisionRuleDto(null, "Safety net: anything unmatched goes to manual review.",
                List.of("-", "-", "-"), "\"REVIEW\""));

        return new UpdateDraftRequest(null,
                new UpdateDraftRequest.GuidedTableEdit("Eligibility", "FIRST", rules),
                "Widen the under-21 review rule to under-23.");
    }
}
