package com.dmnrules.decision.integration;

import com.dmnrules.decision.author.ItemDefinitionDto;
import com.dmnrules.decision.author.ModelGraphDto;
import com.dmnrules.decision.model.ModelVersionRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON Schema import through the API: preview writes nothing; applying branches a draft from the
 * published version (which stays byte-for-byte the same), the types are readable from the draft,
 * name clashes are refused unless replacing, and read-only roles cannot import.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@org.springframework.context.annotation.Import(DemoUserBasicAuth.class)
class DataTypeImportIntegrationTest {

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

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    private static final String MODEL = "insurance-premium";
    private static final String SCHEMA = """
            {"title": "Vehicle", "type": "object",
             "properties": {"make": {"type": "string"}, "year": {"type": "integer", "minimum": 1950},
                            "fuel": {"type": "string", "enum": ["PETROL", "DIESEL", "ELECTRIC"]}}}""";

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<Map<String, Object>> importAs(String user, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User", user);
        return restTemplate.exchange("/api/models/" + MODEL + "/data-types/import", HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private List<ModelVersionRow> versions() {
        return restTemplate.exchange("/api/models/" + MODEL + "/versions", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ModelVersionRow>>() {
                }).getBody();
    }

    @Test
    void previewWritesNothingThenApplyBranchesADraftAndKeepsThePublishedVersion() {
        List<ModelVersionRow> before = versions();
        ModelVersionRow published = before.stream().filter(v -> v.status().equals("PUBLISHED")).reduce((a, b) -> b)
                .orElseThrow();

        // Preview: the schema may be sent as pasted text.
        ResponseEntity<Map<String, Object>> preview = importAs("analyst",
                Map.of("schema", SCHEMA, "createInputs", true, "apply", false));
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).containsEntry("applied", false).containsEntry("baseVersion", published.version());
        assertThat((List<Map<String, Object>>) preview.getBody().get("types")).extracting(t -> t.get("name"))
                .contains("Vehicle", "Fuel");
        assertThat(versions()).hasSameSizeAs(before);

        // Apply: a new draft, branched from the published version.
        ResponseEntity<Map<String, Object>> applied = importAs("analyst",
                Map.of("schema", SCHEMA, "createInputs", true, "apply", true));
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> version = (Map<String, Object>) applied.getBody().get("version");
        assertThat(version).containsEntry("status", "DRAFT").containsEntry("authoredBy", "analyst");
        assertThat((String) version.get("changeNote")).contains("Vehicle");
        assertThat((List<String>) applied.getBody().get("inputs")).contains("make", "year", "fuel");

        ModelVersionRow publishedAfter = versions().stream().filter(v -> v.id().equals(published.id())).findFirst()
                .orElseThrow();
        assertThat(publishedAfter.xmlSha256()).isEqualTo(published.xmlSha256());
        assertThat(publishedAfter.status()).isEqualTo("PUBLISHED");

        // The draft's structure now lists the imported types.
        ModelGraphDto graph = restTemplate.getForObject(
                "/api/models/" + MODEL + "/drafts/" + version.get("id") + "/graph", ModelGraphDto.class);
        assertThat(graph.itemDefinitions()).extracting(ItemDefinitionDto::name).contains("Vehicle", "Fuel");

        // Same names again: refused unless replacing, then accepted into the same draft.
        assertThat(importAs("analyst", Map.of("schema", SCHEMA, "apply", true)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map<String, Object>> replaced = importAs("analyst",
                Map.of("schema", SCHEMA, "apply", true, "replaceExisting", true));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<String, Object>) replaced.getBody().get("version")).get("id")).isEqualTo(version.get("id"));
    }

    @Test
    void readOnlyRolesCannotImportAndBadSchemasAreRejected() {
        assertThat(importAs("auditor", Map.of("schema", SCHEMA, "apply", false)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map<String, Object>> notJson = importAs("analyst", Map.of("schema", "{not json", "apply", false));
        assertThat(notJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) notJson.getBody().get("detail")).contains("not valid JSON");

        assertThat(restTemplate.exchange("/api/models/no-such-model/data-types/import", HttpMethod.POST,
                new HttpEntity<>(Map.of("schema", SCHEMA)), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
