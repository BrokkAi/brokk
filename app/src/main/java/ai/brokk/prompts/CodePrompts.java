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
import ai.brokk.context.ViewingPolicy;
import ai.brokk.util.StyleGuideResolver;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks. */
public abstract class CodePrompts {
    private static final Logger logger = LogManager.getLogger(CodePrompts.class);
    public static final CodePrompts instance = new CodePrompts() {}; // Changed instance creation
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

    /** Formats the most recent build error for the LLM retry prompt. */
    public static String buildFeedbackPrompt(Context context) {
        var cf = context.getBuildFragment().orElseThrow();
        return """
                The build failed with the error visible in the Workspace. Please refer to
                fragment id %s, "%s".

                Please analyze the error message, review the conversation history for previous attempts, and provide SEARCH/REPLACE blocks to fix all the errors and warnings.

                IMPORTANT: If you determine that the build errors are not improving or are going in circles after reviewing the history,
                do your best to explain the problem but DO NOT provide any edits.
                Otherwise, provide the edits as usual.
                """
                .formatted(cf.id(), cf.description());
    }

    public static Set<InstructionsFlags> instructionsFlags(Context ctx) {
        return instructionsFlags(
                ctx.getEditableFragments().flatMap(f -> f.files().stream()).collect(Collectors.toSet()));
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
        return baseReminder + "\n" + MARKDOWN_REMINDER;
    }

    public String architectReminder() {
        return ARCHITECT_REMINDER + "\n" + MARKDOWN_REMINDER;
    }

    public String askReminder() {
        return MARKDOWN_REMINDER;
    }

    /**
     * Redacts SEARCH/REPLACE blocks from an AiMessage. If the message contains S/R blocks, they are replaced with
     * "[elided SEARCH/REPLACE block]". If the message does not contain S/R blocks, or if the redacted text is blank,
     * Optional.empty() is returned.
     *
     * @param aiMessage The AiMessage to process.
     * @param parser The EditBlockParser to use for parsing.
     * @return An Optional containing the redacted AiMessage, or Optional.empty() if no message should be added.
     */
    public static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {
        // Pass an empty set for trackedFiles as it's not needed for redaction.
        var parsedResult = parser.parse(aiMessage.text(), Collections.emptySet());
        // Check if there are actual S/R block objects, not just text parts
        boolean hasSrBlocks = parsedResult.blocks().stream().anyMatch(b -> b.block() != null);

        if (!hasSrBlocks) {
            // No S/R blocks, return message as is (if not blank)
            return aiMessage.text().isBlank() ? Optional.empty() : Optional.of(aiMessage);
        } else {
            // Contains S/R blocks, needs redaction
            var blocks = parsedResult.blocks();
            var sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                var ob = blocks.get(i);
                if (ob.block() == null) { // Plain text part
                    sb.append(ob.text());
                } else { // An S/R block
                    sb.append("[elided SEARCH/REPLACE block]");
                    // If the next output block is also an S/R block, add a newline
                    if (i + 1 < blocks.size() && blocks.get(i + 1).block() != null) {
                        sb.append('\n');
                    }
                }
            }
            String redactedText = sb.toString();
            return redactedText.isBlank() ? Optional.empty() : Optional.of(new AiMessage(redactedText));
        }
    }

    public final List<ChatMessage> collectCodeMessages(
            StreamingChatModel model,
            Context ctx,
            List<ChatMessage> prologue,
            List<ChatMessage> taskMessages,
            UserMessage request,
            Set<ProjectFile> changedFiles,
            ViewingPolicy viewingPolicy)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var messages = new ArrayList<ChatMessage>();
        var reminder = codeReminder(cm.getService(), model);

        messages.add(systemMessage(ctx, reminder));

        // Read-only + untouched-editable message (CodeAgent wants summaries combined; changedFiles controls untouched)
        var codeAgentWorkspace =
                WorkspacePrompts.getMessagesForCodeAgent(ctx, viewingPolicy, changedFiles);
        messages.addAll(codeAgentWorkspace.readOnlyPlusUntouched());

        messages.addAll(prologue);

        messages.addAll(getHistoryMessages(ctx));
        messages.addAll(taskMessages);
        if (!changedFiles.isEmpty()) {
            // Changed editable + build status
            messages.addAll(codeAgentWorkspace.editableChanged());
        }
        messages.add(request);

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
        messages.addAll(
                WorkspacePrompts.getMessagesInAddedOrder(cm.liveContext(), viewingPolicy));
        messages.addAll(getHistoryMessages(cm.liveContext()));
        messages.add(askRequest(input));

        return messages;
    }

    protected SystemMessage systemMessage(Context ctx, String reminder) {
        var workspaceSummary = WorkspacePrompts.formatGroupedToc(ctx);

        // Collect project-backed files from current context (nearest-first resolution uses parent dirs).
        var projectFiles =
                ctx.fileFragments().flatMap(cf -> cf.files().stream()).toList();

        // Resolve composite style guide from AGENTS.md files nearest to current context files; fall back to project
        // root guide.
        var resolvedGuide = StyleGuideResolver.resolve(projectFiles);
        var styleGuide = resolvedGuide.isBlank() ? ctx.getContextManager().getProject().getStyleGuide() : resolvedGuide;

        var text =
                """
          <instructions>
          %s
          </instructions>
          <workspace-toc>
          %s
          </workspace-toc>
          <style_guide>
          %s
          </style_guide>
          """
                        .formatted(systemIntro(reminder), workspaceSummary, styleGuide)
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
           You can create new files without asking!
           But if you need to propose changes to code you can't see,
           you *MUST* tell the user their full filename names and ask them to *add the files to the chat*;
           end your reply and wait for their approval.
           But if you only need to change individual functions whose code you can see,
           you may do so without having the entire file in the Workspace.

        2. Explain the needed changes in a few short sentences.

        3. Give each change as a *SEARCH/REPLACE* block.

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

    /** Generates a message based on parse/apply errors from failed edit blocks */
    public static String getApplyFailureMessage(List<EditBlock.FailedBlock> failedBlocks, int succeededCount) {
        if (failedBlocks.isEmpty()) {
            return "";
        }

        // Group failed blocks by filename
        var failuresByFile = failedBlocks.stream()
                .filter(fb -> fb.block().rawFileName() != null) // Only include blocks with filenames
                .collect(Collectors.groupingBy(fb -> fb.block().rawFileName()));

        int totalFailCount = failedBlocks.size();
        boolean singularFail = (totalFailCount == 1);
        var pluralizeFail = singularFail ? "" : "s";

        // Instructions for the LLM
        String instructions =
                """
                      <instructions>
                      # %d SEARCH/REPLACE block%s failed to match in %d files!

                      Take a look at the CURRENT state of the relevant file%s provided above in the editable Workspace.
                      If the failed edits listed in the `<failed_blocks>` tags are still needed, please correct them based on the current content.
                      Remember that SEARCH/REPLACE ignores leading and trailing whitespace, so look for material, non-whitespace mismatches.
                      If the SEARCH text looks correct, double-check the filename too.

                      Provide corrected SEARCH/REPLACE blocks for the failed edits only.
                      </instructions>
                      """
                        .formatted(totalFailCount, pluralizeFail, failuresByFile.size(), pluralizeFail);

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
                                       %s
                                       </block>
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

        // Add info about successful blocks, if any
        String successNote = "";
        if (succeededCount > 0) {
            boolean singularSuccess = (succeededCount == 1);
            var pluralizeSuccess = singularSuccess ? "" : "s";
            successNote =
                    """
                          <note>
                          The other %d SEARCH/REPLACE block%s applied successfully. Do not re-send them. Just fix the failing blocks detailed above.
                          </note>
                          """
                            .formatted(succeededCount, pluralizeSuccess);
        }

        // Construct the full message for the LLM
        return """
               %s

               %s
               %s
               """
                .formatted(instructions, fileDetails, successNote);
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
                    redactAiMessage(aiMessage, parser).ifPresent(processedMessages::add);
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
        var searchContents =
                """
        4. One of the following SEARCH types:
          - Line-based SEARCH: a contiguous chunk of the EXACT lines to search for in the existing source code,
          - Full-file SEARCH: a single line `BRK_ENTIRE_FILE` indicating replace-the-entire-file (or create-new-file)
        """;
        var hints = """
        - Use full-file SEARCH when you are changing over half of the file.
        """;

        if (flags.contains(InstructionsFlags.SYNTAX_AWARE)) {
            searchContents +=
                    """
            - Syntax-aware SEARCH: a single line consisting of BRK_CLASS or BRK_FUNCTION, followed by the FULLY QUALIFIED class or function name:
              `BRK_[CLASS|FUNCTION] $fqname`. This applies to any named class-like (struct, record, interface, etc)
              or function-like (method, static method) entity, but NOT anonymous ones. `BRK_FUNCTION` replaces an
              EXISTING function's signature, annotations, and body, including any Javadoc; it CANNOT create new functions
              without an existing one to replace.
              **IMPORTANT**: The `BRK_` token is NOT part of the file content, it is an entity locator used only in SEARCH.
              When writing the REPLACE block, do **not** repeat the `BRK_` line.
              The REPLACE block must contain *only* the valid code (annotations, signature, body) that will overwrite the target.
            """;
            hints = "- Use syntax-aware SEARCH when you are replacing an entire class or function.\n" + hints;
        }
        if (flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS)) {
            searchContents +=
                    """
            - Conflict SEARCH: a single line consisting of the conflict marker ID: `BRK_CONFLICT_$n`
              where $n is the conflict number.""";
            hints = "- ALWAYS use conflict SEARCH when you are fixing conflicts.\n" + hints;
        }
        hints +=
                """
        - Line-based SEARCH is jack of all trades, master of none. Accuracy degrades as the number of lines grows.
          Use when none of the more specialized and more efficient options is a good fit.
          Include just the changing lines, plus a few surrounding lines if needed for uniqueness.
          You should not need to cite an entire large block to change a line or two.
        """;

        var examples = EditBlockExamples.buildExamples(flags);

        var intro = flags.isEmpty()
                ? ""
                : "The *SEARCH/REPLACE* engine has been upgraded and supports more powerful features than simple line-based edits; pay close attention to the instructions. ";

        var brkRestriction = flags.contains(InstructionsFlags.SYNTAX_AWARE)
                ? "Do not generate more than one BRK_CLASS or BRK_FUNCTION edit for the same fully qualified symbol in a single response;\ncombine all changes for that symbol into a single block.\n\n"
                : "";

        return """
        <rules>
        # EXTENDED *SEARCH/REPLACE block* Rules:
        
        %sEvery *SEARCH/REPLACE block* must use this format:
        1. The opening fence: ```
        2. The *FULL* file path alone on a line, verbatim. No comment tokens, no bold asterisks, no quotes, no escaping of characters, etc.
        3. The start of search block: <<<<<<< SEARCH
        %s
        5. The dividing line: =======
        6. The lines to replace into the source code
        7. The end of the replace block: >>>>>>> REPLACE
        8. The closing fence: ```
        
        Points to remember:
        - Use the *FULL* file path, as shown to you by the user. No other text should appear on the marker lines.
        %s
        
        ## Examples (format only; illustrative, not real code)
        Follow these patterns exactly when you emit edits.
        %s
        
        *SEARCH/REPLACE* blocks will *fail* to apply if the SEARCH payload matches multiple occurrences in the content.
        For line-based edits, this means you must include enough lines to uniquely match each set of lines that need to change,
        and avoid using syntax-aware edits for overloaded functions.
        
        Keep *SEARCH/REPLACE* blocks concise.
        Break large changes into a series of smaller blocks that each change a small portion.
        
        Avoid generating overlapping *SEARCH/REPLACE* blocks, combine them into a single edit.
        %sIf you want to move code within a filename, use 2 blocks: one to delete from the old location,
        and one to insert in the new location.
        
        Pay attention to which filenames the user wants you to edit, especially if they are asking
        you to create a new filename.
        
        If the user just says something like "ok" or "go ahead" or "do that", they probably want you
        to make SEARCH/REPLACE blocks for the code changes you just proposed.
        The user will say when they've applied your edits.
        If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.
        
        NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
        use vanilla ascii single and double quotes.
        
        # General
        Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.
        
        Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!
        
        %s
        </rules>
        
        <goal>
        %s
        </goal>
"""
                .formatted(intro, searchContents, hints, examples, brkRestriction, reminder, input);
    }
}
