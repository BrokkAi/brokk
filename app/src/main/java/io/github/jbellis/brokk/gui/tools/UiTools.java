package io.github.jbellis.brokk.gui.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.AskHumanDialog;

/**
 * GUI-scoped tools available when Chrome (GUI) is present.
 * Registered during agent initialization when GUI exists.
 */
public final class UiTools {

    private final Chrome chrome;

    public UiTools(Chrome chrome) {
        this.chrome = chrome;
    }

    @Tool("""
Ask a human for clarification or missing information. Use this sparingly when you are unsure and need input to proceed. This tool does not generate code.
""")
    public String askHuman(
            @P("A clear, concise question for the human. Do not include code to implement; ask only for information you need.")
            String question) {

        var answer = AskHumanDialog.ask(chrome, question);
        if (answer == null || answer.isBlank()) {
            return "No human input provided (canceled or empty). Proceeding without it.";
        }
        return answer;
    }
}
