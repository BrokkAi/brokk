package ai.brokk;

import ai.brokk.concurrent.LoggingFuture;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class DescribePasteWorker {
    private static final Logger logger = LogManager.getLogger(DescribePasteWorker.class);

    private final IAppContextManager cm;
    private final String content;

    @Nullable
    private String resultSummary;

    @Nullable
    private String resultSyntaxStyle;

    public record PasteInfo(String description, String syntaxStyle) {}

    public DescribePasteWorker(IAppContextManager cm, String content) {
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

    public CompletableFuture<PasteInfo> execute() {
        return LoggingFuture.supplyVirtual(() -> {
                    try {
                        var syntaxStyles = new ArrayList<String>();
                        for (var field : SyntaxConstants.class.getDeclaredFields()) {
                            if (field.getName().startsWith("SYNTAX_STYLE_")) {
                                syntaxStyles.add((String) field.get(null));
                            }
                        }

                        var tr = cm.getToolRegistry().builder().register(this).build();
                        var toolSpec =
                                ToolSpecifications.toolSpecificationsFrom(this).get(0);
                        var toolContext = new ToolContext(List.of(toolSpec), ToolChoice.REQUIRED, tr);

                        var messages = new ArrayList<ChatMessage>();
                        messages.add(new SystemMessage(
                                "Describe pasted content in 12 words or fewer and identify its syntax style "
                                        + "by calling the 'describePasteContents' tool. The syntaxStyle parameter "
                                        + "must be one of: "
                                        + String.join(", ", syntaxStyles)
                                        + "."));
                        messages.add(new UserMessage("Content:\n\n" + content));

                        var llm = cm.getLlm(
                                cm.getService().quickestModel(), "Describe pasted text", TaskResult.Type.SUMMARIZE);

                        int maxAttempts = 3;
                        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                            var result = llm.sendRequest(messages, toolContext);
                            if (result.error() != null) {
                                throw new RuntimeException("LLM error while describing paste", result.error());
                            }

                            // Execute tool calls, which will populate instance fields
                            for (var request : result.toolRequests()) {
                                tr.executeTool(request);
                            }

                            // Check results stored in fields
                            var summary = resultSummary;
                            var syntaxStyle = resultSyntaxStyle;

                            if (summary != null && syntaxStyle != null && syntaxStyles.contains(syntaxStyle)) {
                                return new PasteInfo(summary, syntaxStyle);
                            }

                            // If we are here, the result was not valid. Provide feedback and retry.
                            messages.add(result.aiMessage());
                            if (syntaxStyle == null || syntaxStyles.contains(syntaxStyle)) {
                                messages.add(new UserMessage("Invalid syntax style '" + syntaxStyle
                                        + "'. Please choose from the provided list."));
                            } else {
                                messages.add(new UserMessage(
                                        "Tool call did not provide valid summary and syntaxStyle. Please try again."));
                            }
                            // Reset fields for the next attempt
                            resultSummary = null;
                            resultSyntaxStyle = null;
                        }
                        logger.warn(
                                "Failed to get a valid description and syntax style from LLM after {} attempts.",
                                maxAttempts);
                    } catch (Exception e) {
                        logger.warn("Pasted text summarization failed.", e);
                    }
                    return new PasteInfo(
                            resultSummary != null ? resultSummary : "Summarization failed.",
                            resultSyntaxStyle != null ? resultSyntaxStyle : SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
                })
                .whenComplete((res, th) -> cm.getIo().postSummarize());
    }

    private @Nullable String removeTrailingDot(@Nullable String summary) {
        if (summary != null && summary.endsWith(".")) {
            return summary.substring(0, summary.length() - 1);
        }
        return summary;
    }
}
