package ai.brokk.gui.wand;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.MutedConsoleIO;
import ai.brokk.agents.ContextAgent;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.dialogs.TextAreaConsoleIO;
import ai.brokk.project.ModelProperties;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class WandAction {
    private final ContextManager contextManager;

    public WandAction(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public void execute(
            Supplier<String> promptSupplier,
            Consumer<String> promptConsumer,
            IConsoleIO chromeIO,
            JTextArea instructionsArea) {
        var original = promptSupplier.get();
        if (original.isBlank()) {
            chromeIO.toolError("Please enter a prompt to refine");
            return;
        }

        contextManager.submitLlmAction(() -> {
            try {
                var wandIo = new TextAreaConsoleIO(instructionsArea, chromeIO, "Enriching Context");

                var model = contextManager.getCodeModel();
                ContextAgent agent = new ContextAgent(contextManager, model, original, new MutedConsoleIO(chromeIO));
                Context context = contextManager.liveContext();
                ContextAgent.RecommendationResult recommendation = agent.getRecommendations(context);

                if (recommendation.success() && !recommendation.fragments().isEmpty()) {
                    context = contextManager.pushContext(c -> c.addFragments(recommendation.fragments()));
                }

                SwingUtilities.invokeLater(() -> {
                    instructionsArea.setText("");
                    wandIo.llmOutput(
                            "Generating New Prompt...\n", ChatMessageType.AI, new LlmOutputMeta(true, false, false));
                });

                @Nullable String refined = refinePrompt(original, context, wandIo);

                if (refined == null) { // error case
                    SwingUtilities.invokeLater(() -> promptConsumer.accept(original));
                    return;
                }

                if (!refined.isBlank()) {
                    SwingUtilities.invokeLater(() -> promptConsumer.accept(refined));
                } else {
                    // Blank refinement - restore original text (also re-enables undo listener)
                    SwingUtilities.invokeLater(() -> promptConsumer.accept(original));
                }
            } catch (InterruptedException e) {
                SwingUtilities.invokeLater(() -> promptConsumer.accept(original));
            }
        });
    }

    public @Nullable String refinePrompt(String originalPrompt, Context ctx, IConsoleIO consoleIO)
            throws InterruptedException {
        var model = contextManager.getService().getModel(ModelProperties.ModelType.SCAN);
        var analyzer = contextManager.getAnalyzer();

        var workspaceSummary = ctx.allFragments()
                .map(f -> {
                    String description = f.description().join();
                    StringBuilder sb =
                            new StringBuilder("# ").append(description).append("\n");

                    switch (f) {
                        case ContextFragments.ProjectPathFragment pf -> sb.append(analyzer.summarizeSymbols(pf.file()));
                        case ContextFragments.SummaryFragment sf -> {
                            if (sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS) {
                                var file = contextManager.toFile(sf.getTargetIdentifier());
                                sb.append(analyzer.summarizeSymbols(file));
                            } else {
                                var units = analyzer.getDefinitions(sf.getTargetIdentifier());
                                if (!units.isEmpty()) {
                                    sb.append(analyzer.summarizeSymbols(units, CodeUnitType.ALL, 0));
                                }
                            }
                        }
                        case ContextFragments.CodeFragment cf -> {
                            var units = analyzer.getDefinitions(cf.getFullyQualifiedName());
                            if (!units.isEmpty()) {
                                sb.append(analyzer.summarizeSymbols(units, CodeUnitType.ALL, 0));
                            }
                        }
                        default -> {}
                    }
                    return sb.toString().trim();
                })
                .collect(Collectors.joining("\n\n"));

        String instruction =
                """
                <workspace_summary>
                %s
                </workspace_summary>

                <history_sumary>
                %s
                </history_sumary>

                <draft_prompt>
                %s
                </draft_prompt>

                <goal>
                Take the draft prompt and rewrite it so it is clear, concise, and well-structured.
                You may leverage information from the Workspace, but do not speculate beyond what you know for sure.

                Output only the improved prompt in 2-4 paragraphs.
                </goal>
                """
                        .formatted(workspaceSummary, buildHistorySummary(ctx), originalPrompt);

        Llm llm = contextManager.getLlm(new Llm.Options(model, "Refine Prompt").withEcho());
        llm.setOutput(consoleIO);
        List<ChatMessage> req = List.of(
                new SystemMessage("You are a Prompt Refiner for coding instructions."), new UserMessage(instruction));
        Llm.StreamingResult res = llm.sendRequest(req);

        if (res.error() != null) {
            return null; // indicate error
        }

        return sanitize(res.text());
    }

    private String buildHistorySummary(Context ctx) {
        return ctx.getTaskHistory().stream()
                .map(entry -> {
                    if (entry.summary() != null) {
                        return entry.summary();
                    }
                    if (entry.log() != null) {
                        var messages = entry.log().messages();
                        if (!messages.isEmpty()) {
                            return "User: " + Messages.getText(messages.getFirst());
                        }
                    }
                    throw new IllegalStateException("No summary or messages found for task entry");
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private String sanitize(String refined) {
        refined = refined.trim();
        if (refined.startsWith("```")) {
            int start = refined.indexOf('\n');
            int endFence = refined.lastIndexOf("```");
            if (start >= 0 && endFence > start) {
                refined = refined.substring(start + 1, endFence).trim();
            } else {
                refined = refined.replace("```", "").trim();
            }
        }
        var lowered = refined.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("improved prompt:")) {
            int idx = refined.indexOf(':');
            if (idx >= 0 && idx + 1 < refined.length()) {
                refined = refined.substring(idx + 1).trim();
            }
        }
        return refined;
    }
}
