package com.talentmatch.application.content;

/** How much a grounding finding matters (§4.4). Stored by name in {@code application_document.findings}. */
public enum Severity {
    /** Approval needs {@code acknowledgeFindings=true}. */
    BLOCKING,
    /** A heuristic doubt, or a check on owner-written text. */
    WARNING,
    /** A repair the server made (dropped, reverted, defaulted); the output is grounded. */
    INFO
}
