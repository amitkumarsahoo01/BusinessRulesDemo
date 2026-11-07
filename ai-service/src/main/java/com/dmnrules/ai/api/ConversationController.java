package com.dmnrules.ai.api;

import com.dmnrules.ai.admin.AdminAuthorizer;
import com.dmnrules.ai.admin.AdminInterceptor;
import com.dmnrules.ai.chat.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** The caller is the user verified by {@link AdminInterceptor}, never a self-declared X-User header. */
@RestController
@RequestMapping("/api/ai")
public class ConversationController {

    private final ChatService chatService;

    public ConversationController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/conversations")
    public StartConversationResponse start(
            @RequestBody(required = false) StartConversationRequest request,
            @RequestAttribute(AdminInterceptor.USER_ATTRIBUTE) AdminAuthorizer.VerifiedUser user) {
        StartConversationRequest body = request == null ? new StartConversationRequest(null, null, null) : request;
        UUID executionId = body.pinnedExecutionId() == null ? null : UUID.fromString(body.pinnedExecutionId());
        UUID conversationId = chatService.startConversation(
                user.username(), body.pinnedModelKey(), body.pinnedVersion(), executionId);
        return new StartConversationResponse(conversationId.toString());
    }

    @PostMapping(value = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> sendMessage(
            @PathVariable UUID id, @RequestBody SendMessageRequest request,
            @RequestAttribute(AdminInterceptor.USER_ATTRIBUTE) AdminAuthorizer.VerifiedUser user) {
        return chatService.streamMessage(id, request.content(), user.username());
    }

    @GetMapping("/conversations/{id}/messages")
    public Object history(@PathVariable UUID id) {
        return chatService.history(id);
    }
}
