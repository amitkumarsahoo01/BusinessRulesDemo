package com.dmnrules.decision.author;

/**
 * One DRG element. type: INPUT_DATA | DECISION | BKM | KNOWLEDGE_SOURCE. kind (decisions/BKMs only):
 * DECISION_TABLE | LITERAL_EXPRESSION | FUNCTION | OTHER. hitPolicy/ruleCount are set for decision
 * tables; expressionText for literal expressions.
 */
public record GraphNodeDto(
        String id, String name, String type, String typeRef, String kind,
        String hitPolicy, Integer ruleCount, String expressionText, String description) {
}
