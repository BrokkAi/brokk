package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.ImageUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks.
 */
public abstract class CodePrompts {
    private static final Logger logger = LogManager.getLogger(CodePrompts.class);
    public static final CodePrompts instance = new CodePrompts() {}; // Changed instance creation

    public static final String LAZY_REMINDER = """
            You are diligent and tireless!
            You NEVER leave comments describing code without implementing it!
            You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
            """.stripIndent();

    public static final String OVEREAGER_REMINDER = """
            Avoid changing code or comments that are not directly related to the request.

            Do not comment on your modifications, only on the resulting code in isolation.
            You must never output any comments about the progress or type of changes of your refactoring or generation. 
            For example, you must NOT add comments like: 'Added dependency' or 'Changed to new style' or worst of all 'Keeping existing implementation'.
            """.stripIndent();

    public static final String ARCHITECT_REMINDER = """
            Pay careful attention to the scope of the user's request. Attempt to do everything required
            to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
            For example, if the user asks you a question, you should do your best to answer his question first,
            before immediately jumping into taking further action.
            """.stripIndent();

    // Now takes a Models instance
    public static String reminderForModel(Service service, StreamingChatLanguageModel model) {
        return service.isLazy(model)
                ? LAZY_REMINDER
                : OVEREAGER_REMINDER;
    }

    public final List<ChatMessage> collectCodeMessages(IContextManager cm,
                                                       StreamingChatLanguageModel model,
                                                       EditBlockParser parser,
                                                       List<ChatMessage> taskMessages,
                                                       UserMessage request,
                                                       Collection<ProjectFile> changedFiles,
                                                       List<ChatMessage> originalWorkspaceEditableMessages)
    throws InterruptedException
    {
        // TODO revisit cache-friendly message ordering with the next generation of models,
        // as of early 2025 o4-mini in particular gets very confused when it sees (its own) edits in the history
        // after the initial workspace contents
        var messages = new ArrayList<ChatMessage>();
        var reminder = reminderForModel(cm.getService(), model);

        messages.add(systemMessage(cm, reminder));
        messages.addAll(cm.getWorkspaceReadOnlyMessages());
        messages.addAll(originalWorkspaceEditableMessages);
        messages.addAll(parser.exampleMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(taskMessages);
        messages.addAll(getCurrentChangedFilesMessages(cm, changedFiles));
        messages.add(request);

        return messages;
    }

    public final List<ChatMessage> collectAskMessages(ContextManager cm, String input) throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        messages.add(systemMessage(cm, ""));
        messages.addAll(cm.getWorkspaceContentsMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.add(askRequest(input));

        return messages;
    }

    /**
     * Generates a concise description of the workspace contents.
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
        var workspaceSummary = formatWorkspaceDescriptions(cm);
        var styleGuide = cm.getProject().getStyleGuide();

        var text = """
          <instructions>
          %s
          </instructions>
          <workspace-summary>
          %s
          </workspace-summary>
          <style_guide>
          %s
          </style_guide>
          """.stripIndent().formatted(systemIntro(reminder), workspaceSummary, styleGuide).trim();

        return new SystemMessage(text);
    }

    public String systemIntro(String reminder) {
        return """
        Act as an expert software developer.
        Always use best practices when coding.
        Respect and use existing conventions, libraries, etc. that are already present in the code base.
        
        %s
        """.stripIndent().formatted(reminder);
    }

    public UserMessage codeRequest(String input, String reminder, EditBlockParser parser) {
        var instructions = """
        <instructions>
        Think about this request for changes to the supplied code.
        If the request is ambiguous, ask questions.
        
        Once you understand the request you MUST:
        
        1. Decide if you need to propose *SEARCH/REPLACE* edits for any code whose source is not available.
           You can create new files without asking!
           But if you need to propose changes to code you can't see,
           you *MUST* tell the user their full filename names and ask them to *add the files to the chat*;
           end your reply and wait for their approval.
           But if you only need to change individual functions whose code you can see,
           you may do so without having the entire file in the Workspace.
        
        2. Explain the needed changes in a few short sentences.
        
        3. Describe each change with a *SEARCH/REPLACE* block.

        All changes to files must use this *SEARCH/REPLACE* block format.

        If a file is read-only or unavailable, ask the user to add it or make it editable.
        
        If you are struggling to use a dependency or API correctly, stop and ask the user for help.
        """;
        return new UserMessage(instructions + parser.instructions(input, reminder));
    }

    public UserMessage askRequest(String input) {
        var text = """
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
               
               Format your answer with Markdown for readability.
               </instructions>
               
               <question>
               %s
               </question>
               """.formatted(input);
        return new UserMessage(text);
    }

    /**
     * Generates a message based on parse/apply errors from failed edit blocks
     */
    public static String getApplyFailureMessage(List<EditBlock.FailedBlock> failedBlocks,
                                                EditBlockParser parser,
                                                int succeededCount,
                                                IContextManager cm)
    {
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
        String instructions = """
                      <instructions>
                      # %d SEARCH/REPLACE block%s failed to match in %d files!
                      
                      Take a look at the CURRENT state of the relevant file%s provided below in the `<current_content>` tags.
                      If the failed edits listed in the `<failed_blocks>` tags are still needed, please correct them based on the current content.
                      Remember that the SEARCH text within a `<block>` must match EXACTLY the lines in the file -- but
                      I can accommodate whitespace differences, so if you think the only problem is whitespace, you need to look closer.
                      If the SEARCH text looks correct, double-check the filename too.
                      
                      Provide corrected SEARCH/REPLACE blocks for the failed edits only.
                      </instructions>
                      """.formatted(totalFailCount, pluralizeFail, failuresByFile.size(), pluralizeFail).stripIndent();

        String fileDetails = failuresByFile.entrySet().stream()
                .map(entry -> {
                    var filename = entry.getKey();
                    var fileFailures = entry.getValue();
                    var file = cm.toFile(filename);
                    String currentContentBlock;
                    try {
                        var content = file.read();
                        currentContentBlock = """
                                              <current_content>
                                              %s
                                              </current_content>
                                              """.formatted(content.isBlank() ? "[File is empty]" : content).stripIndent();
                    } catch (java.io.IOException e) {
                        return null;
                    }

                    String failedBlocksXml = fileFailures.stream()
                            .map(f -> {
                                var commentaryText = f.commentary().isBlank()
                                                     ? ""
                                                     : """
                                                       <commentary>
                                                       %s
                                                       </commentary>
                                                       """.formatted(f.commentary());
                                return """
                                       <failed_block reason="%s">
                                       <block>
                                       %s
                                       %s
                                       </block>
                                       </failed_block>
                                       """.formatted(f.reason(), parser.repr(f.block()), commentaryText).stripIndent();
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                           <file name="%s">
                           %s
                           
                           <failed_blocks>
                           %s
                           </failed_blocks>
                           </file>
                           """.formatted(filename, currentContentBlock, failedBlocksXml).stripIndent();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));

        // Add info about successful blocks, if any
        String successNote = "";
        if (succeededCount > 0) {
            boolean singularSuccess = (succeededCount == 1);
            var pluralizeSuccess = singularSuccess ? "" : "s";
            successNote = """
                          <note>
                          The other %d SEARCH/REPLACE block%s applied successfully. Do not re-send them. Just fix the failing blocks detailed above.
                          </note>
                          """.formatted(succeededCount, pluralizeSuccess).stripIndent();
        }

        // Construct the full message for the LLM
        return """
               %s
               
               %s
               %s
               """.formatted(instructions, fileDetails, successNote).stripIndent();
    }

    /**
     * Collects messages for a full-file replacement request, typically used as a fallback
     * when standard SEARCH/REPLACE fails repeatedly. Includes system intro, history, workspace,
     * target file content, and the goal. Asks for the *entire* new file content back.
     *
     * @param cm              ContextManager to access history, workspace, style guide.
     * @param targetFile      The file whose content needs full replacement.
     * @param goal            The user's original goal or reason for the replacement (e.g., build error).
     * @param taskMessages
     * @return List of ChatMessages ready for the LLM.
     */
    public List<ChatMessage> collectFullFileReplacementMessages(IContextManager cm,
                                                                ProjectFile targetFile,
                                                                String goal,
                                                                List<ChatMessage> taskMessages)
    throws InterruptedException
    {
        var messages = new ArrayList<ChatMessage>();

        // 1. System Intro + Style Guide
        messages.add(systemMessage(cm, LAZY_REMINDER));
        // 2. No examples provided for full-file replacement

        // 3. History Messages (provides conversational context)
        messages.addAll(cm.getHistoryMessages());

        // 4. Workspace
        messages.addAll(cm.getWorkspaceContentsMessages());

        // 5. task-messages-so-far
        messages.addAll(taskMessages);

        // 5. Target File Content + Goal
        String currentContent;
        try {
            currentContent = targetFile.read();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read target file for full replacement prompt: " + targetFile, e);
        }

        var userMessage = """
            You are now performing a full-file replacement because previous edits failed.
            
            Remember that this was the original goal:
            <goal>
            %s
            </goal>
            
            Here is the current content of the file:
            <file source="%s">
            %s
            </file>
            
            Review the conversation history, workspace contents, and the current source code.
            Figure out what changes we are trying to make to implement the goal,
            then provide the *complete and updated* new content for the entire file,
            fenced with triple backticks. Omit language identifiers or other markdown options.
            Think about your answer before starting to edit.
            You MUST include the backtick fences, even if the correct content is an empty file.
            DO NOT modify the file except for the changes pertaining to the goal!
            DO NOT use the SEARCH/REPLACE format you see earlier -- that didn't work!
            """.formatted(goal, targetFile, currentContent);
        messages.add(new UserMessage(userMessage));

        return messages;
    }

    public List<ChatMessage> getSimpleFileReplaceMessages(IProject project, ProjectFile targetFile, String goal) {
        var messages = new ArrayList<ChatMessage>();
        var styleGuide = project.getStyleGuide();

        // 1. System Intro + Style Guide
        var text = """
          <instructions>
          %s
          </instructions>
          <style_guide>
          %s
          </style_guide>
          """.stripIndent().formatted(systemIntro(LAZY_REMINDER), styleGuide).trim();
        messages.add(new SystemMessage(text));

        // 2. Target File Content + Goal
        String currentContent;
        try {
            currentContent = targetFile.read();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read target file for full replacement prompt: " + targetFile, e);
        }

        var userMessage = """
            You are now performing a full-file replacement of a single file.
            
            Here is your goal:
            <goal>
            %s
            </goal>
            
            Here is the current content of the file:
            <file source="%s">
            %s
            </file>
            
            Figure out the necessary changes to implement the goal,
            then provide the *complete and updated* new content for the entire file,
            fenced with triple backticks. Omit language identifiers or other markdown options.

            Think about your answer before starting to edit.
            You MUST include the backtick fences, even if the correct content is an empty file.
            DO NOT modify the file except for the changes pertaining to the goal!
            
            As a special case, if no changes are required, then give the special marker BRK_NO_CHANGES_REQUIRED
            and do not repeat the file contents.
            """.formatted(goal, targetFile, currentContent);
        messages.add(new UserMessage(userMessage));

        return messages;
    }

    /**
     * Returns messages containing only the read-only workspace content (files, virtual fragments, etc.).
     * Does not include editable content or related classes.
     */
    public final Collection<ChatMessage> getWorkspaceReadOnlyMessages(IContextManager cm) throws InterruptedException {
        var c = cm.liveContext();
        var allContents = new ArrayList<Content>();

        // --- Process Read-Only Fragments from liveContext (Files, Virtual, AutoContext) ---
        var readOnlyTextFragments = new StringBuilder();
        var readOnlyImageFragments = new ArrayList<ImageContent>();
        c.getReadOnlyFragments()
                .forEach(fragment -> {
                    if (fragment.isText()) {
                        // Handle text-based fragments
                        String formatted = fragment.format(); // No analyzer
                        if (formatted != null && !formatted.isBlank()) {
                            readOnlyTextFragments.append(formatted).append("\n\n");
                        }
                    } else if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE ||
                               fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
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
                            readOnlyTextFragments.append(String.format("[Error processing image: %s - %s]\n\n", fragment.description(), e.getMessage()));
                        }
                    } else {
                        // Handle non-text, non-image fragments (e.g., HistoryFragment, TaskFragment)
                        // Just add their formatted representation as text
                        String formatted = fragment.format(); // No analyzer
                        if (formatted != null && !formatted.isBlank()) {
                            readOnlyTextFragments.append(formatted).append("\n\n");
                        }
                    }
                });

        if (readOnlyTextFragments.isEmpty() && readOnlyImageFragments.isEmpty()) {
            return List.of();
        }

        // Add the combined text content for read-only items if any exists
        String readOnlyText = """
                              <workspace_readonly>
                              Here are the READ ONLY files and code fragments in your Workspace.
                              Do not edit this code! Images will be included separately if present.
                              
                              %s
                              </workspace_readonly>
                              """.stripIndent().formatted(readOnlyTextFragments.toString().trim());

        // text and image content must be distinct
        allContents.add(new TextContent(readOnlyText));
        allContents.addAll(readOnlyImageFragments);

        // Create the main UserMessage
        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(readOnlyUserMessage, new AiMessage("Thank you for the read-only context."));
    }

    /**
     * Returns messages containing only the editable workspace content.
     * Does not include read-only content or related classes.
     */
    public final Collection<ChatMessage> getWorkspaceEditableMessages(IContextManager cm) throws InterruptedException {
        var c = cm.liveContext();

        // --- Process Editable Fragments ---
        var editableTextFragments = new StringBuilder();
        c.getEditableFragments().forEach(fragment -> {
            String formatted = fragment.format(); // format() on live fragment
            if (formatted != null && !formatted.isBlank()) {
                editableTextFragments.append(formatted).append("\n\n");
            }
        });

        if (editableTextFragments.isEmpty()) {
            return List.of();
        }

        String editableText = """
                              <workspace_editable>
                              Here are the EDITABLE files and code fragments in your Workspace.
                              This is *the only context in the Workspace to which you should make changes*.
                              
                              *Trust this message as the true contents of these files!*
                              Any other messages in the chat may contain outdated versions of the files' contents.
                              
                              %s
                              </workspace_editable>
                              """.stripIndent().formatted(editableTextFragments.toString().trim());

        var editableUserMessage = new UserMessage(editableText);
        return List.of(editableUserMessage, new AiMessage("Thank you for the editable context."));
    }

    /**
     * Constructs the ChatMessage(s) representing the current workspace context (read-only and editable files/fragments).
     * Handles both text and image fragments, creating a multimodal UserMessage if necessary.
     *
     * @return A collection containing one UserMessage (potentially multimodal) and one AiMessage acknowledgment, or empty if no content.
     */
    public final Collection<ChatMessage> getWorkspaceContentsMessages(IContextManager cm, boolean includeRelatedClasses) throws InterruptedException {
        var readOnlyMessages = getWorkspaceReadOnlyMessages(cm);
        var editableMessages = getWorkspaceEditableMessages(cm);

        // If both are empty and no related classes requested, return empty
        if (readOnlyMessages.isEmpty() && editableMessages.isEmpty() && !includeRelatedClasses) {
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

        // optional: related classes
        String topClassesText = "";
        if (includeRelatedClasses && cm.getAnalyzerWrapper().isCpg()) {
            var acFragment = cm.liveContext().buildAutoContext(10); // Assumes liveContext() is available on IContextManager
            String topClassesRaw = acFragment.text();
            if (!topClassesRaw.isBlank()) {
                topClassesText = """
                               <related_classes>
                               Here are some classes that may be related to what is in your Workspace. They are not yet part of the Workspace!
                               If relevant, you should explicitly add them with addClassSummariesToWorkspace or addClassesToWorkspace so they are
                               visible to Code Agent. If they are not relevant, just ignore them.
                               
                               %s
                               </related_classes>
                               """.stripIndent().formatted(topClassesRaw);
            }
        }

        // Wrap everything in workspace tags
        var workspaceText = """
                           <workspace>
                           %s
                           </workspace>
                           %s
                           """.stripIndent().formatted(combinedText.toString().trim(), topClassesText);

        // Add the workspace text as the first content
        allContents.add(0, new TextContent(workspaceText));

        // Create the main UserMessage
        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing the Workspace contents."));
    }

    public final Collection<ChatMessage> getWorkspaceContentsMessages(IContextManager cm) throws InterruptedException {
        return getWorkspaceContentsMessages(cm, false);
    }

    /**
     * @return a summary of each fragment in the workspace; for most fragment types this is just the description,
     * but for some (SearchFragment) it's the full text and for others (files, skeletons) it's the class summaries.
     */
    public final Collection<ChatMessage> getWorkspaceSummaryMessages(IContextManager cm) {
        var c = cm.topContext();

        var summaries = Streams.concat(c.getReadOnlyFragments(), c.getEditableFragments())
                .map(ContextFragment::formatSummary)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));

        if (summaries.isEmpty()) {
            return List.of();
        }

        String summaryText = """
                             <workspace-summary>
                             %s
                             </workspace-summary>
                             """.stripIndent().formatted(summaries).trim();

        var summaryUserMessage = new UserMessage(summaryText);
        return List.of(summaryUserMessage, new AiMessage("Okay, I have the workspace summary."));
    }

    /**
     * Returns messages containing the original editable workspace content at the start of the task.
     */
    public final List<ChatMessage> getOriginalWorkspaceEditableMessages(IContextManager cm) {
        var originalWorkspaceEditableContent = cm.liveContext().getEditableFragments()
                .map(ContextFragment::format)
                .collect(Collectors.joining("\n\n"))
                .trim();

        if (originalWorkspaceEditableContent.isEmpty()) {
            return List.of();
        }

        String editableText = """
                              <workspace_editable_original>
                              Here are the EDITABLE files and code fragments in your Workspace.
                              This is *the only context in the Workspace to which you should make changes*.
                              This represents the ORIGINAL state before any modifications during this session.
                              
                              Note: These files may be modified during the session. If they are changed, you will see 
                              their CURRENT versions in a separate section later in this conversation.
                              
                              %s
                              </workspace_editable_original>
                              """.stripIndent().formatted(originalWorkspaceEditableContent);

        var editableUserMessage = new UserMessage(editableText);
        return List.of(editableUserMessage, new AiMessage("Thank you for the original editable workspace state."));
    }

    /**
     * Returns messages containing the current state of files that have been changed during the task.
     */
    public final Collection<ChatMessage> getCurrentChangedFilesMessages(IContextManager cm, Collection<ProjectFile> changedFiles) {
        if (changedFiles.isEmpty()) {
            return List.of();
        }

        var changedFilesSet = Set.copyOf(changedFiles);
        var changedFilesText = cm.liveContext().getEditableFragments()
                .map(fragment -> (ContextFragment.ProjectPathFragment) fragment)
                .filter(fragment -> changedFilesSet.contains(fragment.file()))
                .map(ContextFragment::format)
                .collect(Collectors.joining("\n\n"))
                .trim();

        String currentStateText = """
                                 <workspace_editable_changed>
                                 Here are the CURRENT versions of files that have been CREATED or MODIFIED during this session.
                                 *Trust these as the true current contents of these files!*
                                 
                                 %s
                                 </workspace_editable_changed>
                                 """.stripIndent().formatted(changedFilesText);

        var currentStateUserMessage = new UserMessage(currentStateText);
        return List.of(currentStateUserMessage, new AiMessage("Thank you for the current state of modified files."));
    }
}
