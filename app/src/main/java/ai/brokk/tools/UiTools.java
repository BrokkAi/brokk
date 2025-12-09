package ai.brokk.tools;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.AskHumanDialog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** GUI-scoped tools available when Chrome (GUI) is present. Registered during agent initialization when GUI exists. */
public final class UiTools {

    private static final Logger logger = LogManager.getLogger(UiTools.class);

    private final Chrome chrome;

    public UiTools(Chrome chrome) {
        this.chrome = chrome;
    }

    @Tool(
            """
Ask a human for clarification or missing information. Use this tool when you need input to proceed.

For free-form questions: Provide only the 'question' parameter. The human will type their response.

For multiple-choice questions: Provide the 'question' parameter and a non-empty 'choices' list. The human will select one option via radio buttons. This is more efficient than free-form input when the set of acceptable answers is known.

This tool does not generate code; it only gathers human input.
""")
    public String askHuman(
            @P(
                            "A clear, concise question for the human. Do not include code to implement; ask only for information you need.")
                    String question,
            @P(
                            "Optional list of choices for a multiple-choice question. If provided and non-empty, the human selects from these options. If null or empty, the human provides free-form text input.")
                    @Nullable
                    List<String> choices) {

        String answer;
        if (choices != null && !choices.isEmpty()) {
            answer = AskHumanDialog.askWithChoices(chrome, question, choices);
        } else {
            answer = AskHumanDialog.ask(chrome, question);
        }

        if (answer == null) {
            logger.debug("askHuman canceled or dialog closed by user");
            return "";
        }
        var trimmed = answer.trim();
        if (trimmed.isEmpty()) {
            logger.debug("askHuman received empty input");
            return "";
        }
        logger.debug("askHuman received input ({} chars)", trimmed.length());
        return trimmed;
    }
}
