package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingWorker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class DescribePasteWorker extends SwingWorker<DescribePasteWorker.PasteInfo, Void> {
    private static final Logger logger = LogManager.getLogger(DescribePasteWorker.class);

    private final IContextManager cm;
    private final String content;

    @Nullable
    private String resultSummary;

    @Nullable
    private String resultSyntaxStyle;

    public record PasteInfo(String description, String syntaxStyle) {}

    public DescribePasteWorker(IContextManager cm, String content) {
        this.cm = cm;
        this.content = content;
    }

    @Tool("Describes the pasted text content and identifies its syntax style.")
    public void describePasteContents(
            @P("A brief summary of the text content in 12 words or fewer.") @Nullable String summary,
            @P("The syntax style of the text content.") @Nullable String syntaxStyle) {
        this.resultSummary = removeTrailingDot(summary);
        this.resultSyntaxStyle = syntaxStyle;
    }

    @Override
    protected PasteInfo doInBackground() throws Exception {
        try {
            var syntaxStyles = new ArrayList<String>();
            for (Field field : SyntaxConstants.class.getDeclaredFields()) {
                if (field.getName().startsWith("SYNTAX_STYLE_")) {
                    syntaxStyles.add((String) field.get(null));
                }
            }

            var toolSpec = ToolSpecifications.toolSpecificationsFrom(this).get(0);

            var toolContext = new ToolContext(List.of(toolSpec), ToolChoice.REQUIRED, this);

            var messages = new ArrayList<ChatMessage>();
            var prompt =
                    "Describe the following content in 12 words or fewer, and identify its syntax style by calling the 'describePasteContents' tool. "
                            + "The syntaxStyle parameter must be one of the following values: "
                            + String.join(", ", syntaxStyles)
                            + ".\n\n"
                            + "Content:\n\n"
                            + content;
            messages.add(new UserMessage(prompt));

            var llm = cm.getLlm(cm.getService().quickestModel(), "Describe pasted text");
            var toolRegistry = cm.getToolRegistry();

            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                var result = llm.sendRequest(messages, toolContext, false);

                if (result.error() != null) {
                    throw new Exception("LLM error while describing paste", result.error());
                }
                if (result.toolRequests().isEmpty()) {
                    messages.add(result.aiMessage());
                    messages.add(new UserMessage(
                            "You did not call the tool. Please call the 'describePasteContents' tool."));
                    continue;
                }

                // Execute tool calls, which will populate instance fields
                try {
                    for (var request : result.toolRequests()) {
                        toolRegistry.executeTool(this, request);
                    }
                } catch (Exception e) {
                    messages.add(result.aiMessage());
                    messages.add(new UserMessage(
                            "There was an error executing your tool call. Please try again. " + e.getMessage()));
                    continue; // retry
                }

                // Check results stored in fields
                var summary = resultSummary;
                var syntaxStyle = resultSyntaxStyle;

                if (summary != null && syntaxStyle != null && syntaxStyles.contains(syntaxStyle)) {
                    return new PasteInfo(summary, syntaxStyle);
                }

                // If we are here, the result was not valid. Provide feedback and retry.
                messages.add(result.aiMessage());
                if (syntaxStyle != null && !syntaxStyles.contains(syntaxStyle)) {
                    messages.add(new UserMessage(
                            "Invalid syntax style '" + syntaxStyle + "'. Please choose from the provided list."));
                } else {
                    // This case handles nulls or other unexpected issues
                    messages.add(new UserMessage(
                            "Tool call did not provide valid summary and syntaxStyle. Please try again."));
                }
                // Reset fields for the next attempt
                resultSummary = null;
                resultSyntaxStyle = null;
            }
            logger.warn("Failed to get a valid description and syntax style from LLM after {} attempts.", maxAttempts);
        } catch (Exception e) {
            logger.warn("Pasted text summarization failed.", e);
        }
        return new PasteInfo(
                resultSummary != null ? resultSummary : "Summarization failed.",
                resultSyntaxStyle != null ? resultSyntaxStyle : SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    }

    @Override
    protected void done() {
        cm.getIo().postSummarize();
    }

    private @Nullable String removeTrailingDot(@Nullable String summary) {
        if (summary != null && summary.endsWith(".")) {
            return summary.substring(0, summary.length() - 1);
        }
        return summary;
    }
}
