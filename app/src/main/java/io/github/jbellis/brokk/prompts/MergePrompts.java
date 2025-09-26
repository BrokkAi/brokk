package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;

/**
 * Prompt helper for explaining a commit range with emphasis on public API changes and for constructing
 * conflict-resolution prompts for a single file.
 */
public final class MergePrompts {

    public static final MergePrompts instance = new MergePrompts();

    private MergePrompts() {}

    /**
     * Build messages instructing the LLM to summarize the supplied unified diff with strong focus on public API changes
     * and merge-conflict-relevant information.
     *
     * @param diff A unified diff for the inclusive commit range.
     * @param from The starting revision (inclusive).
     * @param to The ending revision (inclusive).
     * @return messages to send to the LLM; empty if the diff is blank.
     */
    public List<ChatMessage> collectMessages(String diff, String from, String to) {
        if (diff.isBlank()) {
            return List.of();
        }

        var system = new SystemMessage(explanationSystemIntro());

        var user = new UserMessage(
                """
                <range from="%s" to="%s" inclusive="true">
                <instructions>
                Produce a high-fidelity but concise explanation of the PUBLIC API
                changes in these commits. Your report will be used to resolve merge conflicts.

                Requirements:
                - Organize the output with Markdown headings and concise bulleted explanations.
                - First, provide a short executive summary
                - Then, detail "Changed Public APIs":
                  - Added/removed/renamed types (classes, interfaces, records, enums)
                  - Added/removed/changed public/protected methods and fields
                  - Signature changes (parameters, generics, exceptions, visibility, static/final)
                  - Behavioral changes that materially affect API contracts or threading, nullability, or error handling
                - Call out BREAKING CHANGES clearly
                - Mention notable refactors, file moves/renames, and dependency/configuration changes that affect integration.
                - Where the diff is ambiguous, state assumptions explicitly (e.g., "likely rename", "inferred contract change").
                - Keep code snippets short and only include them when they clarify an API change.

                Deliverable: a single Markdown-formatted analysis only; no extra commentary outside of the above report.
                </instructions>

                <diff>
                %s
                </diff>
                </range>
                """
                        .stripIndent()
                        .formatted(from, to, diff));

        return List.of(system, user);
    }

    /** System prompt used when asking the LLM to explain a commit range. */
    private String explanationSystemIntro() {
        return """
               You are an expert software engineer assisting with merge conflict resolution. Carefully analyze the provided unified diff for a commit range and produce a structured,
               detailed summary with special attention to public API changes and breaking changes. Use Markdown appropriately. Be precise and thorough.
               """;
    }
}
