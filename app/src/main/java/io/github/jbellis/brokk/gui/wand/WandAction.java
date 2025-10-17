package io.github.jbellis.brokk.gui.wand;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.context.ContextFragment;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
                var wandIo = new WandConsoleIO(instructionsArea, chromeIO);
                @Nullable String refined = refinePrompt(original, wandIo);

                if (refined == null) { // error case
                    SwingUtilities.invokeLater(() -> promptConsumer.accept(original));
                    return;
                }

                if (!refined.isBlank()) {
                    SwingUtilities.invokeLater(() -> promptConsumer.accept(refined));
                } else {
                    SwingUtilities.invokeLater(() -> {
                        instructionsArea.setEnabled(true);
                        instructionsArea.requestFocusInWindow();
                    });
                }
            } catch (InterruptedException e) {
                SwingUtilities.invokeLater(() -> promptConsumer.accept(original));
            }
        });
    }

    public @Nullable String refinePrompt(String originalPrompt, IConsoleIO consoleIO) throws InterruptedException {
        var model = contextManager.getCodeModel();

        String instruction =
                """
                <workspace_summary>
                %s
                </workspace_summary>

                <draft_prompt>>
                %s
                </draft_prompt>

                <goal>
                Take the draft prompt and rewrite it so it is clear, concise, and well-structured.
                You may leverage information from the Workspace, but do not speculate beyond what you know for sure.

                Output only the improved prompt in 2-4 paragraphs.
                </goal>
                """
                        .formatted(
                                ContextFragment.getSummary(
                                        contextManager.topContext().allFragments()),
                                originalPrompt);

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

    public static class WandConsoleIO implements IConsoleIO {
        private final JTextArea instructionsArea;
        private final IConsoleIO errorReporter;
        private boolean hasStartedContent = false;
        private boolean lastWasReasoning = true;

        public WandConsoleIO(JTextArea instructionsArea, IConsoleIO errorReporter) {
            this.instructionsArea = instructionsArea;
            this.errorReporter = errorReporter;
            SwingUtilities.invokeLater(() -> {
                instructionsArea.setEnabled(false);
                instructionsArea.setText("Improving your prompt...\n\n");
                instructionsArea.setCaretPosition(instructionsArea.getText().length());
            });
        }

        @Override
        public void llmOutput(
                String token,
                dev.langchain4j.data.message.ChatMessageType type,
                boolean isNewMessage,
                boolean isReasoning) {
            if (!isReasoning && lastWasReasoning && !hasStartedContent) {
                // Transition from reasoning to content: clear the area first
                SwingUtilities.invokeLater(() -> instructionsArea.setText(""));
                hasStartedContent = true;
            } else if (isReasoning && !lastWasReasoning) {
                // Illegal transition back to reasoning
                throw new IllegalStateException("Wand stream switched from non-reasoning to reasoning");
            }

            if (!token.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    instructionsArea.append(token);
                    instructionsArea.setCaretPosition(instructionsArea.getText().length());
                });
            }
            lastWasReasoning = isReasoning;
        }

        @Override
        public void toolError(String message, String title) {
            errorReporter.toolError(message, title);
        }

        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}
