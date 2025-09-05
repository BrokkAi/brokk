package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.LineEditor;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.ImageUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

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
            Always format your entire response using GFM Markdown to **improve the readability** of your responses with:
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
     * Redacts BRK_EDIT_EX blocks from an AiMessage (mixed content allowed).
     * - ED blocks become a placeholder that cannot be confused with valid blocks.
     * - BRK_EDIT_RM lines are preserved.
     * - If there are no edits, returns the original message (unless blank).
     */
    public static Optional<AiMessage> redactAiMessage(AiMessage aiMessage) {
        var rawText = aiMessage.text();
        var parsed = LineEditorParser.instance.parse(rawText);

        if (!parsed.hasEdits()) {
            return rawText.isBlank() ? Optional.empty() : Optional.of(aiMessage);
        }

        var redacted = redactParsedEdBlocks(rawText);
        return redacted.isBlank() ? Optional.empty() : Optional.of(new AiMessage(redacted));
    }

    /** Helper used by redactAiMessage(AiMessage): elide BRK_EDIT_EX blocks from raw text while preserving BRK_EDIT_RM lines. */
    private static @NotNull String redactParsedEdBlocks(String rawText) {
        var sb = new StringBuilder();
        var lines = rawText.split("\n", -1); // keep trailing empty
        boolean inBlock = false;
        String currentPath = null;

        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            var trimmed = line.trim();

            if (!inBlock) {
                if (trimmed.equals("BRK_EDIT_EX") || trimmed.startsWith("BRK_EDIT_EX ")) {
                    // Start of an edit block; record the path (if present) and emit a placeholder
                    int sp = trimmed.indexOf(' ');
                    currentPath = (sp >= 0) ? trimmed.substring(sp + 1).trim() : null;

                    if (currentPath == null || currentPath.isBlank()) {
                        sb.append("[[ELIDED EDIT BLOCK]]\n");
                    } else {
                        sb.append("[[ELIDED EDIT BLOCK for ").append(currentPath).append("]]\n");
                    }
                    inBlock = true;
                } else {
                    sb.append(line).append('\n');
                }
            } else {
                // Inside a BRK_EDIT_EX block: elide until END fence (or EOF)
                if (trimmed.equals("BRK_EDIT_EX_END")) {
                    inBlock = false;
                    currentPath = null;
                }
                // Do not append body lines
            }
        }

        return sb.toString();
    }

    public final List<ChatMessage> collectCodeMessages(
            IContextManager cm,
            StreamingChatModel model,
            List<ChatMessage> taskMessages,
            UserMessage request,
            Set<ProjectFile> changedFiles)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();
        var reminder = codeReminder(cm.getService(), model);
        Context ctx = cm.liveContext();

        messages.add(systemMessage(cm, reminder));
        messages.addAll(LineEditorParser.instance.exampleMessages());
        if (changedFiles.isEmpty()) {
            messages.addAll(getWorkspaceContentsMessages(ctx));
        } else {
            messages.addAll(getWorkspaceContentsMessages(getWorkspaceReadOnlyMessages(ctx), List.of()));
        }
        messages.addAll(getHistoryMessages(ctx));
        messages.addAll(taskMessages);
        if (!changedFiles.isEmpty()) {
            messages.addAll(getWorkspaceContentsMessages(List.of(), getWorkspaceEditableMessages(ctx)));
        }
        messages.add(request);

        return messages;
    }

    public final List<ChatMessage> getSingleFileCodeMessages(
            String styleGuide,
            List<ChatMessage> readOnlyMessages,
            List<ChatMessage> taskMessages,
            UserMessage request,
            ProjectFile file) {
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
                        .formatted(systemIntro(""), styleGuide)
                        .trim();
        messages.add(new SystemMessage(systemPrompt));

        messages.addAll(LineEditorParser.instance.exampleMessages());
        messages.addAll(readOnlyMessages);

        try {
            String editableText =
                    """
                                  <workspace_editable>
                                  You are editing A SINGLE FILE in this Workspace.
                                  This represents the current state of the file.

                                  <file path="%s">
                                  %s
                                  </file>
                                  </workspace_editable>
                                  """
                            .stripIndent()
                            .formatted(file.toString(), file.read());
            var editableUserMessage = new UserMessage(editableText);
            messages.addAll(List.of(editableUserMessage, new AiMessage("Thank you for the editable context.")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        messages.addAll(taskMessages);
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

        String fileContent = null;
        try {
            fileContent =
                    """
                              <file path="%s">
                              %s
                              </file>
                              """
                            .stripIndent()
                            .formatted(file.toString(), file.read());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
    public static String formatWorkspaceDescriptions(IContextManager cm) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var workspaceBuilder = new StringBuilder();
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }
        return workspaceBuilder.toString();
    }

    protected SystemMessage systemMessage(IContextManager cm, String reminder) {
        var styleGuide = cm.getProject().getStyleGuide();

        var text =
                """
          <instructions>
          %s
          </instructions>
          <style_guide>
          %s
          </style_guide>
          """
                        .stripIndent()
                        .formatted(systemIntro(reminder), styleGuide)
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

    public UserMessage codeRequest(String input, String reminder, @Nullable ProjectFile file) {
        var instructions =
                """
        <instructions>
        Think about this request for changes to the supplied code.
        If the request is ambiguous, %s.

        Once you understand the request you MUST:

        1. Decide if you need to propose Line-Edit Tags for any code whose source is not available.
           You can create new files without asking!
           But if you need to propose changes to code you can't see,
           you *MUST* tell the user their full filename names and ask them to *add the files to the chat*;
           end your reply and wait for their approval.
           But if you only need to change individual functions whose code you can see,
           you may do so without having the entire file in the Workspace.

        2. Explain the needed changes in a few short sentences.

        3. Describe each change with Line-Edit Tags.

        All changes to files must use the Line-Edit Tag format.

        If a file is read-only or unavailable, ask the user to add it or make it editable.

        If you are struggling to use a dependency or API correctly, you MUST stop and ask the user for help.
        """
                        .formatted(
                                GraphicsEnvironment.isHeadless()
                                        ? "decide what the most logical interpretation is"
                                        : "ask questions");
        return new UserMessage(instructions + LineEditorParser.instance.instructions(input, file, reminder));
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
    public static String getApplyFailureMessage(
            List<EditBlock.FailedBlock> failedBlocks, EditBlockParser parser, int succeededCount, IContextManager cm) {
        if (failedBlocks.isEmpty()) {
            return "";
        }

        // Group failed blocks by filename
        var failuresByFile = failedBlocks.stream()
                .filter(fb -> fb.block().filename() != null) // Only include blocks with filenames
                .collect(Collectors.groupingBy(fb -> fb.block().filename()));

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
                      Remember that the SEARCH text within a `<block>` must match EXACTLY the lines in the file -- but
                      I can accommodate whitespace differences, so if you think the only problem is whitespace, you need to look closer.
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
                                        .formatted(f.reason(), parser.repr(f.block()), commentaryText)
                                        .stripIndent();
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                           <file name="%s">
                           <failed_blocks>
                           %s
                           </failed_blocks>
                           </file>
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
     * Collects messages for a full-file replacement request, typically used as a fallback when standard SEARCH/REPLACE
     * fails repeatedly. Includes system intro, history, workspace, target file content, and the goal. Asks for the
     * *entire* new file content back.
     *
     * @param cm ContextManager to access history, workspace, style guide.
     * @param targetFile The file whose content needs full replacement.
     * @param goal The user's original goal or reason for the replacement (e.g., build error).
     * @param taskMessages
     * @return List of ChatMessages ready for the LLM.
     */
    public List<ChatMessage> collectFullFileReplacementMessages(
            IContextManager cm,
            ProjectFile targetFile,
            List<EditBlock.FailedBlock> failures,
            String goal,
            List<ChatMessage> taskMessages) {
        var messages = new ArrayList<ChatMessage>();

        // 1. System Intro + Style Guide
        messages.add(systemMessage(cm, LAZY_REMINDER));
        // 2. No examples provided for full-file replacement

        // 3. History Messages (provides conversational context)
        messages.addAll(getHistoryMessages(cm.liveContext()));

        // 4. Workspace
        messages.addAll(getWorkspaceContentsMessages(cm.liveContext()));

        // 5. task-messages-so-far
        messages.addAll(taskMessages);

        // 5. Target File Content + Goal + Failed Blocks
        String currentContent;
        try {
            currentContent = targetFile.read();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read target file for full replacement prompt: " + targetFile, e);
        }

        var failedBlocksText = failures.stream()
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
                            </block>
                            %s
                            </failed_block>
                            """
                            .formatted(f.reason(), f.block().toString(), commentaryText);
                })
                .collect(Collectors.joining("\n"));

        var userMessage =
                """
            You are now performing a full-file replacement because previous edits failed.

            Remember that this was the original goal:
            <goal>
            %s
            </goal>

            Here is the current content of the file:
            <file source="%s">
            %s
            </file>

            Here are the specific edit blocks that failed to apply:
            <failed_blocks>
            %s
            </failed_blocks>

            Review the conversation history, workspace contents, the current source code, and the failed edit blocks.
            Figure out what changes we are trying to make to implement the goal,
            then provide the *complete and updated* new content for the entire file,
            fenced with triple backticks. Omit language identifiers or other markdown options.
            Think about your answer before starting to edit.
            You MUST include the backtick fences, even if the correct content is an empty file.
            DO NOT modify the file except for the changes pertaining to the goal!
            DO NOT use the SEARCH/REPLACE format you see earlier -- that didn't work!
            """
                        .formatted(goal, targetFile, currentContent, failedBlocksText);
        messages.add(new UserMessage(userMessage));

        return messages;
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
                } catch (IOException e) {
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
        var summaries = ContextFragment.getSummary(ctx.getAllFragmentsInDisplayOrder());
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

        // Merge compressed messages into a single taskhistory message
        var compressed = taskHistory.stream()
                .filter(TaskEntry::isCompressed)
                .map(TaskEntry::toString) // This will use raw messages if TaskEntry was created with them
                .collect(Collectors.joining("\n\n"));
        if (!compressed.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(compressed)));
            messages.add(new AiMessage("Ok, I see the history."));
        }

        // Uncompressed messages: process for Line-Edit Tag redaction
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

    /**
     * Generates a message for parse failures returned by the LineEditorParser.
     * Aligns with apply failure reporting: shows problem source (snippet), reason, and commentary.
     */
    public static String getParseFailureMessage(
            List<LineEditorParser.ParseFailure> failures,
            @Nullable LineEditorParser.ParseError fatalError,
            int parsedEditsCount,
            @Nullable io.github.jbellis.brokk.LineEdit lastGoodEdit) {
        if (failures.isEmpty() && fatalError == null) {
            return """
                   <instructions>
                   We could not understand your Line Edit tags. Please resend well-formed BRK_EDIT_EX / BRK_EDIT_RM blocks.
                   </instructions>
                   """.stripIndent();
        }

        var sb = new StringBuilder();
        sb.append("""
<instructions>
# Parse error%s in Line-Edit tags!

Review the failures below. For each one, fix the problem and resend corrected **BRK_EDIT_EX / BRK_EDIT_RM** blocks.
Remember:
- Emit exactly one anchor per address (for ranges, anchor the first and last line only).
- Use `0 a` to insert at start, `$ a` to append at end; use `@0| ` or `@$| ` as blank anchors when applicable.
- Bodies end with a single `.` on its own line; escape literal lines of `.` as `\\.` and `\\` as `\\\\`.
- Always close each block with `BRK_EDIT_EX_END`. Avoid overlapping edits, and keep addresses 1-based and inclusive.
</instructions>

<parse_failures>
""".stripIndent().formatted(failures.size() == 1 ? "" : "s"));

        for (var f : failures) {
            sb.append("""
  <failure reason="%s">
  <problem_source>
  %s
  </problem_source>
  <commentary>
  %s
  </commentary>
  </failure>

""".formatted(f.reason(), f.snippet().isBlank() ? "<unavailable>" : f.snippet().trim(), f.commentary().trim()));
        }
        sb.append("</parse_failures>\n\n");

        if (fatalError != null) {
            sb.append("""
<note>
Fatal parse condition detected: %s.
If your block ended at EOF, make sure the edit body ended with a single '.' on its own line,
and include BRK_EDIT_EX_END unless the response ends immediately after a complete edit.
</note>

""".stripIndent().formatted(fatalError));
        }

        if (parsedEditsCount > 0 && lastGoodEdit != null) {
            sb.append("""
<last_good_edit>
%s
</last_good_edit>

Please continue from there WITHOUT repeating that edit.
""".stripIndent().formatted(lastGoodEdit.repr()));
        }

        return sb.toString();
    }

    public static String getLineEditFailureMessage(
            List<LineEditor.ApplyFailure> failures,
            int succeededCount,
            IContextManager cm) {

        if (failures.isEmpty()) {
            return "";
        }

        var byFile = failures.stream()
                .collect(Collectors.groupingBy(f -> f.edit().file()));

        var sb = new StringBuilder();
        int total = failures.size();
        boolean single = total == 1;
        sb.append("""
<instructions>
# %d edit command failure%s in %d file%s!

Review the current state of these files above in the Workspace.
Provide corrected **BRK_EDIT_EX / BRK_EDIT_RM** blocks for the failed edits only.

Tips:
- Addresses are absolute numeric (1-based). Ranges n,m are inclusive; use `0` and `$` as documented.
- **Anchors are mandatory for every numeric address except `0` and `$`:**
  - After `a n` provide `n: <current line at n>` (you may omit the anchor if `n` is `0` or `$`)
  - After `c n[,m]` provide `n:` (and `m:` when present; `0`/`$` anchors may be omitted)
  - After `d n[,m]` provide `n:` (and `m:` when present; `0`/`$` anchors may be omitted)
- Body is required only for **a** and **c**; end it with a single `.` on its own line.
- Emit commands in **descending line order (last edits first)** within each file to avoid line shifts.
- Overlapping edits are an error.
</instructions>
""".stripIndent().formatted(total, single ? "" : "s", byFile.size(), byFile.size() == 1 ? "" : "s"));

        for (var entry : byFile.entrySet()) {
            var file = entry.getKey();
            var fileFailures = entry.getValue();

            sb.append("<file name=\"").append(file).append("\">\n");
            sb.append("<failed_edits>\n");

            for (var f : fileFailures) {
                sb.append("""
            <failed_edit reason="%s">
            <edit>
            %s
            </edit>
            <commentary>
            %s
            </commentary>
            </failed_edit>
            """.formatted(f.reason(), f.edit().repr(), f.commentary()));
            }

            sb.append("</failed_edits>\n</file>\n\n");
        }
        if (succeededCount > 0) {
            sb.append("""
        <note>
        The other %d edit%s applied successfully and are reflected in the latest file contents already. Do not re-send them; only fix the failures listed above.
        </note>
        """.stripIndent().formatted(succeededCount, succeededCount == 1 ? "" : "s"));
        }

        return sb.toString();
    }
}
