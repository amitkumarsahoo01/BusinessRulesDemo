package com.dmnrules.decision.author;

/** Enforces "authors cannot approve their own drafts" (docs/Architecture.md Security; BUILD_PLAN M3). */
public class ApproverMustDifferFromAuthorException extends RuntimeException {

    public ApproverMustDifferFromAuthorException(String author) {
        super("Approver must be different from the author ('" + author + "')");
    }
}
