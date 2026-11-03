package com.dmnrules.decision.api;

import com.dmnrules.decision.audit.ExecutionNotFoundException;
import com.dmnrules.decision.author.ApproverMustDifferFromAuthorException;
import com.dmnrules.decision.author.InvalidLifecycleTransitionException;
import com.dmnrules.decision.model.ModelNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** JSON errors as {@code application/problem+json} per CLAUDE.md conventions. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ModelNotFoundException.class)
    public ProblemDetail handleModelNotFound(ModelNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Model not found");
        return problem;
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    public ProblemDetail handleExecutionNotFound(ExecutionNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Execution not found");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(InvalidLifecycleTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidLifecycleTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Invalid lifecycle transition");
        return problem;
    }

    @ExceptionHandler(ApproverMustDifferFromAuthorException.class)
    public ProblemDetail handleApproverMustDiffer(ApproverMustDifferFromAuthorException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Four-eyes violation");
        return problem;
    }
}
