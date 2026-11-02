package com.kalo.document.service;

import com.kalo.common.exception.InvalidOperationException;
import java.util.List;

/** Safe reason codes only; no owner IDs, storage references or rejection free text. */
public class FleetDocumentRequiredException extends InvalidOperationException {
    public record Issue(String ownerType, String documentType, String reason) {}
    private final List<Issue> issues;

    public FleetDocumentRequiredException(List<Issue> issues) {
        super("Current approved and unexpired fleet documents are required");
        this.issues = List.copyOf(issues);
    }

    public List<Issue> getIssues() { return issues; }
}
