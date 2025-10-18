package io.github.jbellis.brokk.prompts;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.ImageUtil;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks. */
public abstract class CodePrompts {
    private static final Logger logger = LogManager.getLogger(CodePrompts.class);
    public static final CodePrompts instance = new CodePrompts() {}; // Changed instance creation

    public static final String LAZY_REMINDER =
            """
            You are diligent and tireless!
            You NEVER leave comments describing code without implementing it!
            You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
            """
                    .stripIndent();

    public static final String OVEREAGER_REMINDER =
            """
            Avoid changing code or comments that are not directly related to the request.

            Do not comment on your modifications, only on the resulting code in isolation.
            You must never output any comments about the progress or type of changes of your refactoring or generation.
            For example, you must NOT add comments like: 'Added dependency' or 'Changed to new style' or worst of all 'Keeping existing implementation'.
            """
                    .stripIndent();

    public static final String ARCHITECT_REMINDER =
            """
            Pay careful attention to the scope of the user's request. Attempt to do everything required
            to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
            For example, if the user asks you a question, you should do your best to answer his question first,
            before immediately jumping into taking further action.
            """
                    .stripIndent();

    public static final String GPT5_MARKDOWN_REMINDER =
            """
            <persistence>
            ## Markdown Formatting
            When not writing SEARCH/REPLACE blocks,
            format your response using GFM Markdown to **improve the readability** of your responses with:
            - **bold**
            - _italics_
            - `inline code` (for file, directory, function, class names and other symbols)
            - ```code fences``` for code and pseudocode
            - list
            - prefer GFM tables over bulleted lists
            - header tags (start from ##).
            </persistence>
            """
                    .stripIndent();

    /** Formats the most recent build error for the LLM retry prompt. */
    public static String buildFeedbackPrompt() {
        return """
                The build failed with the error visible in the Workspace.

                Please analyze the error message, review the conversation history for previous attempts, and provide SEARCH/REPLACE blocks to fix the error.

                IMPORTANT: If you determine that the build errors are not improving or are going in circles after reviewing the history,
                do your best to explain the problem but DO NOT provide any edits.
                Otherwise, provide the edits as usual.
                """;
    }

    public static Set<InstructionsFlags> instructionsFlags(Context ctx) {
        return instructionsFlags(
                ctx.getContextManager().getProject(),
                ctx.getEditableFragments().flatMap(f -> f.files().stream()).collect(Collectors.toSet()));
    }

    public static Set<InstructionsFlags> instructionsFlags(IProject project, Set<ProjectFile> editableFiles) {
        var flags = new HashSet<InstructionsFlags>();
        var languages = project.getAnalyzerLanguages();

        // we'll inefficiently read the files every time this method is called but at least we won't do it twice
        var fileContents = editableFiles.stream()
                .collect(Collectors.toMap(f -> f, f -> f.read().orElse("")));

        // set InstructionsFlags.SYNTAX_AWARE if all editable files' extensions are supported by one of `languages`
        var unsupported = fileContents.keySet().stream()
                .filter(f -> {
                    var ext = f.extension();
                    return ext.isEmpty()
                            || languages.stream()
                                    .noneMatch(lang -> lang.getExtensions().contains(ext));
                })
                .collect(Collectors.toSet());
        // temporarily disabled, see https://github.com/BrokkAi/brokk/issues/1250
        if (false) {
            flags.add(InstructionsFlags.SYNTAX_AWARE);
        } else {
            IContextManager.logger.debug("Syntax-unsupported files are {}", unsupported);
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

    public String codeReminder(Service service, StreamingChatModel model) {
        var baseReminder = service.isLazy(model) ? LAZY_REMINDER : OVEREAGER_REMINDER;

        var modelName = service.nameOf(model).toLowerCase(Locale.ROOT);
        if (modelName.startsWith("gpt-5")) {
            return baseReminder + "\n" + GPT5_MARKDOWN_REMINDER;
        }
        return baseReminder;
    }

    public String architectReminder(Service service, StreamingChatModel model) {
        var baseReminder = ARCHITECT_REMINDER;

        var modelName = service.nameOf(model).toLowerCase(Locale.ROOT);
        if (modelName.startsWith("gpt-5")) {
            return baseReminder + "\n" + GPT5_MARKDOWN_REMINDER;
        }
        return baseReminder;
    }

    public String askReminder(IContextManager cm, StreamingChatModel model) {
        var service = cm.getService();
        var modelName = service.nameOf(model).toLowerCase(Locale.ROOT);
        if (modelName.startsWith("gpt-5")) {
            return GPT5_MARKDOWN_REMINDER;
        }
        return "";
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
            Set<ProjectFile> changedFiles)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var messages = new ArrayList<ChatMessage>();
        var reminder = codeReminder(cm.getService(), model);

        messages.add(systemMessage(cm, ctx, reminder));
        // FIXME we're supposed to leave the unchanged files in their original position
        if (changedFiles.isEmpty()) {
            messages.addAll(getWorkspaceContentsMessages(ctx));
        } else {
            messages.addAll(getWorkspaceReadOnlyMessages(ctx));
        }
        messages.addAll(prologue);

        messages.addAll(getHistoryMessages(ctx));
        messages.addAll(taskMessages);
        if (!changedFiles.isEmpty()) {
            messages.addAll(getWorkspaceEditableMessages(ctx));
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

    public final List<ChatMessage> collectAskMessages(IContextManager cm, String input, StreamingChatModel model)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        messages.add(systemMessage(cm, askReminder(cm, model)));
        messages.addAll(getWorkspaceContentsMessages(cm.liveContext()));
        messages.addAll(getHistoryMessages(cm.topContext()));
        messages.add(askRequest(input));

        return messages;
    }

    /**
     * Generates a concise description of the workspace contents.
     *
     * @param cm The ContextManager.
     * @return A string summarizing editable files, read-only snippets, etc.
     */
    public static String formatWorkspaceToc(IContextManager cm) {
        var ctx = cm.topContext();
        var editableContents = ctx.getEditableToc();
        var readOnlyContents = ctx.getReadOnlyToc();
        var workspaceBuilder = new StringBuilder();
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("<editable-toc>\n%s\n</editable-toc>".formatted(editableContents));
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("<readonly-toc>\n%s\n</readonly-toc>".formatted(readOnlyContents));
        }
        return workspaceBuilder.toString();
    }

    public static String formatWorkspaceToc(IContextManager cm, Context ctx) {
        var editableContents = ctx.getEditableToc();
        var readOnlyContents = ctx.getReadOnlyToc();
        var workspaceBuilder = new StringBuilder();
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("<editable-toc>\n%s\n</editable-toc>".formatted(editableContents));
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("<readonly-toc>\n%s\n</readonly-toc>".formatted(readOnlyContents));
        }
        return workspaceBuilder.toString();
    }

    protected SystemMessage systemMessage(IContextManager cm, Context ctx, String reminder) {
        var workspaceSummary = formatWorkspaceToc(cm, ctx);
        var styleGuide = cm.getProject().getStyleGuide();

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
                        .stripIndent()
                        .formatted(systemIntro(reminder), workspaceSummary, styleGuide)
                        .trim();

        return new SystemMessage(text);
    }

    protected SystemMessage systemMessage(IContextManager cm, String reminder) {
        var workspaceSummary = formatWorkspaceToc(cm);
        var styleGuide = cm.getProject().getStyleGuide();

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
                        .stripIndent()
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
                .stripIndent()
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
                        .formatted(totalFailCount, pluralizeFail, failuresByFile.size(), pluralizeFail)
                        .stripIndent();

        String fileDetails = failuresByFile.entrySet().stream()
                .map(entry -> {
                    var filename = entry.getKey();
                    var fileFailures = entry.getValue();

                    String failedBlocksXml = fileFailures.stream()
                            .map(f -> {
                                var commentaryText = f.commentary().isBlank()
                                        ? ""
                                        : """
                                                       <commentary>
                                                       %s
                                                       </commentary>
                                                       """
                                                .formatted(f.commentary());
                                return """
                                       <failed_block reason="%s">
                                       <block>
                                       %s
                                       %s
                                       </block>
                                       </failed_block>
                                       """
                                        .formatted(f.reason(), f.block().repr(), commentaryText)
                                        .stripIndent();
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
                            .formatted(succeededCount, pluralizeSuccess)
                            .stripIndent();
        }

        // Construct the full message for the LLM
        return """
               %s

               %s
               %s
               """
                .formatted(instructions, fileDetails, successNote)
                .stripIndent();
    }

    /**
     * Returns messages containing only the read-only workspace content (files, virtual fragments, etc.). Does not
     * include editable content or related classes.
     */
    public final Collection<ChatMessage> getWorkspaceReadOnlyMessages(Context ctx) {
        var allContents = new ArrayList<Content>();

        // --- Process Read-Only Fragments from liveContext (Files, Virtual, AutoContext) ---
        var readOnlyTextFragments = new StringBuilder();
        var readOnlyImageFragments = new ArrayList<ImageContent>();
        ctx.getReadOnlyFragments().forEach(fragment -> {
            if (fragment.isText()) {
                // Handle text-based fragments
                String formatted = fragment.format(); // No analyzer
                if (!formatted.isBlank()) {
                    readOnlyTextFragments.append(formatted).append("\n\n");
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                    || fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
                // Handle image fragments - explicitly check for known image fragment types
                try {
                    // Convert AWT Image to LangChain4j ImageContent
                    var l4jImage = ImageUtil.toL4JImage(fragment.image());
                    readOnlyImageFragments.add(ImageContent.from(l4jImage));
                    // Add a placeholder in the text part for reference
                    readOnlyTextFragments.append(fragment.format()).append("\n\n"); // No analyzer
                } catch (IOException | UncheckedIOException e) {
                    logger.error("Failed to process image fragment {} for LLM message", fragment.description(), e);
                    // Add a placeholder indicating the error, do not call removeBadFragment from here
                    readOnlyTextFragments.append(String.format(
                            "[Error processing image: %s - %s]\n\n", fragment.description(), e.getMessage()));
                }
            } else {
                // Handle non-text, non-image fragments (e.g., HistoryFragment, TaskFragment)
                // Just add their formatted representation as text
                String formatted = fragment.format(); // No analyzer
                if (!formatted.isBlank()) {
                    readOnlyTextFragments.append(formatted).append("\n\n");
                }
            }
        });

        if (readOnlyTextFragments.isEmpty() && readOnlyImageFragments.isEmpty()) {
            return List.of();
        }

        // Add the combined text content for read-only items if any exists
        String readOnlyText =
                """
                              <workspace_readonly>
                              Here are the READ ONLY files and code fragments in your Workspace.
                              Do not edit this code! Images will be included separately if present.

                              %s
                              </workspace_readonly>
                              """
                        .stripIndent()
                        .formatted(readOnlyTextFragments.toString().trim());

        // text and image content must be distinct
        allContents.add(new TextContent(readOnlyText));
        allContents.addAll(readOnlyImageFragments);

        // Create the main UserMessage
        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(readOnlyUserMessage, new AiMessage("Thank you for the read-only context."));
    }

    /**
     * Returns messages containing only the editable workspace content. Does not include read-only content or related
     * classes.
     */
    public final Collection<ChatMessage> getWorkspaceEditableMessages(Context ctx) {
        // --- Process Editable Fragments ---
        var editableTextFragments = new StringBuilder();
        ctx.getEditableFragments().forEach(fragment -> {
            String formatted = fragment.format(); // format() on live fragment
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
                        .stripIndent()
                        .formatted(editableTextFragments.toString().trim());

        var editableUserMessage = new UserMessage(editableText);
        return List.of(editableUserMessage, new AiMessage("Thank you for the editable context."));
    }

    /**
     * Constructs the ChatMessage(s) representing the current workspace context (read-only and editable
     * files/fragments). Handles both text and image fragments, creating a multimodal UserMessage if necessary.
     *
     * @return A collection containing one UserMessage (potentially multimodal) and one AiMessage acknowledgment, or
     *     empty if no content.
     */
    public final Collection<ChatMessage> getWorkspaceContentsMessages(Context ctx) {
        var readOnlyMessages = getWorkspaceReadOnlyMessages(ctx);
        var editableMessages = getWorkspaceEditableMessages(ctx);

        return getWorkspaceContentsMessages(readOnlyMessages, editableMessages);
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
                        .stripIndent()
                        .formatted(combinedText.toString().trim());

        // Add the workspace text as the first content
        allContents.addFirst(new TextContent(workspaceText));

        // Create the main UserMessage
        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * @return a summary of each fragment in the workspace; for most fragment types this is just the description, but
     *     for some (SearchFragment) it's the full text and for others (files, skeletons) it's the class summaries.
     */
    public final Collection<ChatMessage> getWorkspaceSummaryMessages(Context ctx) {
        var summaries = ContextFragment.describe(ctx.getAllFragmentsInDisplayOrder());
        if (summaries.isEmpty()) {
            return List.of();
        }

        String summaryText =
                """
                             <workspace-summary>
                             %s
                             </workspace-summary>
                             """
                        .stripIndent()
                        .formatted(summaries)
                        .trim();

        var summaryUserMessage = new UserMessage(summaryText);
        return List.of(summaryUserMessage, new AiMessage("Okay, I have the workspace summary."));
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
             or function-like (method, static method) entity, but NOT anonymous ones.""";
            hints = "- Use syntax-aware SEARCH when you are rewriting an entire class or function.\n" + hints;
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

        var examples = buildExamples(flags);

        var intro = flags.isEmpty()
                ? ""
                : "The *SEARCH/REPLACE* engine has been upgraded and supports more powerful features than simple line-based edits; pay close attention to the instructions. ";

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

# General
Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.

Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!

%s
</rules>

<goal>
%s
</goal>
"""
                .formatted(intro, searchContents, hints, examples, reminder, input);
    }

    /**
     * Builds example SEARCH/REPLACE blocks that demonstrate correct formatting.
     *
     * <p>The examples use a single Java source file (src/main/java/com/acme/Foo.java) and show: - A "Before" workspace
     * excerpt (when MERGE_AGENT_MARKERS is enabled, this includes an actual conflict block) - A line-based SEARCH edit
     * - Optional syntax-aware edits (BRK_FUNCTION and BRK_CLASS) when enabled - A full-file replacement using
     * BRK_ENTIRE_FILE - Optional conflict-range fix using BRK_CONFLICT_1 when MERGE_AGENT_MARKERS is enabled
     *
     * <p>The examples are illustrative only and intended to show the exact wire format, not working diffs against a
     * real repository.
     */
    private static String buildExamples(Set<InstructionsFlags> flags) {
        var parts = new ArrayList<String>();

        // ---------- BEFORE: current workspace excerpt ----------
        // If MERGE_AGENT_MARKERS is enabled, show an actual conflict block in the file.
        var before = flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS)
                ? """
              ### Before: Current Workspace excerpt (with conflict markers present)

              <workspace_example>
                <file path="src/main/java/com/acme/Foo.java" fragmentid="1">
                package com.acme;

                import java.util.List;
                import java.util.Objects;

                public class Foo {
                    public int compute(int a, int b) {
                        // naive implementation
                        return a + b;
                    }

                    public String greet(String name) {
                        return "Hello, " + name + "!";
                    }

                    // The Merge Agent has wrapped a Git-style conflict inside custom markers.
                    BRK_CONFLICT_BEGIN_1
                    <<<<<<< HEAD
                    private static int fib(int n) {
                        if (n <= 1) return n;
                        return fib(n - 1) + fib(n - 2);
                    }
                    =======
                    private static int fib(int n) {
                        if (n < 2) return n;
                        int a = 0, b = 1;
                        for (int i = 2; i <= n; i++) {
                            int tmp = a + b;
                            a = b;
                            b = tmp;
                        }
                        return b;
                    }
                    >>>>>>> feature/iterative-fib
                    BRK_CONFLICT_END_1
                }
                </file>
              </workspace_example>
              """
                        .stripIndent()
                : """
              ### Before: Current Workspace excerpt

              <workspace_example>
                <file path="src/main/java/com/acme/Foo.java" fragmentid="1">
                package com.acme;

                import java.util.List;
                import java.util.Objects;

                public class Foo {
                    public int compute(int a, int b) {
                        // naive implementation
                        return a + b;
                    }

                    public String greet(String name) {
                        return "Hello, " + name + "!";
                    }

                    private static int fib(int n) {
                        if (n <= 1) return n;
                        return fib(n - 1) + fib(n - 2);
                    }
                }
                </file>
              </workspace_example>
              """
                        .stripIndent();

        parts.add(before);

        int ex = 1;

        // ---------- Example 1: Line-based SEARCH ----------
        parts.add(
                """
              ### Example %d — Line-based SEARCH (modify a fragment outside of a method)

              ```
              src/main/java/com/acme/Foo.java
              <<<<<<< SEARCH
              import java.util.List;
              import java.util.Objects;
              =======
              import java.util.List;
              >>>>>>> REPLACE
              ```
              """
                        .formatted(ex++)
                        .stripIndent());

        // ---------- Syntax-aware examples (only if enabled) ----------
        if (flags.contains(InstructionsFlags.SYNTAX_AWARE)) {
            // BRK_FUNCTION: replace a single method by fully qualified name
            parts.add(
                    """
                  ### Example %d — Syntax-aware SEARCH for a function (BRK_FUNCTION)

                  ```
                  src/main/java/com/acme/Foo.java
                  <<<<<<< SEARCH
                  BRK_FUNCTION com.acme.Foo.greet
                  =======
                  public String greet(String name) {
                      return "Hi, " + name;
                  }
                  >>>>>>> REPLACE
                  ```
                  """
                            .formatted(ex++)
                            .stripIndent());

            // BRK_CLASS: replace the entire class body by fully qualified name
            // Note: For BRK_CLASS, provide the class block (not package/imports) as the replacement.
            parts.add(
                    """
                  ### Example %d — Syntax-aware SEARCH for an entire class (BRK_CLASS)

                  ```
                  src/main/java/com/acme/Foo.java
                  <<<<<<< SEARCH
                  BRK_CLASS com.acme.Foo
                  =======
                  public class Foo {
                      public int compute(int a, int b) {
                          return Math.addExact(a, b);
                      }

                      public String greet(String name) {
                          return "Hello, " + name + "!";
                      }

                      private static int fib(int n) {
                          if (n < 2) return n;
                          int a = 0, b = 1;
                          for (int i = 2; i <= n; i++) {
                              int tmp = a + b;
                              a = b;
                              b = tmp;
                          }
                          return b;
                      }
                  }
                  >>>>>>> REPLACE
                  ```
                  """
                            .formatted(ex++)
                            .stripIndent());
        }

        // ---------- Example: Full-file replacement using BRK_ENTIRE_FILE ----------
        parts.add(
                """
              ### Example %d — Full-file replacement (BRK_ENTIRE_FILE)

              ```
              src/main/java/com/acme/Foo.java
              <<<<<<< SEARCH
              BRK_ENTIRE_FILE
              =======
              package com.acme;

              public class Foo {
                  public int compute(int a, int b) {
                      return Math.addExact(a, b);
                  }

                  public String greet(String name) {
                      return "Hello, " + name + "!";
                  }

                  private static int fib(int n) {
                      if (n < 2) return n;
                      int a = 0, b = 1;
                      for (int i = 2; i <= n; i++) {
                          int next = Math.addExact(a, b);
                          a = b;
                          b = next;
                      }
                      return b;
                  }
              }
              >>>>>>> REPLACE
              ```
              """
                        .formatted(ex++)
                        .stripIndent());

        // ---------- Conflict-range fix (only if enabled) ----------
        if (flags.contains(InstructionsFlags.MERGE_AGENT_MARKERS)) {
            parts.add(
                    """
                  ### Example %d — Conflict range fix (BRK_CONFLICT markers)

                  The SEARCH is a **single line** that targets the entire conflict region, regardless of its contents.
                  Replace that region with the resolved implementation.

                  ```
                  src/main/java/com/acme/Foo.java
                  <<<<<<< SEARCH
                  BRK_CONFLICT_1
                  =======
                  private static int fib(int n) {
                      if (n < 2) return n;
                      int a = 0, b = 1;
                      for (int i = 2; i <= n; i++) {
                          int tmp = Math.addExact(a, b);
                          a = b;
                          b = tmp;
                      }
                      return b;
                  }
                  >>>>>>> REPLACE
                  ```
                  """
                            .formatted(ex++)
                            .stripIndent());
        }

        return String.join("\n\n", parts).strip();
    }
}
