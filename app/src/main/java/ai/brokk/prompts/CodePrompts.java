package ai.brokk.prompts;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AbstractService;
import ai.brokk.EditBlock;
import ai.brokk.IContextManager;
import ai.brokk.SyntaxAwareConfig;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.TaskMeta;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.util.Messages;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/** Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks. */
public class CodePrompts {
    private static final Logger logger = LogManager.getLogger(CodePrompts.class);
    public static final CodePrompts instance = new CodePrompts();
    private static final Pattern BRK_MARKER_PATTERN =
            Pattern.compile("^BRK_(CLASS|FUNCTION)\\s+(.+)$", Pattern.MULTILINE);

    @Blocking
    public static Set<InstructionsFlags> instructionsFlags(Context ctx) {
        return instructionsFlags(ctx.getEditableFragments()
                .flatMap(f -> f.files().join().stream())
                .collect(Collectors.toSet()));
    }

    public static Set<InstructionsFlags> instructionsFlags(Set<ProjectFile> editableFiles) {
        var flags = new HashSet<InstructionsFlags>();

        // we'll inefficiently read the files every time this method is called but at least we won't do it twice
        var fileContents = editableFiles.stream()
                .collect(Collectors.toMap(f -> f, f -> f.read().orElse("")));

        // Enable SYNTAX_AWARE only if:
        // (a) editable set is non-empty AND
        // (b) every editable file's extension is in SYNTAX_AWARE_EXTENSIONS (case-insensitive)
        var nonEmpty = !editableFiles.isEmpty();
        var editableExtensions = fileContents.keySet().stream()
                .map(f -> f.extension().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        var allEditableAreAllowed = editableExtensions.stream().allMatch(SyntaxAwareConfig::isSyntaxAwareExtension);

        if (nonEmpty && allEditableAreAllowed) {
            flags.add(InstructionsFlags.SYNTAX_AWARE);
            logger.debug(
                    "Syntax-aware edits enabled for extensions {} ({} editable file(s), workspace extensions: {}).",
                    SyntaxAwareConfig.syntaxAwareExtensions(),
                    fileContents.size(),
                    editableExtensions);
        } else {
            if (!nonEmpty) {
                logger.debug("Syntax-aware edits disabled: editable set is empty.");
            } else {
                logger.debug(
                        "Syntax-aware edits disabled: editable set contains extensions {} not in SYNTAX_AWARE_EXTENSIONS {}.",
                        editableExtensions,
                        SyntaxAwareConfig.syntaxAwareExtensions());
            }
        }

        // set MERGE_AGENT_MARKERS if any editable file contains both BRK_CONFLICT_BEGIN_ and BRK_CONFLICT_END_
        var hasMergeMarkers = fileContents.values().stream()
                .filter(s -> s.contains("BRK_CONFLICT_BEGIN_") && s.contains("BRK_CONFLICT_END_"))
                .collect(Collectors.toSet());
        if (!hasMergeMarkers.isEmpty()) {
            flags.add(InstructionsFlags.MERGE_AGENT_MARKERS);
            IContextManager.logger.debug("Files with merge markers: {}", hasMergeMarkers);
        }

        return flags;
    }

    private static final String ELIDED_BLOCK_PLACEHOLDER = "[elided SEARCH/REPLACE block]";

    /**
     * Redacts SEARCH/REPLACE blocks from an AiMessage. If the message contains S/R blocks, they are replaced with
     * "[elided SEARCH/REPLACE block]". If the message does not contain S/R blocks, or if the redacted text is blank,
     * Optional.empty() is returned.
     *
     * @param aiMessage The AiMessage to process.
     * @return An Optional containing the redacted AiMessage, or Optional.empty() if no message should be added.
     */
    public static Optional<AiMessage> redactEditBlocks(AiMessage aiMessage) {
        return redactEditBlocks(aiMessage, true);
    }

    /**
     * Redacts SEARCH/REPLACE blocks from an AiMessage.
     *
     * @param aiMessage The AiMessage to process.
     * @param leaveMarker If true, S/R blocks are replaced with "[elided SEARCH/REPLACE block]".
     *                  If false, they are removed entirely.
     * @return An Optional containing the redacted AiMessage, or Optional.empty() if no message should be added.
     */
    public static Optional<AiMessage> redactEditBlocks(AiMessage aiMessage, boolean leaveMarker) {
        var text = aiMessage.text();

        if (text == null) {
            return aiMessage.hasToolExecutionRequests() ? Optional.of(aiMessage) : Optional.empty();
        }

        var parsedResult = EditBlockParser.instance.parse(text, Collections.emptySet());
        boolean hasSrBlocks = parsedResult.blocks().stream().anyMatch(b -> b.block() != null);

        if (!hasSrBlocks) {
            return text.isBlank() ? Optional.empty() : Optional.of(aiMessage);
        }

        var blocks = parsedResult.blocks();
        var sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            var ob = blocks.get(i);
            if (ob.block() == null) {
                sb.append(ob.text());
            } else if (leaveMarker) {
                sb.append(ELIDED_BLOCK_PLACEHOLDER);
                if (i + 1 < blocks.size() && blocks.get(i + 1).block() != null) {
                    sb.append('\n');
                }
            }
        }

        String redactedText = sb.toString();
        if (redactedText.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(
                aiMessage.hasToolExecutionRequests()
                        ? new AiMessage(redactedText, aiMessage.toolExecutionRequests())
                        : new AiMessage(redactedText));
    }

    /**
     * Orchestrates history-message redaction.
     *
     * <p>If {@code redactToolCalls} is true, tool execution results are omitted and {@link AiMessage}s with tool
     * execution requests are converted into passive historical descriptions. Regardless of {@code redactToolCalls},
     * this method always redacts SEARCH/REPLACE blocks from {@link AiMessage}s via {@link #redactEditBlocks(AiMessage)}.
     */
    public static List<ChatMessage> redactHistoryMessages(List<ChatMessage> messages, boolean redactToolCalls) {
        var toolProcessed = redactToolCalls ? redactToolCallsFromOtherModels(messages) : messages;

        return toolProcessed.stream()
                .flatMap(msg -> msg instanceof AiMessage ai ? redactEditBlocks(ai).stream() : Stream.of(msg))
                .toList();
    }

    /**
     * Removes tool-call semantics from history: drops tool execution result messages and rewrites tool-request
     * {@link AiMessage}s into passive, non-executable text.
     */
    private static List<ChatMessage> redactToolCallsFromOtherModels(List<ChatMessage> messages) {
        var result = new ArrayList<ChatMessage>();

        for (var message : messages) {
            // skip tool execution result messages
            if (message instanceof ToolExecutionResultMessage) {
                continue;
            }

            // redact tool execution requests
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                var existingText = aiMessage.text();
                var prefix = (existingText != null && !existingText.isBlank()) ? existingText + "\n\n" : "";

                var toolDescriptions = aiMessage.toolExecutionRequests().stream()
                        .map(Messages::getRedactedRepr)
                        .collect(Collectors.joining("\n"));

                var rewrittenText = prefix + "[Historical tool usage by a different model]\n" + toolDescriptions;
                result.add(new AiMessage(rewrittenText));
                continue;
            }

            result.add(message);
        }

        return result;
    }

    public final List<ChatMessage> collectCodeMessages(
            StreamingChatModel model,
            TaskResult.TaskMeta taskMeta,
            Context ctx,
            List<ChatMessage> prologue,
            List<ChatMessage> taskMessages,
            UserMessage request,
            Set<SpecialTextType> suppressedTypes,
            String goal) {
        var cm = ctx.getContextManager();
        var messages = new ArrayList<ChatMessage>();
        AbstractService service = cm.getService();

        var flags = instructionsFlags(ctx);
        var data = new CodeSystemData(
                GraphicsEnvironment.isHeadless() ? "decide what the most logical interpretation is" : "ask questions",
                !service.isReasoning(model),
                flags.contains(InstructionsFlags.SYNTAX_AWARE),
                flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS),
                EditBlockExamples.buildExamples(flags),
                service.isLazy(model) ? SystemPrompts.LAZY_REMINDER : SystemPrompts.OVEREAGER_REMINDER,
                prologue.isEmpty() ? goal : null);

        try {
            messages.add(new SystemMessage(CODE_SYSTEM_TEMPLATE.apply(data)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var codeAgentWorkspace = WorkspacePrompts.getMessagesForCodeAgent(ctx, suppressedTypes);
        messages.addAll(getHistoryMessages(ctx, taskMeta));
        messages.addAll(prologue);
        messages.addAll(codeAgentWorkspace.workspace());
        messages.addAll(taskMessages);

        // Append TOC reminder to the request
        var tocReminder = """
                \n
                %s
                """
                .formatted(WorkspacePrompts.formatToc(ctx, suppressedTypes));
        var augmentedRequest = new UserMessage(Messages.getText(request) + tocReminder);
        messages.add(augmentedRequest);

        return messages;
    }

    public UserMessage codeRequest(Context ctx, String input, StreamingChatModel model) {
        String guidance =
                """
                %s

                If you need to propose changes to code you can't see, tell me their full class or file names and ask me to add them to the Workspace; end your reply and wait for my approval.
                """
                        .formatted(input.trim());

        return new UserMessage(guidance.trim());
    }

    /**
     * Result of building retry messages for apply failures.
     * Contains both the tagged AI message (to replace the last AI message in history)
     * and the user message with failure details.
     */
    public record ApplyRetryMessages(AiMessage taggedAiMessage, UserMessage retryRequest) {}

    /**
     * Consolidates the construction of retry messages when SEARCH/REPLACE blocks fail to apply.
     *
     * @param originalAiText The raw text of the AI's previous response to be tagged.
     * @param blockResults The outcome of applying the blocks.
     * @param buildError The current build error, if any.
     * @return An ApplyRetryMessages containing the tagged AiMessage and retry UserMessage.
     */
    public static ApplyRetryMessages buildApplyRetryMessages(
            String originalAiText, List<EditBlock.ApplyResult> blockResults, String buildError, int startingIndex) {
        var failures = blockResults.stream().filter(r -> !r.succeeded()).toList();
        assert !failures.isEmpty();

        // Remove any existing BRK_BLOCK markers before re-tagging
        var cleanedText = originalAiText.replaceAll("(?m)^\\s*\\[BRK_BLOCK_\\d+\\]\\s*\\n?", "");

        // Build the tagged AI message
        var taggedText =
                """
        [HARNESS NOTE: some edits in this message failed to apply. Your SEARCH/REPLACE blocks have been tagged
        with BRK_BLOCK_$N markers that will be referenced in the subsequent feedback.]
        %s
        """
                        .formatted(EditBlockParser.instance.tagBlocks(cleanedText, startingIndex));
        var taggedAiMessage = new AiMessage(taggedText);

        var successIndices = IntStream.range(0, blockResults.size())
                .filter(i -> blockResults.get(i).succeeded())
                .mapToObj(i -> "BRK_BLOCK_" + (startingIndex + i + 1))
                .toList();

        // Track original block indices (1-based) for each failure
        record IndexedFailure(EditBlock.ApplyResult result, int blockIndex) {}
        var indexedFailures = IntStream.range(0, blockResults.size())
                .filter(i -> !blockResults.get(i).succeeded())
                .mapToObj(i -> new IndexedFailure(blockResults.get(i), startingIndex + i + 1))
                .toList();

        var failuresByFile = indexedFailures.stream()
                .filter(f -> f.result().block().rawFileName() != null)
                .collect(Collectors.groupingBy(
                        f -> requireNonNull(f.result().block().rawFileName()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                .sorted(Comparator.comparingInt(IndexedFailure::blockIndex))
                                .toList())));

        var fileDetails = failuresByFile.entrySet().stream()
                .map(entry -> {
                    var filename = entry.getKey();
                    var fileFailures = entry.getValue();

                    String failedBlocksList = fileFailures.stream()
                            .map(f -> formatBlockFailure(f.result(), f.blockIndex()))
                            .collect(Collectors.joining("\n\n"));

                    return new ApplyRetryData.FileFailure(filename, failedBlocksList);
                })
                .toList();

        var data = new ApplyRetryData(
                successIndices.isEmpty() ? "None" : String.join(", ", successIndices),
                fileDetails,
                !buildError.isBlank() && successIndices.isEmpty());

        try {
            return new ApplyRetryMessages(taggedAiMessage, new UserMessage(APPLY_RETRY_TEMPLATE.apply(data)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String formatBlockFailure(EditBlock.ApplyResult f, int blockIndex) {
        var enriched = enrichSemanticCommentary(f);
        var blockTag = "BRK_BLOCK_%d".formatted(blockIndex);

        if (enriched.isBlank()) {
            return "%s: %s".formatted(blockTag, f.reason());
        } else {
            return "%s: %s\n- %s".formatted(blockTag, f.reason(), enriched.replace("\n", "\n- "));
        }
    }

    /**
     * Enrich commentary for semantic-aware failures (BRK_CLASS / BRK_FUNCTION).
     * Preserves existing analyzer commentary (which may already include "Did you mean ..." suggestions),
     * and appends actionable guidance depending on failure reason and marker type.
     */
    private static String enrichSemanticCommentary(EditBlock.ApplyResult f) {
        var base = f.commentary() == null ? "" : f.commentary().trim();

        // Try to detect semantic markers in the original SEARCH block
        var m = BRK_MARKER_PATTERN.matcher(f.block().beforeText().strip());
        if (!m.find()) {
            return base;
        }

        var data = new SemanticEnrichmentData(
                base, m.group(1), requireNonNull(f.reason()).name());
        try {
            return SEMANTIC_ENRICHMENT_TEMPLATE.apply(data).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ChatMessage> getHistoryMessages(Context ctx, TaskMeta currentMeta) {
        var taskHistory = ctx.getTaskHistory();
        var messages = new ArrayList<ChatMessage>();

        // Merge compressed messages into a single taskhistory message
        var compressed = taskHistory.stream()
                .filter(TaskEntry::isCompressed)
                .map(TaskEntry::toString)
                .collect(Collectors.joining("\n\n"));
        if (!compressed.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(compressed)));
            messages.add(new AiMessage("Ok, I see the history."));
        }

        // Uncompressed messages: process for tool and S/R block redaction
        taskHistory.stream().filter(e -> !e.isCompressed()).forEach(e -> {
            var entryRawMessages = castNonNull(e.mopLog()).messages();
            if (entryRawMessages.isEmpty()) {
                return;
            }

            // Determine the messages to include from the entry
            var relevantEntryMessages = entryRawMessages.getLast() instanceof AiMessage
                    ? entryRawMessages
                    : entryRawMessages.subList(0, entryRawMessages.size() - 1);

            var entryMeta = e.meta();

            var currentPrimaryModel = currentMeta.primaryModel();
            var entryPrimaryModel = entryMeta == null ? null : entryMeta.primaryModel();

            // Redact tool calls if the primary models differ
            boolean redactToolCalls =
                    entryPrimaryModel != null && !currentPrimaryModel.name().equals(entryPrimaryModel.name());

            messages.addAll(redactHistoryMessages(relevantEntryMessages, redactToolCalls));
        });

        return messages;
    }

    public enum InstructionsFlags {
        SYNTAX_AWARE,
        MERGE_AGENT_MARKERS
    }

    private record CodeSystemData(
            String ambiguityGuidance,
            boolean showExplanationInstruction,
            boolean hasSyntaxAware,
            boolean hasMergeMarkers,
            String examples,
            String reminder,
            @org.jetbrains.annotations.Nullable String goal) {}

    private record ApplyRetryData(
            String successIndices, List<FileFailure> failuresByFile, boolean showBuildFailureReminder) {
        public record FileFailure(String filename, String failedBlocksList) {}
    }

    private record SemanticEnrichmentData(String base, String kind, String reason) {}

    private static final Template CODE_SYSTEM_TEMPLATE;
    private static final Template APPLY_RETRY_TEMPLATE;
    private static final Template SEMANTIC_ENRICHMENT_TEMPLATE;

    static {
        Handlebars handlebars = new Handlebars().with(EscapingStrategy.NOOP);
        handlebars.registerHelpers(ConditionalHelpers.class);
        handlebars.registerHelpers(com.github.jknack.handlebars.helper.StringHelpers.class);

        String codeSystemTemplateText =
                """
        <instructions>
        Act as an expert software developer.
        Always use best practices when coding.
        Respect and use existing conventions, libraries, etc. that are already present in the code base.

        Think about requests for changes to the supplied code.
        If a request is ambiguous, {{ambiguityGuidance}}.

        Once you understand the request you MUST:

        1. Decide if you need to propose *SEARCH/REPLACE* edits for any code whose source is not available.
           1a. You can create new files without asking!
           1b. If you only need to change individual functions whose code you CAN see,
               you may do so without having the entire file in the Workspace.
           1c. Ask for additional files if you are blocked by visibility or best practices.
                - **Do not stop** and ask for files just to add convenience methods, overloads, or helpers
                  in files that are not editable in your Workspace; if a valid solution is available, use it.
                - **Do ask** if the alternative is an "unnatural" hack.
                  For example:
                  - If you need reflection to access a private member (ask for the file to relax visibility instead).
                  - If you would have to copy-paste significant logic (ask for the file to preserve DRY).
                - **Do ask** if you do not have the APIs visible to confidently write a solution without guessing.
                  (Generally you do not need to insist on the full source when you have an api summary visible.)
           1d. When refactoring or changing signatures, adopt a "Closed World" assumption.
               Assume that the callers visible in the Workspace are the only ones that exist;
               update those visible callers as needed and proceed.

           If you need to propose changes to code you can't see,
           tell the user their full class or file names and ask them to *add them to the Context*;
           end your reply and wait for their approval.

        {{#if showExplanationInstruction~}}
        1. Explain the needed changes in a few short sentences.
        {{~/if}}
        1. Give each change as a *SEARCH/REPLACE* block.

        If an appropriate test file is in the Workspace, add or update tests to cover the changes you make.
        If no such test file exists, only create a new one if instructed to do so.

        If a file is read-only or unavailable, ask the user to add it or make it editable.

        If you do not know how to use a dependency or API correctly, you MUST stop and ask the user for help.

        If the user just says something like "ok" or "go ahead" or "do that", they probably want you
        to make SEARCH/REPLACE blocks for the code changes you just proposed.
        The user will say when they've applied your edits.
        If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.

        Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.

        All changes to files must use the *SEARCH/REPLACE* block format in the rules section.
        </instructions>
        <rules>
        EXTENDED *SEARCH/REPLACE block* Rules:

        The *SEARCH/REPLACE* engine supports multiple SEARCH types. Choose the most precise option that fits your edit.
        Line-based SEARCH remains the default for most changes.

        ## SEARCH Type Priority

        Use the first row whose description matches the change you need:

        | Priority | Type | When to use |
        |----------|------|-------------|
        {{#if hasMergeMarkers~}}
        | 1 | `BRK_CONFLICT_n` | Resolving regions wrapped in BRK_CONFLICT markers |
        {{~/if}}
        | {{#if hasMergeMarkers}}2{{else}}1{{/if}} | Line-based | Default choice for localized edits |
        {{#if hasSyntaxAware~}}
        | {{#if hasMergeMarkers}}3{{else}}2{{/if}} | `BRK_FUNCTION` | Replacing a complete method (signature + body) |
        | {{#if hasMergeMarkers}}3{{else}}2{{/if}} | `BRK_CLASS` | Replacing the entire body of a class |
        {{~/if}}
        | {{#if hasSyntaxAware}}{{#if hasMergeMarkers}}4{{else}}3{{/if}}{{else}}{{#if hasMergeMarkers}}3{{else}}2{{/if}}{{/if}} | `BRK_ENTIRE_FILE` | Creating a new file or rewriting most of a file |

        Every *SEARCH/REPLACE block* must use this format:
        1. The opening fence: ```
        2. The *FULL* file path alone on a line, verbatim. No comment tokens, no bold asterisks, no quotes, no escaping of characters, etc.
        3. The start of search block: <<<<<<< SEARCH
        4. One of the following SEARCH types:
          - Line-based SEARCH: a contiguous chunk of the EXACT lines to search for in the existing source code,
        {{#if hasSyntaxAware~}}
          - Syntax-aware SEARCH: a single line consisting of BRK_CLASS or BRK_FUNCTION, followed by the FULLY QUALIFIED class or function name:
            `BRK_[CLASS|FUNCTION] $fqname`. This applies to any named class-like (struct, record, interface, etc)
            or function-like (method, static method) entity, but NOT anonymous ones. `BRK_FUNCTION` replaces an
            EXISTING function's signature, annotations, and body, including any Javadoc; it CANNOT create new functions
            without an existing one to replace. Do not generate more than one BRK_CLASS or BRK_FUNCTION edit
            for the same fully qualified symbol in a single response; combine all changes for that symbol into a single block.

            For BRK_CLASS specifically: include only the class/struct/interface declaration and its members (the class
            header and body). Do NOT include file-level `package` declarations or `import` statements inside a
            BRK_CLASS REPLACE — package and import lines belong at the top of the file and must be edited separately.
            If you need to modify package or import statements, perform a separate line-based SEARCH/REPLACE that
            targets those lines, or use `BRK_ENTIRE_FILE` for a full-file replacement. Including `package` or `import`
            lines in a BRK_CLASS REPLACE will cause duplication and can introduce build errors.
        {{~/if}}
        {{#if hasMergeMarkers~}}
          - Conflict SEARCH: a single line consisting of the conflict marker ID: `BRK_CONFLICT_$n`
            where $n is the conflict number.
        {{~/if}}
          - Full-file SEARCH: a single line `BRK_ENTIRE_FILE` indicating replace-the-entire-file, or create-new-file
        5. The dividing line: =======
        6. The lines to replace into the source code
        7. The end of the replace block: >>>>>>> REPLACE
        8. The closing fence: ```

        ALWAYS use the *FULL* file path, as shown to you by the user. No other text should appear on the marker lines.

        ALWAYS base SEARCH/REPLACE blocks on the editable code in the Workspace. Excerpts of code or pseudocode
        may be given in your goal, but this is NOT a source of truth of the current files' contents.

        ## Examples (format only; illustrative, not real code)
        Follow these patterns exactly when you emit edits.
        {{examples}}

        *SEARCH/REPLACE* blocks will *fail* to apply if the SEARCH payload matches multiple occurrences in the content.
        For line-based edits, this means you must include enough lines to uniquely match each set of lines that need to change,
        and avoid using syntax-aware edits for overloaded functions.

        Keep *SEARCH/REPLACE* blocks concise.
        Break large changes into a series of smaller blocks that each change a small portion.

        Avoid generating overlapping *SEARCH/REPLACE* blocks, combine them into a single edit.
        If you want to move code within a filename, use 2 blocks: one to delete from the old location,
        and one to insert in the new location.

        Pay attention to which filenames the user wants you to edit, especially if they are asking
        you to create a new filename.

        NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
        use vanilla ascii single and double quotes.

        When generating *SEARCH/REPLACE* blocks, choose the most precise SEARCH type that fits your change:
        - Line-based SEARCH is the primary option for most edits. Use it for adding, modifying, or removing localized
          blocks of code, including new methods or inner classes in existing files. Include the changing lines plus a
          few surrounding lines only when needed for uniqueness.
        {{#if hasMergeMarkers~}}
        - When you are fixing conflicts wrapped in BRK_CONFLICT markers, use conflict SEARCH (`BRK_CONFLICT_n`)
          so that the entire conflict region is replaced in one block.
        {{~/if}}
        {{#if hasSyntaxAware~}}
        - Use syntax-aware SEARCH when you are replacing an entire class or function:
          - `BRK_FUNCTION` for a complete, non-overloaded method (signature, annotations, body, and Javadoc).
          - `BRK_CLASS` for the full body of a class-like declaration (without the surrounding package/imports).
        {{~/if}}
        - Use `BRK_ENTIRE_FILE` when you are creating a brand new file, or when you are intentionally rewriting most
          of an existing file so that a whole-file replacement is clearer than multiple smaller edits.

        **IMPORTANT**: The `BRK_` tokens are NEVER part of the file content, they are entity locators used only in SEARCH.
        When writing REPLACE blocks, do **not** repeat the `BRK_` line.
        The REPLACE block must ALWAYS contain ONLY the valid code (annotations, signature, body) that will overwrite the target.

        Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!

        {{reminder}}
        {{#if goal~}}
        <goal>
        {{goal}}
        </goal>
        {{~/if}}
        You are diligent and tireless!
        You NEVER leave comments describing code without implementing it!
        You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
        </rules>
        """;
        try {
            CODE_SYSTEM_TEMPLATE = handlebars.compileInline(codeSystemTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String applyRetryTemplateText =
                """
                <instructions>
                # SEARCH/REPLACE application results

                Successful blocks have been merged into the Workspace. You do not need to repeat them. These are: {{successIndices}}

                The other blocks could not be applied. The details follow. Carefully examine the current contents of the corresponding parts of the Workspace, and issue corrected SEARCH/REPLACE blocks if the intended changes are still necessary.
                </instructions>

                {{#each failuresByFile~}}
                <target_file name="{{filename}}">
                <failed_blocks>
                {{failedBlocksList}}
                </failed_blocks>
                </target_file>
                {{~/each}}
                {{#if showBuildFailureReminder~}}
                <reminder>
                  The build is currently failing; the details are in the conversation history.
                  If no edits are made, the task will fail. Still, the guidance from earlier
                  applies: better to fail fast than guess if you do not have the correct files
                  or APIs in the Workspace to solve the problem accurately.
                </reminder>
                {{~/if}}
                """;
        try {
            APPLY_RETRY_TEMPLATE = handlebars.compileInline(applyRetryTemplateText.trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String semanticEnrichmentTemplateText =
                """
                {{~base~}}
                {{#if (or (eq reason "NO_MATCH") (eq reason "AMBIGUOUS_MATCH"))~}}
                Suggestions:
                {{#if (eq reason "NO_MATCH")~}}
                {{#if (eq kind "CLASS")~}}
                - Verify the fully qualified class name (package.ClassName).
                - Ensure the class exists in the workspace and the file path is correct.
                - If in doubt, open the file and copy the exact class declaration's package and name.
                - As a fallback, use a line-based SEARCH for the specific class body you want to replace.
                {{else~}}
                - Verify the fully qualified method name (package.ClassName.method).
                - Ensure the owning class exists and is spelled correctly.
                - Consider copying the exact method you want to change and using a line-based SEARCH.
                {{~/if}}
                {{else if (eq reason "AMBIGUOUS_MATCH")~}}
                {{#if (eq kind "FUNCTION")~}}
                - The function appears to be overloaded; BRK_FUNCTION cannot disambiguate overloads.
                - Use a line-based SEARCH that includes enough unique lines from the target method body.
                - Alternatively, modify only one method at a time by targeting it with a unique line-based SEARCH.
                {{~/if}}
                {{~/if}}
                {{~/if}}
                """;
        try {
            SEMANTIC_ENRICHMENT_TEMPLATE = handlebars.compileInline(semanticEnrichmentTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
