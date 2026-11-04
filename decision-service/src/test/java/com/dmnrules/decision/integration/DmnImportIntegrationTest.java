package com.dmnrules.decision.integration;

import com.dmnrules.decision.api.dto.EvaluateResponseDto;
import com.dmnrules.decision.author.CreateTestCaseRequest;
import com.dmnrules.decision.author.ImportResultDto;
import com.dmnrules.decision.author.ModelGraphDto;
import com.dmnrules.decision.author.ModelSummaryDto;
import com.dmnrules.decision.author.TestRunResult;
import com.dmnrules.decision.author.ValidationResult;
import com.dmnrules.decision.model.ModelVersionRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Import a .dmn through the API and drive it through the normal lifecycle to a live evaluation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DmnImportIntegrationTest {

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

    private static String fixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/shipping-cost.dmn"), StandardCharsets.UTF_8);
    }

    private <T> ResponseEntity<T> upload(String user, String xml, String fileName, String modelKey, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User", user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (modelKey != null) {
            body.add("modelKey", modelKey);
        }
        return restTemplate.exchange("/api/models/import", HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> as(String user, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User", user);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @Test
    void importedModel_becomesDraft_showsStructure_andGoesLiveThroughTheLifecycle() throws Exception {
        ResponseEntity<ImportResultDto> imported = upload("analyst", fixture(), "shipping-cost.dmn", null, ImportResultDto.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImportResultDto result = imported.getBody();
        assertThat(result.newModel()).isTrue();
        assertThat(result.version().modelKey()).isEqualTo("shipping-cost");
        assertThat(result.version().version()).isEqualTo("1.0.0");
        assertThat(result.version().status()).isEqualTo("DRAFT");
        assertThat(result.version().authoredBy()).isEqualTo("analyst");
        assertThat(result.version().changeNote()).contains("shipping-cost.dmn");

        // Listed like any other model, but not evaluable before it is published.
        ResponseEntity<ModelSummaryDto[]> models = restTemplate.getForEntity("/api/models", ModelSummaryDto[].class);
        assertThat(models.getBody()).anySatisfy(m -> {
            assertThat(m.modelKey()).isEqualTo("shipping-cost");
            assertThat(m.latestPublishedVersion()).isNull();
            assertThat(m.inProgressStatus()).isEqualTo("DRAFT");
        });

        // Structure view data: whole DRG, not just tables.
        String id = result.version().id().toString();
        ModelGraphDto graph = restTemplate.getForObject(
                "/api/models/shipping-cost/drafts/" + id + "/graph", ModelGraphDto.class);
        assertThat(graph.nodes()).extracting(n -> n.type())
                .contains("INPUT_DATA", "DECISION", "BKM", "KNOWLEDGE_SOURCE");
        assertThat(graph.edges()).hasSize(4);

        // A second import onto a model that already has a draft in progress is refused.
        ResponseEntity<String> again = upload("analyst", fixture(), "shipping-cost.dmn", "shipping-cost", String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Normal lifecycle: validate -> test -> approve (four-eyes) -> publish -> evaluate.
        ValidationResult validation = as("analyst", HttpMethod.POST,
                "/api/models/shipping-cost/drafts/" + id + "/validate", null, ValidationResult.class).getBody();
        assertThat(validation.ok()).as("validation messages: %s", validation.messages()).isTrue();

        as("analyst", HttpMethod.POST, "/api/models/shipping-cost/test-cases",
                new CreateTestCaseRequest("EU 10kg", Map.of("Weight", 10, "Zone", "EU"), Map.of("Shipping Cost", 20)),
                Object.class);
        TestRunResult tests = as("analyst", HttpMethod.POST,
                "/api/models/shipping-cost/drafts/" + id + "/tests/run", null, TestRunResult.class).getBody();
        assertThat(tests.allPassed()).isTrue();

        assertThat(as("analyst", HttpMethod.POST, "/api/models/shipping-cost/drafts/" + id + "/approve", null,
                String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        as("approver", HttpMethod.POST, "/api/models/shipping-cost/drafts/" + id + "/approve", null, ModelVersionRow.class);
        ModelVersionRow published = as("approver", HttpMethod.POST,
                "/api/models/shipping-cost/drafts/" + id + "/publish", null, ModelVersionRow.class).getBody();
        assertThat(published.status()).isEqualTo("PUBLISHED");

        ResponseEntity<EvaluateResponseDto> evaluated = restTemplate.postForEntity(
                "/api/decisions/shipping-cost/evaluate", Map.of("Weight", 4, "Zone", "US"), EvaluateResponseDto.class);
        assertThat(evaluated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evaluated.getBody().outputs()).containsEntry("Shipping Cost", 20);

        // Importing again after publish creates the next draft version of the same model.
        ImportResultDto next = upload("analyst", fixture(), "shipping-cost-v2.dmn", "shipping-cost", ImportResultDto.class).getBody();
        assertThat(next.newModel()).isFalse();
        assertThat(next.version().version()).isEqualTo("1.0.1");
        assertThat(next.version().status()).isEqualTo("DRAFT");
    }

    @Test
    void rejectsDoctypeNonDmnAndEmptyUploads() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE definitions [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20240513/MODEL/\" name=\"x\">&x;</definitions>";
        ResponseEntity<String> doctype = upload("analyst", xxe, "x.dmn", null, String.class);
        assertThat(doctype.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(doctype.getBody()).contains("DOCTYPE");

        ResponseEntity<String> notDmn = upload("analyst", "<html><body>hi</body></html>", "x.dmn", null, String.class);
        assertThat(notDmn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> garbage = upload("analyst", "this is not xml", "x.dmn", null, String.class);
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String noDecisions = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20240513/MODEL/\" id=\"e\" name=\"Empty\" "
                + "namespace=\"https://example.org/e\"/>";
        ResponseEntity<String> empty = upload("analyst", noDecisions, "e.dmn", null, String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody()).contains("no decisions");

        ResponseEntity<String> badKey = upload("analyst", "<definitions/>", "k.dmn", "Bad Key!", String.class);
        assertThat(badKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
