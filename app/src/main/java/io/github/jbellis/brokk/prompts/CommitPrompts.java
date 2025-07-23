package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IProject;

import java.util.List;

public class CommitPrompts {
    public static final CommitPrompts instance = new CommitPrompts() {};
    
    private CommitPrompts() {}

    public List<ChatMessage> collectMessages(IProject project, String diffTxt) {
        if (diffTxt.isEmpty()) {
            return List.of();
        }

        var formatInstructions = project.getCommitMessageFormat();
        
        var context = """
        <diff>
        %s
        </diff>
        """.stripIndent().formatted(diffTxt);

        var instructions = """
        <goal>
        Here is my diff, please give me a concise commit message based on the format instructions provided in the system prompt.
        </goal>
        """.stripIndent();
        return List.of(new SystemMessage(systemIntro(formatInstructions)),
                       new UserMessage(context + "\n\n" + instructions));
    }

    private String systemIntro(String formatInstructions) {
        return """
               You are an expert software engineer that generates concise,
               one-line Git commit messages based on the provided diffs.
               Review the provided context and diffs which are about to be committed to a git repo.
               Review the diffs carefully.
               Generate a one-line commit message for those changes, following the format instructions below.
               %s

               Ensure the commit message:
               - Follows the specified format.
               - Is in the imperative mood (e.g., "Add feature" not "Added feature" or "Adding feature").
               - Does not exceed 72 characters.
               
               Additionally, if a single file is changed be sure to include the short filename (not the path, not the extension).
               
               Reply only with the one-line commit message, without any additional text, explanations,
               or line breaks.
               """.formatted(formatInstructions);
    }
}
