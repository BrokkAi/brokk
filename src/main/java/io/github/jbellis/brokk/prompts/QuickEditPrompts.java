package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.context.ContextFragment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompts specialized for "Quick Edit":
 * We already know the target text and instructions,
 * so we don't need search/replace blocks from the LLM.
 * We simply want the LLM to output the new edited text, fenced with triple backticks.
 */
public final class QuickEditPrompts {
    public static final QuickEditPrompts instance = new QuickEditPrompts();

    /**
     * Collects the messages for a quick edit session. The difference from DefaultPrompts is that
     * we do NOT need search/replace blocks; we simply ask for the new text (fenced with triple backticks).
     */
    public List<ChatMessage> collectMessages(String fileContents,
                                             ContextFragment.SkeletonFragment relatedCode, 
                                             String styleGuide) 
    {
        var messages = new ArrayList<ChatMessage>();
        // A system message containing the workspace/style guide info and instructions:
        messages.add(systemMessage(quickEditIntro(), styleGuide));
        // Example quick-edit usage (optional but mirrors how DefaultPrompts includes examples):
        messages.addAll(exampleMessages());
        messages.addAll(contentMessages(fileContents, relatedCode));

        return messages;
    }

    private List<ChatMessage> contentMessages(String fileContents, ContextFragment.SkeletonFragment relatedCode) {
        var um = new UserMessage("""
        Here is a summary of related code that you may need to reference:
        %s
        
        Here is the source file you are editing:
        <source>
        ```
        %s
        ```
        </source>
        """.stripIndent().formatted(formatRelatedCode(relatedCode), fileContents));

        return List.of(um, new AiMessage("I will update the target code in the source file to implement your instructions."));
    }

    private String formatRelatedCode(ContextFragment.SkeletonFragment relatedCode) {
        return relatedCode.format();
    }

    public String formatInstructions(String target, String instructions) {
        return """        
        You are replacing only the following code in the source file:
        <target>
        ```
        %s
        ```
        </target>
        <goal>
        %s
        </goal>
        Think about how to implement the goal, then return the replacement code fenced with triple backticks. Omit language or other markdown options.
        You MUST include the backtick fences, even if you want to replace the entire code with the empty string.
        """.stripIndent().formatted(target, instructions);
    }

    /**
     * Formats an intro with a short system-level prompt plus workspace/style guide details.
     */
    private SystemMessage systemMessage(String instructions, String styleGuide) {
        assert styleGuide != null;
        var text = """
          <instructions>
          %s
          </instructions>
          <style_guide>
          %s
          </style_guide>
          """.stripIndent().formatted(instructions, styleGuide);
        return new SystemMessage(text);
    }

    /**
     * The core system instructions, analogous to DefaultPrompts.systemIntro(),
     * but for quick edits (no search/replace).
     */
    public String quickEditIntro() {
        return """
                Act as an expert software developer performing a QUICK EDIT of user-provided text.
                You will receive the original source file and the code to replace;
                you must output the REPLACEMENT CODE fenced in triple backticks.
                Always apply any relevant best practices or style guidelines to the snippet.
                """.stripIndent();
    }

    public String fullReplaceIntro() {
        return """
                Act as an expert software developer.
                You will receive a source file and other relevant context;
                you must output the REPLACEMENT CODE for the ENTIRE FILE, fenced in triple backticks.
                Always apply any relevant best practices or style guidelines to the snippet.
                """.stripIndent();
    }
    /**
     * Example conversation that demonstrates how the quick edit should be returned.
     */
    public List<ChatMessage> exampleMessages()
    {
        var userTxt = formatInstructions("""
        public int factorial(int n) {
            if (n <= 1) {
                return 1;
            }
            return n * factorial(n - 1);
        }
        """, "Replace the recursion with a simple loop");
        var aiTxt = """
        Sure! Here is the revised target code, using recursion:
        ```
        public int factorial(int n) {
            int result = 1;
            for (int i = 1; i <= n; i++) {
                result *= i;
            }
            return result;
        }
        ```
        """.stripIndent();
        return List.of(new UserMessage(userTxt), new AiMessage(aiTxt));
    }
}
