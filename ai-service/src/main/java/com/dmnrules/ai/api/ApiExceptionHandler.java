package com.dmnrules.ai.api;

import com.dmnrules.ai.chat.ConversationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

/** JSON errors as {@code application/problem+json} per CLAUDE.md conventions. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFound(ConversationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Conversation not found");
        return problem;
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ProblemDetail handleDecisionServiceError(RestClientResponseException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "decision-service call failed: " + e.getMessage());
        problem.setTitle("Upstream error");
        return problem;
    }
}
