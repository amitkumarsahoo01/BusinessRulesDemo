package com.dmnrules.ai.retrieval;

import com.dmnrules.ai.tools.ToolCallLog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Retrieval over rule annotations, change notes, and policy documents, embedded into pgvector
 * (docs/Architecture.md "Retrieval for documentation"). Answers "why does this rule exist"
 * questions that structure/trace data alone can't.
 */
@Component
public class DocumentRetrievalTools {

    private final VectorStore vectorStore;

    public DocumentRetrievalTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(description = "Search policy documents and rule-row business-reason annotations for "
            + "background on WHY a rule or model exists, not just what it computes. Use this for "
            + "'why' questions a bare decision table can't answer.")
    public Object searchDocs(
            @ToolParam(description = "Natural-language search query") String query,
            @ToolParam(required = false, description = "Max results, default 4") Integer topK,
            ToolContext toolContext) {
        ToolCallLog.record(toolContext, "searchDocs", query);
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK == null ? 4 : topK).build());
        return results.stream()
                .map(d -> Map.of(
                        "source", String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                        "text", d.getText()))
                .toList();
    }
}
