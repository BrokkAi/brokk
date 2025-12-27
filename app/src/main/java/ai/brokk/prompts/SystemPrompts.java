package ai.brokk.prompts;

import dev.langchain4j.data.message.SystemMessage;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public abstract class SystemPrompts {
    public static final String LAZY_REMINDER =
            """
                    You are diligent and tireless!
                    You NEVER leave comments describing code without implementing it!
                    You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
                    """;
    public static final String OVEREAGER_REMINDER =
            """
                    Avoid changing code or comments that are not directly related to the request.

                    Do not comment on your modifications, only on the resulting code in isolation.
                    You must never output any comments about the progress or type of changes of your refactoring or generation.
                    For example, you must NOT add comments like: 'Added dependency' or 'Changed to new style' or worst of all 'Keeping existing implementation'.
                    """;
    public static final String ARCHITECT_REMINDER =
            """
                    Pay careful attention to the scope of the user's request. Attempt to do everything required
                    to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
                    For example, if the user asks you a question, you should do your best to answer his question first,
                    before immediately jumping into taking further action.
                    """;
    public static final String MARKDOWN_REMINDER =
            """
                    <persistence>
                    ## Markdown Formatting
                    When not writing SEARCH/REPLACE blocks,
                    format your response using GFM Markdown to **improve the readability** of your responses with:
                    - **bold**
                    - _italics_
                    - `inline code` (for file, directory, function, class names, and other symbols)
                    - ```code fences``` for code and pseudocode
                    - list
                    - prefer GFM tables over bulleted lists
                    - header tags (start from ##).
                    </persistence>
                    """;

    public String askReminder() {
        return MARKDOWN_REMINDER;
    }

    @Blocking
    public SystemMessage systemMessage(String reminder, @Nullable String goal) {
        final String text;
        if (goal == null || goal.isBlank()) {
            text =
                    """
                            <instructions>
                            %s
                            </instructions>
                            """
                            .formatted(systemIntro(reminder))
                            .trim();
        } else {
            text =
                    """
                            <instructions>
                            %s
                            </instructions>
                            <goal>
                            %s
                            </goal>
                            """
                            .formatted(systemIntro(reminder), goal)
                            .trim();
        }

        return new SystemMessage(text);
    }

    @Blocking
    protected final SystemMessage systemMessage(String reminder) {
        return systemMessage(reminder, null);
    }

    protected abstract String systemIntro(String reminder);
}
