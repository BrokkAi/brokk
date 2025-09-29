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
        var service = contextManager.getService();
        var model = service.getWandModel();

        String instruction =
                """
                <workspace_summary>
                %s
                </workspace_summary>

                <draft_prompt>>
                %s
                </draft_prompt>

                <goal>
                Take the draft prompt and rewrite it so it is:
                - Clear
                - Concise
                - Structured
                - Leverages information from the Workspace but without speculating beyond what you know for sure

                Output only the improved prompt.
                </goal>
                """
                        .formatted(
                                ContextFragment.getSummary(
                                        contextManager.topContext().allFragments()),
                                originalPrompt);

        Llm llm = contextManager.getLlm(model, "Refine Prompt", false);
        llm.setOutput(consoleIO);
        List<ChatMessage> req = List.of(
                new SystemMessage("You are a Prompt Refiner for coding instructions."), new UserMessage(instruction));
        Llm.StreamingResult res = llm.sendRequest(req, true);

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
}
