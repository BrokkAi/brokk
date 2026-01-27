package ai.brokk.executor.jobs;

/**
 * Utility for constructing the PR review prompt used by the LLM.
 */
public final class PrReviewPromptBuilder {
    private PrReviewPromptBuilder() {}

    /**
     * Build the review prompt text for a given diff and comment policy.
     */
    public static String buildReviewPrompt(String diff, PrReviewService.Severity minSeverity, int maxComments) {
        String fencedDiff = "```diff\nDIFF_START\n" + diff + "\nDIFF_END\n```";

        // Compose the policy lines using explicit phrasing that tests can rely on.
        String severityLine = "ONLY emit comments with severity >= " + minSeverity.name() + ".";
        String maxLine =
                "MAX " + maxComments + " comments total. Merge similar issues into one comment instead of repeating.";

        return """
                You are performing a Pull Request diff review. The diff to review is provided
                *between the fenced code block marked DIFF_START and DIFF_END*.
                Everything inside that block is code - do not ignore any part of it.

                %s

                IMPORTANT: Line Number Format
                -----------------------------
                Each diff line is annotated with explicit OLD/NEW line numbers for your reference:

                - Added lines:   "[OLD:- NEW:N] +<content>" where N is the exact line number in the new file
                - Removed lines: "[OLD:N NEW:-] -<content>" where N is the exact line number in the old file
                - Context lines: "[OLD:N NEW:N]  <content>" where N/N are the exact line numbers in the old/new files

                When writing your review, cite line numbers using just the number, choosing the appropriate number:
                - For additions ("+"): use the NEW line number from the annotation
                - For deletions ("-"): use the OLD line number from the annotation
                - For context/unchanged lines (" "): use the NEW line number from the annotation

                Your task:
                Analyze the diff content above using the context of related methods and code files.

                OUTPUT FORMAT
                -------------
                You MUST output a single JSON object with this exact structure:

                {
                  "summaryMarkdown": "## Brokk PR Review\\n\\n[1-3 sentences describing what changed and only the most important risks]",
                  "comments": [
                    {
                      "path": "src/main/java/Example.java",
                      "line": 42,
                      "severity": "HIGH",
                      "bodyMarkdown": "Describe the issue, why it matters, and a minimal actionable fix."
                    }
                  ]
                }

                REQUIRED FIELDS:
                - "summaryMarkdown": MUST start with exactly "## Brokk PR Review" followed by a newline and 1-3 sentences.
                - "comments": Array of inline comment objects (MUST be [] if nothing meets threshold).

                Each comment object MUST have:
                - "path": File path relative to repository root (e.g., "src/main/java/Foo.java")
                - "line": Single integer line number from the diff annotation:
                  * For "+" lines: use the NEW line number
                  * For "-" lines: use the OLD line number
                  * For " " lines: use the NEW line number
                - "severity": One of "CRITICAL"|"HIGH"|"MEDIUM"|"LOW"
                - "bodyMarkdown": Markdown description of the issue with a minimal actionable fix

                SEVERITY DEFINITIONS:
                - CRITICAL: likely exploitable security issue, data loss/corruption, auth/permission bypass, remote crash, or severe production outage risk.
                - HIGH: likely bug, race condition, broken error handling, incorrect logic, resource leak, significant performance regression, or high-impact maintainability risk.
                - MEDIUM: could become a bug; edge-case correctness; non-trivial readability/maintenance concerns.
                - LOW: style, nits, subjective preference, minor readability, minor refactors.

                COMMENT POLICY (STRICT):
                - %s
                - %s
                - Do NOT comment on missing import statements.
                - Do NOT flag undefined symbols or assume the code will fail to compile.
                - Do NOT attempt to act as a compiler or duplicate CI/build failure messages. Assume the compiler and CI will surface genuine compilation issues; prioritize human, context-aware review and actionable suggestions instead.
                - NO style-only/nit suggestions. NO repetitive variants of the same point.
                - SKIP correct code and skip minor improvements.
                - If nothing meets the requested severity threshold, "comments" MUST be [].

                OUTPUT ONLY THE JSON OBJECT. Do not include any text before or after the JSON.
                """
                .formatted(severityLine, maxLine, fencedDiff);
    }
}
