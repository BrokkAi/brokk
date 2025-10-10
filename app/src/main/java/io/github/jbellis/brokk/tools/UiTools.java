package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.AskHumanDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** GUI-scoped tools available when Chrome (GUI) is present. Registered during agent initialization when GUI exists. */
public final class UiTools {

    private static final Logger logger = LogManager.getLogger(UiTools.class);

    private final Chrome chrome;

    public UiTools(Chrome chrome) {
        this.chrome = chrome;
    }

    @Tool(
            """
Ask a human for clarification or missing information. Use this sparingly when you are unsure and need input to proceed. This tool does not generate code.
""")
    public String askHuman(
            @P(
                            "A clear, concise question for the human. Do not include code to implement; ask only for information you need.")
                    String question) {

        var answer = AskHumanDialog.ask(chrome, question);
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
