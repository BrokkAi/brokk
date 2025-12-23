package ai.brokk.prompts;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AbstractService;
import ai.brokk.EditBlock;
import ai.brokk.IContextManager;
import ai.brokk.SyntaxAwareConfig;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ViewingPolicy;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Messages;
import ai.brokk.util.StyleGuideResolver;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/** Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks. */
public abstract class CodePrompts {
    private static final Logger logger = LogManager.getLogger(CodePrompts.class);
    public static final CodePrompts instance = new CodePrompts() {};
    private static final Pattern BRK_MARKER_PATTERN =
            Pattern.compile("^BRK_(CLASS|FUNCTION)\\s+(.+)$", Pattern.MULTILINE);

    public static final String LAZY_REMINDER =
            """
                    You are diligent and tireless!
                    You NEVER leave comments describing code without implementing it!
                    You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
                    """;

    public static final String OVEREAGER_REMINDER =
            """
                    Avoid changing code or comments that are not directly related to the request.

                    Do not comment on your modifications, only on the resulting code in isolation.
                    You must never output any comments about the progress or type of changes of your refactoring or generation.
                    For example, you must NOT add comments like: 'Added dependency' or 'Changed to new style' or worst of all 'Keeping existing implementation'.
                    """;

    public static final String ARCHITECT_REMINDER =
            """
                    Pay careful attention to the scope of the user's request. Attempt to do everything required
                    to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
                    For example, if the user asks you a question, you should do your best to answer his question first,
                    before immediately jumping into taking further action.
                    """;

    public static final String MARKDOWN_REMINDER =
            """
            <persistence>
            ## Markdown Formatting
            When not writing SEARCH/REPLACE blocks,
            format your response using GFM Markdown to **improve the readability** of your responses with:
            - **bold**
            - _italics_
            - `inline code` (for file, directory, function, class names, and other symbols)
            - ```code fences``` for code and pseudocode
            - list
            - prefer GFM tables over bulleted lists
            - header tags (start from ##).
            </persistence>
            """;

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

    public String codeReminder(AbstractService service, StreamingChatModel model) {
        var baseReminder = service.isLazy(model) ? LAZY_REMINDER : OVEREAGER_REMINDER;
        return """
                %s
                %s
                """.formatted(baseReminder, MARKDOWN_REMINDER);
    }

    public String architectReminder() {
        return ARCHITECT_REMINDER + "\n" + MARKDOWN_REMINDER;
    }

    public String askReminder() {
        return MARKDOWN_REMINDER;
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
    public static Optional<AiMessage> redactAiMessage(AiMessage aiMessage) {
        var parsedResult = EditBlockParser.instance.parse(aiMessage.text(), Collections.emptySet());
        boolean hasSrBlocks = parsedResult.blocks().stream().anyMatch(b -> b.block() != null);

        if (!hasSrBlocks) {
            return aiMessage.text().isBlank() ? Optional.empty() : Optional.of(aiMessage);
        }

        var blocks = parsedResult.blocks();
        var sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            var ob = blocks.get(i);
            if (ob.block() == null) {
                sb.append(ob.text());
            } else {
                sb.append(ELIDED_BLOCK_PLACEHOLDER);
                if (i + 1 < blocks.size() && blocks.get(i + 1).block() != null) {
                    sb.append('\n');
                }
            }
        }
        String redactedText = sb.toString();
        return redactedText.isBlank() ? Optional.empty() : Optional.of(new AiMessage(redactedText));
    }

    public final List<ChatMessage> collectCodeMessages(
            StreamingChatModel model,
            Context ctx,
            List<ChatMessage> prologue,
            List<ChatMessage> taskMessages,
            UserMessage request,
            ViewingPolicy viewingPolicy,
            String goal,
            boolean includeBuildStatus) {
        var cm = ctx.getContextManager();
        var messages = new ArrayList<ChatMessage>();
        var reminder = codeReminder(cm.getService(), model);
        var codeAgentWorkspace = WorkspacePrompts.getMessagesForCodeAgent(ctx, viewingPolicy, includeBuildStatus);

        // Use goal-aware system message
        messages.add(systemMessage(ctx, reminder, goal));
        messages.addAll(getHistoryMessages(ctx));
        messages.addAll(prologue);
        messages.addAll(codeAgentWorkspace.workspace());
        messages.addAll(taskMessages);

        // Append TOC reminder to the request
        var tocReminder =
                """

                Reminder: here is a list of the full contents of the Workspace that you can refer to above:
                %s
                """
                        .formatted(WorkspacePrompts.formatToc(ctx, includeBuildStatus));
        var augmentedRequest = new UserMessage(Messages.getText(request) + tocReminder);
        messages.add(augmentedRequest);

        return messages;
    }

    public final List<ChatMessage> getSingleFileAskMessages(
            IContextManager cm, ProjectFile file, List<ChatMessage> readOnlyMessages, String question) {
        var messages = new ArrayList<ChatMessage>();

        var systemPrompt =
                """
                        <instructions>
                        %s
                        </instructions>
                        <style_guide>
                        %s
                        </style_guide>
                        """
                        .stripIndent()
                        .formatted(systemIntro(""), cm.getProject().getStyleGuide())
                        .trim();
        messages.add(new SystemMessage(systemPrompt));

        messages.addAll(readOnlyMessages);

        String fileContent =
                """
                        <file path="%s">
                        %s
                        </file>
                        """
                        .stripIndent()
                        .formatted(file.toString(), file.read().orElseThrow());
        messages.add(new UserMessage(fileContent));
        messages.add(new AiMessage("Thank you for the file."));

        messages.add(askRequest(question));

        return messages;
    }

    /**
     * Collects chat messages for an "ask" request, using the ASK viewing policy.
     * <p>
     * This method no longer takes a {@code model} parameter. Instead, it sets the viewing policy
     * to {@code ViewingPolicy(TaskResult.Type.ASK)}, which determines what workspace contents are shown.
     *
     * @param cm    The context manager for the current project/session.
     * @param input The user's question or request.
     * @return A list of chat messages representing the system prompt, workspace contents, history, and the user's request.
     * @throws InterruptedException if interrupted while collecting messages.
     */
    public final List<ChatMessage> collectAskMessages(IContextManager cm, String input) throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        var viewingPolicy = new ViewingPolicy(TaskResult.Type.ASK);
        String reminder = askReminder();
        messages.add(systemMessage(cm.liveContext(), reminder));
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(cm.liveContext(), viewingPolicy));
        messages.addAll(getHistoryMessages(cm.liveContext()));
        messages.add(askRequest(input));

        return messages;
    }

    // New goal-aware overload. If goal is non-blank, append a <goal>...</goal> block after <style_guide>.
    public SystemMessage systemMessage(Context ctx, String reminder, @Nullable String goal) {
        var styleGuide = StyleGuideResolver.resolve(ctx, ctx.getContextManager().getProject());

        final String text;
        if (goal == null || goal.isBlank()) {
            text =
                    """
                            <instructions>
                            %s
                            </instructions>
                            <style_guide>
                            %s
                            </style_guide>
                            """
                            .formatted(systemIntro(reminder), styleGuide)
                            .trim();
        } else {
            text =
                    """
                            <instructions>
                            %s
                            </instructions>
                            <style_guide>
                            %s
                            </style_guide>
                            <goal>
                            %s
                            </goal>
                            """
                            .formatted(systemIntro(reminder), styleGuide, goal)
                            .trim();
        }

        return new SystemMessage(text);
    }

    @Blocking
    protected SystemMessage systemMessage(IContextManager cm, Context ctx, String reminder) {
        // Collect project-backed files from current context (nearest-first resolution uses parent dirs).
        var projectFiles =
                ctx.fileFragments().flatMap(cf -> cf.files().join().stream()).toList();

        // Resolve composite style guide from AGENTS.md files nearest to current context files.
        var styleGuide = StyleGuideResolver.resolve(projectFiles, cm.getProject());

        var text =
                """
                        <instructions>
                        %s
                        </instructions>
                        <style_guide>
                        %s
                        </style_guide>
                        """
                        .formatted(systemIntro(reminder), styleGuide)
                        .stripIndent()
                        .trim();

        return new SystemMessage(text);
    }

    // Backwards-compatible helper kept for existing call sites that don't supply a goal.
    public SystemMessage systemMessage(Context ctx, String reminder) {
        return systemMessage(ctx, reminder, null);
    }

    @Blocking
    protected SystemMessage systemMessage(IContextManager cm, String reminder) {
        // Resolve composite style guide from AGENTS.md files nearest to files in the top context.
        var projectFiles = cm.liveContext()
                .fileFragments()
                .flatMap(cf -> cf.files().join().stream())
                .collect(Collectors.toList());

        var styleGuide = StyleGuideResolver.resolve(projectFiles, cm.getProject());

        var text =
                """
                        <instructions>
                        %s
                        </instructions>
                        <style_guide>
                        %s
                        </style_guide>
                        """
                        .formatted(systemIntro(reminder), styleGuide)
                        .stripIndent()
                        .trim();

        return new SystemMessage(text);
    }

    public String systemIntro(String reminder) {
        return """
                Act as an expert software developer.
                Always use best practices when coding.
                Respect and use existing conventions, libraries, etc. that are already present in the code base.

                %s
                """
                .formatted(reminder);
    }

    public UserMessage codeRequest(Context ctx, String input, String reminder) {
        var instructions =
                """
                        <instructions>
                        Think about this request for changes to the supplied code.
                        If the request is ambiguous, %s.

                        Once you understand the request you MUST:

                        1. Decide if you need to propose *SEARCH/REPLACE* edits for any code whose source is not available.
                           1a. You can create new files without asking!
                           1b. If you only need to change individual functions whose code you CAN see,
                               you may do so without having the entire file in the Workspace.
                           1c. Ask for additional files if having them would enable a cleaner solution,
                               even if you could hack around it without them.
                               For example,
                               - If a field or method is private and you would need reflection to access it,
                                 ask for the file so you can relax the visibility instead.
                               - If you could preserve DRY by editing a data structure or a function instead of substantially duplicating
                                 its functionality.
                           If you need to propose changes to code you can't see,
                           tell the user their full class or file names and ask them to *add them to the Workspace*;
                           end your reply and wait for their approval.

                        3. Explain the needed changes in a few short sentences.

                        4. Give each change as a *SEARCH/REPLACE* block.

                        All changes to files must use this *SEARCH/REPLACE* block format.

                        If a file is read-only or unavailable, ask the user to add it or make it editable.

                        If you are struggling to use a dependency or API correctly, you MUST stop and ask the user for help.
                        """
                        .formatted(
                                GraphicsEnvironment.isHeadless()
                                        ? "decide what the most logical interpretation is"
                                        : "ask questions");
        return new UserMessage(instructions + instructions(input, instructionsFlags(ctx), reminder));
    }

    public UserMessage askRequest(String input) {
        var text =
                """
                        <instructions>
                        Answer this question about the supplied code thoroughly and accurately.

                        Provide insights, explanations, and analysis; do not implement changes.
                        While you can suggest high-level approaches and architectural improvements, remember that:
                        - You should focus on understanding and clarifying the code
                        - The user will make other requests when he wants to actually implement changes
                        - You are being asked here for conceptual understanding and problem diagnosis

                        Be concise but complete in your explanations. If you need more information to answer a question,
                        don't hesitate to ask for clarification. If you notice references to code in the Workspace that
                        you need to see to answer accurately, do your best to take educated guesses but clarify that
                        it IS an educated guess and ask the user to add the relevant code.

                        Format your answer with Markdown for readability. It's particularly important to signal
                        changes in subject with appropriate headings.
                        </instructions>

                        <question>
                        %s
                        </question>
                        """
                        .formatted(input);
        return new UserMessage(text);
    }

    /** Generates a message based on application results of tagged edit blocks. */
    public static String getApplyFailureMessage(List<EditBlock.FailedBlock> failedBlocks, int succeededCount) {
        if (failedBlocks.isEmpty()) {
            return "";
        }

        // We assume the failedBlocks provided are a subset of the total blocks sent,
        // but since we don't have the original total list here, we rely on the commentary
        // or caller logic to identify which block index failed.
        // For now, we will use the indices provided within the FailedBlock if available,
        // or just list the failures.
        
        var sb = new StringBuilder();
        sb.append("<instructions>\n");
        sb.append("# SEARCH/REPLACE application results\n\n");
        sb.append("Some blocks failed to apply. Please review the current file state and provide corrected blocks.\n\n");
        sb.append("</instructions>\n\n");

        var failuresByFile = failedBlocks.stream()
                .filter(fb -> fb.block().rawFileName() != null)
                .collect(Collectors.groupingBy(fb -> fb.block().rawFileName()));

        String fileDetails = failuresByFile.entrySet().stream()
                .map(entry -> {
                    var filename = entry.getKey();
                    var fileFailures = entry.getValue();

                    String failedBlocksXml = fileFailures.stream()
                            .map(f -> {
                                var enriched = enrichSemanticCommentary(f);
                                var commentaryText = enriched.isBlank()
                                        ? ""
                                        : """
                                        <commentary>
                                        %s
                                        </commentary>
                                        """
                                                .formatted(enriched);
                                return """
                                        <failed_block reason="%s">
                                        <block>
                                        %s
                                        </block>
                                        %s
                                        </failed_block>
                                        """
                                        .formatted(f.reason(), f.block().repr(), commentaryText);
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                            <target_file name="%s">
                            <failed_blocks>
                            %s
                            </failed_blocks>
                            </target_file>
                            """
                            .formatted(filename, failedBlocksXml)
                            .stripIndent();
                })
                .collect(Collectors.joining("\n\n"));

        sb.append(fileDetails);

        return sb.toString().trim();
    }

    /**
     * Enrich commentary for semantic-aware failures (BRK_CLASS / BRK_FUNCTION).
     * Preserves existing analyzer commentary (which may already include "Did you mean ..." suggestions),
     * and appends actionable guidance depending on failure reason and marker type.
     */
    private static String enrichSemanticCommentary(EditBlock.FailedBlock f) {
        var base = f.commentary().trim();

        // Try to detect semantic markers in the original SEARCH block
        var before = f.block().beforeText().strip();
        var m = BRK_MARKER_PATTERN.matcher(before);
        if (!m.find()) {
            // Not a semantic marker; return original commentary
            return base;
        }

        var kind = m.group(1); // "CLASS" or "FUNCTION"

        var hints = new ArrayList<String>();

        switch (f.reason()) {
            case NO_MATCH -> {
                if ("CLASS".equals(kind)) {
                    hints.add("- Verify the fully qualified class name (package.ClassName).");
                    hints.add("- Ensure the class exists in the workspace and the file path is correct.");
                    hints.add("- If in doubt, open the file and copy the exact class declaration's package and name.");
                    hints.add(
                            "- As a fallback, use a line-based SEARCH for the specific class body you want to replace.");
                } else { // FUNCTION
                    hints.add("- Verify the fully qualified method name (package.ClassName.method).");
                    hints.add("- Ensure the owning class exists and is spelled correctly.");
                    hints.add("- Consider copying the exact method you want to change and using a line-based SEARCH.");
                }
            }
            case AMBIGUOUS_MATCH -> {
                if ("FUNCTION".equals(kind)) {
                    hints.add("- The function appears to be overloaded; BRK_FUNCTION cannot disambiguate overloads.");
                    hints.add(
                            "- Use a line-based SEARCH that includes enough unique lines from the target method body.");
                    hints.add(
                            "- Alternatively, modify only one method at a time by targeting it with a unique line-based SEARCH.");
                }
                // For BRK_CLASS ambiguity we don't add extra guidance (not a typical case).
            }
            default -> {
                // No extra guidance for other reasons
            }
        }

        if (hints.isEmpty()) {
            return base;
        }

        var guidance = ("Suggestions:\n" + String.join("\n", hints)).trim();
        if (base.isBlank()) {
            return guidance;
        }
        // Append guidance after existing commentary
        return (base + (base.endsWith("\n") ? "" : "\n") + guidance).trim();
    }

    /**
     * Returns messages containing only the read-only workspace content (files, virtual fragments, etc.). Does not
     * include editable content or related classes.
     *
     * @param ctx              The context to process.
     * @param combineSummaries If true, coalesce multiple SummaryFragments into a single combined block.
     * @param vp               The viewing policy to apply for content visibility; defaults to NONE if not specified.
     * @return A collection of ChatMessages (empty if no content).
     */
    @Blocking
    public final Collection<ChatMessage> getWorkspaceReadOnlyMessages(
            Context ctx, boolean combineSummaries, ViewingPolicy vp) {
        return getWorkspaceReadOnlyMessagesInternal(ctx, combineSummaries, vp);
    }

    /**
     * Internal implementation of getWorkspaceReadOnlyMessages that applies the viewing policy.
     */
    @Blocking
    private Collection<ChatMessage> getWorkspaceReadOnlyMessagesInternal(
            Context ctx, boolean combineSummaries, ViewingPolicy vp) {
        // --- Partition Read-Only Fragments ---
        var readOnlyFragments = ctx.getReadonlyFragments().toList();
        var summaryFragments = combineSummaries
                ? readOnlyFragments.stream()
                        .filter(ContextFragments.SummaryFragment.class::isInstance)
                        .map(ContextFragments.SummaryFragment.class::cast)
                        .toList()
                : List.<ContextFragments.SummaryFragment>of();
        var otherFragments = combineSummaries
                ? readOnlyFragments.stream()
                        .filter(f -> !(f instanceof ContextFragments.SummaryFragment))
                        .toList()
                : readOnlyFragments;

        // --- Format non-summary fragments using the policy ---
        var rendered = formatWithPolicy(otherFragments, vp);
        var combinedText = new StringBuilder(rendered.text);

        // --- Append summary fragments if present ---
        if (!summaryFragments.isEmpty()) {
            var summaryText = ContextFragments.SummaryFragment.combinedText(summaryFragments);
            var combinedBlock =
                    """
                            <api_summaries fragmentid="api_summaries">
                            %s
                            </api_summaries>
                            """
                            .formatted(summaryText);
            if (!rendered.text.isEmpty()) {
                combinedText.append("\n\n");
            }
            combinedText.append(combinedBlock).append("\n\n");
        }

        // --- Return early if nothing to show ---
        if (combinedText.isEmpty() && rendered.images.isEmpty()) {
            return List.of();
        }

        // --- Compose final workspace_readonly message ---
        String readOnlyText =
                """
                        <workspace_readonly>
                        Here are the READ ONLY files and code fragments in your Workspace.
                        Do not edit this code! Images will be included separately if present.

                        %s
                        </workspace_readonly>
                        """
                        .formatted(combinedText.toString().trim());

        var allContents = new ArrayList<Content>();
        allContents.add(new TextContent(readOnlyText));
        allContents.addAll(rendered.images);

        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(readOnlyUserMessage, new AiMessage("Thank you for the read-only context."));
    }

    /**
     * Returns messages containing only the editable workspace content. Does not include read-only content or related
     * classes.
     */
    @Blocking
    public final Collection<ChatMessage> getWorkspaceEditableMessages(Context ctx) {
        // --- Process Editable Fragments ---
        var editableTextFragments = new StringBuilder();
        ctx.getEditableFragments().forEach(fragment -> {
            String formatted = fragment.format().join(); // format() on live fragment
            if (!formatted.isBlank()) {
                editableTextFragments.append(formatted).append("\n\n");
            }
        });

        if (editableTextFragments.isEmpty()) {
            return List.of();
        }

        String editableText =
                """
                        <workspace_editable>
                        Here are the EDITABLE files and code fragments in your Workspace.
                        This is *the only context in the Workspace to which you should make changes*.

                        *Trust this message as the true contents of these files!*
                        Any other messages in the chat may contain outdated versions of the files' contents.

                        %s
                        </workspace_editable>
                        """
                        .formatted(editableTextFragments.toString().trim());

        var editableUserMessage = new UserMessage(editableText);
        return List.of(editableUserMessage, new AiMessage("Thank you for the editable context."));
    }

    /**
     * Constructs the ChatMessage(s) representing the current workspace context (read-only and editable
     * files/fragments). Handles both text and image fragments, creating a multimodal UserMessage if necessary.
     *
     * @param ctx              The context to process.
     * @param combineSummaries If true, coalesce multiple SummaryFragments into a single combined block.
     * @param vp               The viewing policy to apply for content visibility; uses default if null.
     * @return A collection containing one UserMessage (potentially multimodal) and one AiMessage acknowledgment, or
     * empty if no content.
     */
    @Blocking
    public final Collection<ChatMessage> getWorkspaceContentsMessages(
            Context ctx, boolean combineSummaries, ViewingPolicy vp) {
        var readOnlyMessages = getWorkspaceReadOnlyMessages(ctx, combineSummaries, vp);
        var editableMessages = getWorkspaceEditableMessages(ctx);

        return getWorkspaceContentsMessages(readOnlyMessages, editableMessages);
    }

    /**
     * Convenience overload for getWorkspaceContentsMessages with a viewing policy.
     *
     * @param ctx The context to process.
     * @param vp  The viewing policy to apply for content visibility.
     * @return A collection containing workspace messages with applied viewing policy.
     */
    @Blocking
    public final Collection<ChatMessage> getWorkspaceContentsMessages(Context ctx, ViewingPolicy vp) {
        return getWorkspaceContentsMessages(ctx, false, vp);
    }

    /**
     * Internal helper to render a list of fragments in the given order.
     * Converts text fragments to formatted strings and image fragments to ImageContent objects.
     * Returns both the combined text and the list of images.
     */
    private static final class RenderedContent {
        final String text;
        final List<ImageContent> images;

        RenderedContent(String text, List<ImageContent> images) {
            this.text = text;
            this.images = images;
        }
    }

    /**
     * Formats fragments according to a viewing policy, rendering text and collecting images.
     * Applies ViewingPolicy to StringFragments when provided (vp != null).
     */
    @Blocking
    private RenderedContent formatWithPolicy(List<ContextFragment> fragments, @Nullable ViewingPolicy vp) {
        var textBuilder = new StringBuilder();
        var imageList = new ArrayList<ImageContent>();

        for (var fragment : fragments) {
            if (fragment.isText()) {
                String formatted;
                if (vp != null && fragment instanceof ContextFragments.StringFragment sf) {
                    var visibleText = sf.textForAgent(vp);
                    formatted =
                            """
                                    <fragment description="%s" fragmentid="%s">
                                    %s
                                    </fragment>
                                    """
                                    .formatted(sf.description().join(), sf.id(), visibleText);
                } else {
                    formatted = fragment.format().join();
                }
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                    || fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
                try {
                    var imageBytesCv = Objects.requireNonNull(fragment.imageBytes(), "Image bytes were null");
                    var l4jImage = ImageUtil.toL4JImage(ImageUtil.bytesToImage(imageBytesCv.join()));
                    imageList.add(ImageContent.from(l4jImage));
                    textBuilder.append(fragment.format().join()).append("\n\n");
                } catch (IOException | UncheckedIOException e) {
                    var description = fragment.description().join();
                    logger.error("Failed to process image fragment {} for LLM message", description, e);
                    textBuilder.append(
                            String.format("[Error processing image: %s - %s]\n\n", description, e.getMessage()));
                }
            } else {
                String formatted = fragment.format().join();
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            }
        }

        return new RenderedContent(textBuilder.toString().trim(), imageList);
    }

    private List<ChatMessage> getWorkspaceContentsMessages(
            Collection<ChatMessage> readOnlyMessages, Collection<ChatMessage> editableMessages) {
        // If both are empty and no related classes requested, return empty
        if (readOnlyMessages.isEmpty() && editableMessages.isEmpty()) {
            return List.of();
        }

        var allContents = new ArrayList<Content>();
        var combinedText = new StringBuilder();

        // Extract text and image content from read-only messages
        if (!readOnlyMessages.isEmpty()) {
            var readOnlyUserMessage = readOnlyMessages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .findFirst();
            if (readOnlyUserMessage.isPresent()) {
                var contents = readOnlyUserMessage.get().contents();
                for (var content : contents) {
                    if (content instanceof TextContent textContent) {
                        combinedText.append(textContent.text()).append("\n\n");
                    } else if (content instanceof ImageContent imageContent) {
                        allContents.add(imageContent);
                    }
                }
            }
        }

        // Extract text from editable messages
        if (!editableMessages.isEmpty()) {
            var editableUserMessage = editableMessages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .findFirst();
            if (editableUserMessage.isPresent()) {
                var contents = editableUserMessage.get().contents();
                for (var content : contents) {
                    if (content instanceof TextContent textContent) {
                        combinedText.append(textContent.text()).append("\n\n");
                    }
                }
            }
        }

        // Wrap everything in workspace tags
        var workspaceText =
                """
                        <workspace>
                        %s
                        </workspace>
                        """
                        .formatted(combinedText.toString().trim());

        // Add the workspace text as the first content
        allContents.addFirst(new TextContent(workspaceText));

        // Create the main UserMessage
        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * Same as getWorkspaceMessagesInAddedOrder(Context) but applies a ViewingPolicy:
     * - Redacts special StringFragments (e.g., Task List) when policy denies visibility.
     * - Preserves insertion order and multimodal content.
     */
    @Blocking
    public final Collection<ChatMessage> getWorkspaceMessagesInAddedOrder(Context ctx, ViewingPolicy vp) {
        var allFragments = ctx.allFragments().toList();
        if (allFragments.isEmpty()) {
            return List.of();
        }

        var rendered = formatWithPolicy(allFragments, vp);
        if (rendered.text.isEmpty() && rendered.images.isEmpty()) {
            return List.of();
        }

        var allContents = new ArrayList<Content>();
        var workspaceText =
                """
                        <workspace>
                        %s
                        </workspace>
                        """
                        .formatted(rendered.text);

        allContents.add(new TextContent(workspaceText));
        allContents.addAll(rendered.images);

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    public List<ChatMessage> getHistoryMessages(Context ctx) {
        var taskHistory = ctx.getTaskHistory();
        var messages = new ArrayList<ChatMessage>();
        EditBlockParser parser = EditBlockParser.instance;

        // Merge compressed messages into a single taskhistory message
        var compressed = taskHistory.stream()
                .filter(TaskEntry::isCompressed)
                .map(TaskEntry::toString) // This will use raw messages if TaskEntry was created with them
                .collect(Collectors.joining("\n\n"));
        if (!compressed.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(compressed)));
            messages.add(new AiMessage("Ok, I see the history."));
        }

        // Uncompressed messages: process for S/R block redaction
        taskHistory.stream().filter(e -> !e.isCompressed()).forEach(e -> {
            var entryRawMessages = castNonNull(e.log()).messages();
            // Determine the messages to include from the entry
            var relevantEntryMessages = entryRawMessages.getLast() instanceof AiMessage
                    ? entryRawMessages
                    : entryRawMessages.subList(0, entryRawMessages.size() - 1);

            List<ChatMessage> processedMessages = new ArrayList<>();
            for (var chatMessage : relevantEntryMessages) {
                if (chatMessage instanceof AiMessage aiMessage) {
                    redactAiMessage(aiMessage).ifPresent(processedMessages::add);
                } else {
                    // Not an AiMessage (e.g., UserMessage, CustomMessage), add as is
                    processedMessages.add(chatMessage);
                }
            }
            messages.addAll(processedMessages);
        });

        return messages;
    }

    public enum InstructionsFlags {
        SYNTAX_AWARE,
        MERGE_AGENT_MARKERS
    }

    protected static String instructions(String input, Set<InstructionsFlags> flags, String reminder) {
        boolean hasSyntaxAware = flags.contains(InstructionsFlags.SYNTAX_AWARE);
        boolean hasMergeMarkers = flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS);

        var intro = flags.isEmpty()
                ? """
                Line-based SEARCH is your primary tool. Other SEARCH types exist for special cases such as whole-class replacement,
                conflict resolution, or rewriting an entire file.

                """
                : """
                The *SEARCH/REPLACE* engine supports multiple SEARCH types. Choose the most precise option that fits your edit.
                Line-based SEARCH remains the default for most changes.

                """;

        String searchContents =
                """
                        4. One of the following SEARCH types:
                          - Line-based SEARCH: a contiguous chunk of the EXACT lines to search for in the existing source code,
                        """;

        if (hasSyntaxAware) {
            searchContents +=
                    """
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
                    """;
        }
        if (hasMergeMarkers) {
            searchContents +=
                    """
                              - Conflict SEARCH: a single line consisting of the conflict marker ID: `BRK_CONFLICT_$n`
                                where $n is the conflict number.
                            """;
        }
        searchContents +=
                """
                          - Full-file SEARCH: a single line `BRK_ENTIRE_FILE` indicating replace-the-entire-file, or create-new-file
                        """;

        var hintLines = new ArrayList<String>();

        hintLines.add(
                """
                        - Line-based SEARCH is the primary option for most edits. Use it for adding, modifying, or removing localized
                          blocks of code, including new methods or inner classes in existing files. Include the changing lines plus a
                          few surrounding lines only when needed for uniqueness.
                        """);

        if (hasMergeMarkers) {
            hintLines.add(
                    """
                            - When you are fixing conflicts wrapped in BRK_CONFLICT markers, use conflict SEARCH (`BRK_CONFLICT_n`)
                              so that the entire conflict region is replaced in one block.
                            """);
        }

        if (hasSyntaxAware) {
            hintLines.add(
                    """
                            - Use syntax-aware SEARCH when you are replacing an entire class or function:
                              - `BRK_FUNCTION` for a complete, non-overloaded method (signature, annotations, body, and Javadoc).
                              - `BRK_CLASS` for the full body of a class-like declaration (without the surrounding package/imports).
                            """);
        }

        hintLines.add(
                """
                        - Use `BRK_ENTIRE_FILE` when you are creating a brand new file, or when you are intentionally rewriting most
                          of an existing file so that a whole-file replacement is clearer than multiple smaller edits.
                        """);

        String hints = String.join("\n", hintLines);

        var examples = EditBlockExamples.buildExamples(flags);
        var searchTypePriority = buildSearchTypePriority(flags);

        return """
        <rules>
        # EXTENDED *SEARCH/REPLACE block* Rules:

        %s%sEvery *SEARCH/REPLACE block* must use this format:
        1. The opening fence: ```
        2. The *FULL* file path alone on a line, verbatim. No comment tokens, no bold asterisks, no quotes, no escaping of characters, etc.
        3. The start of search block: <<<<<<< SEARCH
        %s
        5. The dividing line: =======
        6. The lines to replace into the source code
        7. The end of the replace block: >>>>>>> REPLACE
        8. The closing fence: ```

        ALWAYS use the *FULL* file path, as shown to you by the user. No other text should appear on the marker lines.

        ALWAYS base SEARCH/REPLACE blocks on the editable code in the Workspace. Excerpts of code or pseudocode
        may be given in your goal, but this is NOT a source of truth of the current files' contents.

        ## Examples (format only; illustrative, not real code)
        Follow these patterns exactly when you emit edits.
        %s

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

        If the user just says something like "ok" or "go ahead" or "do that", they probably want you
        to make SEARCH/REPLACE blocks for the code changes you just proposed.
        The user will say when they've applied your edits.
        If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.

        NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
        use vanilla ascii single and double quotes.

        When generating *SEARCH/REPLACE* blocks, choose the most precise SEARCH type that fits your change:
        %s
        **IMPORTANT**: The `BRK_` tokens are NEVER part of the file content, they are entity locators used only in SEARCH.
        When writing REPLACE blocks, do **not** repeat the `BRK_` line.
        The REPLACE block must ALWAYS contain ONLY the valid code (annotations, signature, body) that will overwrite the target.

        # General
        Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.

        Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!

        %s
        </rules>

        <goal>
        %s
        </goal>
        """
                .formatted(intro, searchTypePriority, searchContents, examples, hints, reminder, input);
    }

    private static String buildSearchTypePriority(Set<InstructionsFlags> flags) {
        var rows = new ArrayList<String>();
        int priority = 1;

        // Conflicts
        if (flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS)) {
            rows.add(
                    "| " + priority++
                            + " | `BRK_CONFLICT_n` | Resolving regions wrapped in BRK_CONFLICT markers when fixing merge conflicts |");
        }

        // Line edits
        rows.add("| " + priority++
                + " | Line-based | Default choice for localized edits and adding new code to existing files |");

        // syntax-based (same priority)
        if (flags.contains(InstructionsFlags.SYNTAX_AWARE)) {
            rows.add("| " + priority
                    + " | `BRK_FUNCTION` | Replacing a complete, non-overloaded method (signature + body) |");
            rows.add("| " + priority + " | `BRK_CLASS` | Replacing the entire body of a class-like declaration |");
            priority++;
        }

        // entire file
        rows.add("| " + priority
                + " | `BRK_ENTIRE_FILE` | Creating a new file or intentionally rewriting most of an existing file |");

        return """
        ## SEARCH Type Priority

        Use the first row whose description matches the change you need:

        | Priority | Type | When to use |
        |----------|------|-------------|
        %s

        """
                .formatted(String.join("\n", rows));
    }
}
