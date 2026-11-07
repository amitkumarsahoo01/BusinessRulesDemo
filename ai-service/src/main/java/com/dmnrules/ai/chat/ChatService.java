package com.dmnrules.ai.chat;

import com.dmnrules.ai.llm.LlmRouter;
import com.dmnrules.ai.retrieval.DocumentRetrievalTools;
import com.dmnrules.ai.tools.RuleTools;
import com.dmnrules.ai.tools.ToolCallLog;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            You are the rule analyst for a DMN decision-rules platform. You explain what decision
            models do, why they produced a given outcome, and what would happen with different
            inputs. You never decide anything yourself and you never guess at rule content or past
            outcomes — you always call a tool to find out, then answer from what the tool actually
            returned.

            Rules:
            - Every factual claim about a model, a decision, a rule row, or a past execution must
              come from a tool call you actually made this turn (or earlier in this conversation).
              Never state a credit-score threshold, a rule outcome, or an execution's result from
              memory or inference.
            - Cite your sources inline: name the model version, the decision name, the rule row
              number (1-indexed, as returned by the tools), or the execution ID that backs each
              claim.
            - If the user asks a "what if" question, call simulate — never compute or guess the
              answer yourself.
            - If the user asks you to change or add a rule, call proposeChange. Always tell them
              plainly that this only creates a draft and still needs validation, testing, and
              approval before it affects any real decision — you cannot publish anything yourself.
            - If a tool call fails or returns nothing useful, say so rather than filling the gap
              with a guess.
            - Keep answers concise and use Markdown (short paragraphs, bullet points for lists of
              rows or executions).
            """;

    private final LlmRouter llmRouter;
    private final RuleTools ruleTools;
    private final DocumentRetrievalTools documentRetrievalTools;
    private final ConversationRepository conversationRepository;
    private final InteractionAuditRepository auditRepository;

    public ChatService(
            LlmRouter llmRouter, RuleTools ruleTools, DocumentRetrievalTools documentRetrievalTools,
            ConversationRepository conversationRepository, InteractionAuditRepository auditRepository) {
        this.llmRouter = llmRouter;
        this.ruleTools = ruleTools;
        this.documentRetrievalTools = documentRetrievalTools;
        this.conversationRepository = conversationRepository;
        this.auditRepository = auditRepository;
    }

    public UUID startConversation(String createdBy, String pinnedModelKey, String pinnedVersion, UUID pinnedExecutionId) {
        return conversationRepository.create(createdBy, pinnedModelKey, pinnedVersion, pinnedExecutionId);
    }

    public List<ChatMessageDto> history(UUID conversationId) {
        if (conversationRepository.findById(conversationId).isEmpty()) {
            throw new ConversationNotFoundException(conversationId);
        }
        return auditRepository.findByConversation(conversationId).stream()
                .map(row -> new ChatMessageDto(row.role(), row.content(), row.toolCallsJson(), row.at()))
                .toList();
    }

    public Flux<String> streamMessage(UUID conversationId, String userContent, String username) {
        ConversationRow conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        List<Message> history = auditRepository.findByConversation(conversationId).stream()
                .<Message>map(row -> "USER".equals(row.role())
                        ? new UserMessage(row.content())
                        : new AssistantMessage(row.content()))
                .toList();

        auditRepository.record(conversationId, "USER", userContent, null, null);

        List<ToolCallLog.Entry> toolLog = new CopyOnWriteArrayList<>();
        StringBuilder fullResponse = new StringBuilder();

        // Read the active client once per message: an admin switching provider mid-stream doesn't
        // affect a reply already in progress.
        return llmRouter.chatClient().prompt()
                .system(SYSTEM_PROMPT + pinnedContextNote(conversation))
                .messages(history)
                .user(userContent)
                .tools(ruleTools, documentRetrievalTools)
                .toolContext(Map.of("username", username, ToolCallLog.CONTEXT_KEY, toolLog))
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> auditRepository.record(conversationId, "ASSISTANT", fullResponse.toString(), toolLog, null))
                .doOnError(e -> auditRepository.record(
                        conversationId, "ASSISTANT", "[error generating response: " + e.getMessage() + "]", toolLog, null));
    }

    private String pinnedContextNote(ConversationRow conversation) {
        if (conversation.pinnedModelKey() == null && conversation.pinnedExecutionId() == null) {
            return "";
        }
        StringBuilder note = new StringBuilder("\n\nPinned context for this conversation: ");
        if (conversation.pinnedModelKey() != null) {
            note.append("model '").append(conversation.pinnedModelKey()).append('\'');
            if (conversation.pinnedVersion() != null) {
                note.append(" version ").append(conversation.pinnedVersion());
            }
            note.append(". ");
        }
        if (conversation.pinnedExecutionId() != null) {
            note.append("execution '").append(conversation.pinnedExecutionId())
                    .append("' — if the user asks about \"this execution\" or \"this decision\" without naming one, "
                            + "call getExecution with this ID. ");
        }
        return note.toString();
    }
}
