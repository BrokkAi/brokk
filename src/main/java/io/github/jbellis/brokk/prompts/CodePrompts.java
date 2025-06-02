package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * Generates prompts for the main coding agent loop, including instructions for SEARCH/REPLACE blocks.
 */
public abstract class CodePrompts {
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
                                                       ArrayList<ChatMessage> taskMessages,
                                                       UserMessage request)
    throws InterruptedException
    {
        // TODO revisit cache-friendly message ordering with the next generation of models,
        // as of early 2025 o4-mini in particular gets very confused when it sees (its own) edits in the history
        // after the initial workspace contents
        var messages = new ArrayList<ChatMessage>();
        var reminder = reminderForModel(cm.getService(), model);

        messages.add(systemMessage(cm, reminder));
        messages.addAll(cm.getWorkspaceReadOnlyMessages());
        messages.addAll(parser.exampleMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(taskMessages);
        messages.addAll(cm.getWorkspaceEditableMessages());
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
               don't hesitate to ask for clarification.
               
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
                                                IConsoleIO io,
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

        // Instructions for the LLM
        String instructions = """
                      <instructions>
                      # %d SEARCH/REPLACE block%s failed to match in %d files!
                      
                      Take a look at the CURRENT state of the relevant file%s provided above in the `<current_content>` tags.
                      If the failed edits listed in the `<failed_blocks>` tags are still needed, please correct them based on the current content.
                      Remember that the SEARCH text within a `<block>` must match EXACTLY the lines in the file -- but
                      I can accommodate whitespace differences, so if you think the only problem is whitespace, you need to look closer.
                      If the SEARCH text looks correct, double-check the filename too.
                      
                      Provide corrected SEARCH/REPLACE blocks for the failed edits only.
                      </instructions>
                      """.formatted(totalFailCount, pluralizeFail, failuresByFile.size(), pluralizeFail).stripIndent();

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
                                                                ArrayList<ChatMessage> taskMessages)
    throws InterruptedException
    {
        var messages = new ArrayList<ChatMessage>();
        var styleGuide = cm.getProject().getStyleGuide();

        // 1. System Intro + Style Guide
        messages.add(systemMessage(cm, styleGuide));
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
}
