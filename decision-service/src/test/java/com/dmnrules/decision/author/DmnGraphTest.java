package com.dmnrules.decision.author;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DmnGraphTest {

    private final DmnXmlEditor editor = new DmnXmlEditor();

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void readGraph_coversInputsTablesLiteralsBkmsAndKnowledgeSources() throws Exception {
        ModelGraphDto graph = editor.readGraph(read("src/test/resources/fixtures/shipping-cost.dmn"));

        assertThat(graph.name()).isEqualTo("Shipping Cost");
        assertThat(graph.nodes()).extracting(GraphNodeDto::type)
                .containsExactlyInAnyOrder("INPUT_DATA", "INPUT_DATA", "BKM", "DECISION", "KNOWLEDGE_SOURCE");

        GraphNodeDto cost = graph.nodes().stream().filter(n -> n.name().equals("Shipping Cost")).findFirst().orElseThrow();
        assertThat(cost.kind()).isEqualTo("LITERAL_EXPRESSION");
        assertThat(cost.expressionText()).isEqualTo("Weight * Rate Per Kg(Zone)");
        assertThat(cost.typeRef()).isEqualTo("number");

        assertThat(graph.edges()).containsExactlyInAnyOrder(
                new GraphEdgeDto("in_weight", "dec_cost", "INFORMATION"),
                new GraphEdgeDto("in_zone", "dec_cost", "INFORMATION"),
                new GraphEdgeDto("bkm_rate", "dec_cost", "KNOWLEDGE"),
                new GraphEdgeDto("dec_cost", "ks_policy", "AUTHORITY"));

        assertThat(graph.itemDefinitions()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("tZone");
            assertThat(item.typeRef()).isEqualTo("string");
            assertThat(item.allowedValues()).isEqualTo("\"EU\",\"US\"");
        });
    }

    @Test
    void readGraph_reportsHitPolicyRuleCountAndNestedTypesForSeedModel() throws Exception {
        ModelGraphDto graph = editor.readGraph(read("src/main/resources/seed/dmn/loan-eligibility-1.1.0.dmn"));

        GraphNodeDto risk = graph.nodes().stream().filter(n -> n.name().equals("Risk Category")).findFirst().orElseThrow();
        assertThat(risk.kind()).isEqualTo("DECISION_TABLE");
        assertThat(risk.hitPolicy()).isNotBlank();
        assertThat(risk.ruleCount()).isPositive();

        assertThat(graph.itemDefinitions()).extracting(ItemDefinitionDto::name).contains("tApplicant", "tLoan");
        ItemDefinitionDto applicant = graph.itemDefinitions().stream()
                .filter(i -> i.name().equals("tApplicant")).findFirst().orElseThrow();
        assertThat(applicant.components()).extracting(ItemDefinitionDto::name).contains("age", "creditScore");
    }
}
