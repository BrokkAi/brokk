package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SummarizerPrompts {
    public static final SummarizerPrompts instance = new SummarizerPrompts() {};
    
    public static final int WORD_BUDGET_5 = 5; 
    public static final int WORD_BUDGET_12 = 12; 
    
    private SummarizerPrompts() {}

    public List<ChatMessage> collectMessages(String actionTxt, int wordBudget) {
        assert actionTxt != null;
        assert !actionTxt.isBlank();
        assert wordBudget == WORD_BUDGET_5 || wordBudget == WORD_BUDGET_12 : wordBudget;

        var example = """
        # What Brokk can do
        
        1. Ridiculously good agentic search / code retrieval. Better than Claude Code, better than Sourcegraph,
           better than Augment Code.  Here are
           [Brokk's explanation of "how does bm25 search work?"](https://gist.github.com/jbellis/c2696f58f22a1c1a2aa450fdf45c21f4)
           in the [DataStax Cassandra repo](https://github.com/datastax/cassandra/) (a brand-new feature, not in anyone's training set), starting cold
           with no context, compared to\s
           [Claude Code's (probably the second-best code RAG out there)](https://github.com/user-attachments/assets/3f77ea58-9fe3-4eab-8698-ec4e20cf1974).  \s
        1. Automatically determine the most-related classes to your working context and summarize them
        1. Parse a stacktrace and add source for all the methods to your context
        1. Add source for all the usages of a class, field, or method to your context
        1. Parse "anonymous" context pieces from external commands
        1. Build/lint your project and ask the LLM to fix errors autonomously
        
        These allow some simple but powerful patterns:
        - "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
          of the error and the full source of the methods involved.  Find the bug."
        - "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"
        """;
        var exampleRequest = getRequest(example, wordBudget);
        var exampleResponse = wordBudget == WORD_BUDGET_12
                ? "Brokk: agentic code search and retrieval, usage summarization, stacktrace parsing, build integration"
                : "Brokk: context management, agentic search";

        var request = getRequest(actionTxt, wordBudget);
        return List.of(new SystemMessage(systemIntro()),
                       new UserMessage(exampleRequest),
                       new AiMessage(exampleResponse),
                       new UserMessage(request));
    }

    private static @NotNull String getRequest(String actionTxt, int wordBudget) {
        return """
        <text>
        %s
        </text>
        <goal>
        Summarize the text in %d words or fewer.
        </goal>
        """.stripIndent().formatted(actionTxt, wordBudget);
    }

    public String systemIntro() {
        return """
               You are an expert software engineer that generates concise summaries of code-related text.

               Reply only with the summary, without any additional text, explanations, or line breaks.
               """.stripIndent();
    }

    public List<ChatMessage> compressHistory(String entryText) {
        var instructions = """
        Give a detailed but concise summary of this task.
        A third party should be able to understand what happened without reference to the original.
        Focus on information that would be useful for someone doing further work on the project described in the task.

        Here is the task to summarize. Do not include XML tags or other markup.
        %s
        """.stripIndent().formatted(entryText);
        return List.of(new SystemMessage(systemIntro()), new UserMessage(instructions));
    }
}
