package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.project.ModelProperties;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.util.stream.Collectors;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

/**
 * Phase 2 of the RM-shading eval: loads the diff corpus, asks an LLM which
 * representation (vanilla vs RM-shaded) is more useful for a reviewer, and
 * writes judgments plus summary stats.
 */
@NullMarked
@CommandLine.Command(
        name = "RmShadingEvalJudge",
        mixinStandardHelpOptions = true,
        description = "Runs LLM judge on corpus to compare vanilla vs RM-shaded diff usefulness")
public class RmShadingEvalJudge implements Callable<Integer> {

    private static final String JUDGE_SYSTEM_PROMPT =
            """
            You are evaluating two representations of the same code change for a human code reviewer.
            Version A is the full unified diff.
            Version B is a diff with detected refactorings removed and replaced by a short summary.

            Which representation is more useful for a reviewer to understand the change and spot real issues?
            Try to be objective and balanced in your interepretation of the diffs.

            Answer with exactly one line: WINNER: A | B | TIE
            Then on the next line(s), give a short reason (one to three sentences).
            """;

    private static final Pattern WINNER_PATTERN =
            Pattern.compile("WINNER\\s*:\\s*(A|B|TIE)", Pattern.CASE_INSENSITIVE);

    @CommandLine.Option(
            names = {"--corpus"},
            required = true,
            description = "Path to corpus.json from RmShadingEvalRunner")
    private Path corpusPath;

    @CommandLine.Option(
            names = {"--output"},
            description = "Output directory (default: same as corpus directory)")
    private Path output;

    @CommandLine.Option(
            names = {"--project-dir"},
            description = "Project root for Brokk config and LLM API (default: current directory)")
    private Path projectDir = Path.of(".").toAbsolutePath().normalize();

    @CommandLine.Option(
            names = {"--max-diff-lines"},
            description = "Max lines per diff version to send to the model (default: 3500)")
    private int maxDiffLines = 3500;

    @CommandLine.Option(
            names = {"--openai-api-key"},
            description = "Use OpenAI API directly (e.g. for GPT-5.2). If not set, uses OPENAI_API_KEY env. When set, --project-dir is not required.")
    private @Nullable String openaiApiKey;

    @CommandLine.Option(
            names = {"--openai-model"},
            description = "OpenAI model when using --openai-api-key (default: gpt-5.2)")
    private String openaiModel = "gpt-5.2";

    @CommandLine.Option(
            names = {"--limit"},
            description = "Process only the first N corpus items (default: 0 = all). Use 1 to test a single item.")
    private int limit = 0;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RmShadingEvalJudge()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Path resolvedCorpus = corpusPath.isAbsolute() ? corpusPath : corpusPath.toAbsolutePath().normalize();
        if (!Files.exists(resolvedCorpus) || !Files.isRegularFile(resolvedCorpus)) {
            System.err.println("Error: --corpus file not found: " + resolvedCorpus);
            System.err.println("Run Phase 1 first: ./gradlew :app:runRmShadingEvalRunner -Pargs=\"--dataset ... --output build/reports/rm-shading-eval\"");
            return 1;
        }
        corpusPath = resolvedCorpus;

        if (output == null) {
            output = corpusPath.getParent();
        }
        Files.createDirectories(output);

        ObjectMapper mapper = new ObjectMapper();
        List<RmShadingEvalRunner.RmShadingCorpusItem> items = mapper.readValue(
                Files.readString(corpusPath),
                new TypeReference<List<RmShadingEvalRunner.RmShadingCorpusItem>>() {});

        if (items.isEmpty()) {
            System.err.println("Error: Corpus is empty.");
            return 1;
        }

        String resolvedOpenaiKey = openaiApiKey != null ? openaiApiKey : System.getenv("OPENAI_API_KEY");
        if (limit > 0) {
            items = items.subList(0, Math.min(limit, items.size()));
            System.out.println("Limit applied: processing first " + items.size() + " item(s).");
        }

        System.out.println("================================================================================");
        System.out.println(" RmShadingEvalJudge - LLM judgment");
        System.out.println("================================================================================");
        System.out.println(" Corpus:   " + corpusPath);
        System.out.println(" Output:   " + output);
        System.out.println(" Items:    " + items.size());
        if (resolvedOpenaiKey != null) {
            System.out.println(" Backend: OpenAI API (model=" + openaiModel + ")");
        } else {
            System.out.println(" Backend: Brokk (project-dir=" + projectDir + ")");
            System.out.println(" (For this eval, OpenAI GPT-5.2 is recommended: set OPENAI_API_KEY or use --openai-api-key)");
        }
        System.out.println("--------------------------------------------------------------------------------");

        List<RmShadingEvalRunner.RmShadingCorpusItem> judged = new ArrayList<>();
        int aWins = 0, bWins = 0, ties = 0, failed = 0;
        int total = items.size();
        long startMs = System.currentTimeMillis();

        if (resolvedOpenaiKey != null) {
            runWithOpenai(items, judged, startMs, total, resolvedOpenaiKey);
        } else {
            runWithBrokk(items, judged, startMs, total);
        }

        // Recompute counts from judged list for summary
        aWins = 0;
        bWins = 0;
        ties = 0;
        failed = 0;
        for (var j : judged) {
            String w = j.winner();
            if (w.isEmpty() || w.equalsIgnoreCase("error")) failed++;
            else if ("A".equalsIgnoreCase(w)) aWins++;
            else if ("B".equalsIgnoreCase(w)) bWins++;
            else ties++;
        }

        ObjectMapper writer = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(
                output.resolve("corpus-with-judgments.json"),
                writer.writeValueAsString(judged),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        var summary = new JudgeSummary(aWins, bWins, ties, failed, items.size());
        Files.writeString(
                output.resolve("summary.json"),
                writer.writeValueAsString(summary),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" WINNER A (vanilla):     " + aWins);
        System.out.println(" WINNER B (RM-shaded):   " + bWins);
        System.out.println(" TIE:                    " + ties);
        if (failed > 0) System.out.println(" Failed/unknown:         " + failed);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" Results: corpus-with-judgments.json, summary.json");
        System.out.println("================================================================================");

        return failed > 0 ? 1 : 0;
    }

    private void runWithOpenai(
            List<RmShadingEvalRunner.RmShadingCorpusItem> items,
            List<RmShadingEvalRunner.RmShadingCorpusItem> judged,
            long startMs,
            int total,
            String apiKey) {
        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        int index = 0;
        for (RmShadingEvalRunner.RmShadingCorpusItem item : items) {
            index++;
            String winnerDisplay = "?";
            String vanilla = truncateToLines(item.vanillaDiff(), maxDiffLines);
            String rmShaded = truncateToLines(item.rmShadedDiff(), maxDiffLines);
            String userContent =
                    """
                    Version A (full diff):
                    ```
                    %s
                    ```

                    Version B (refactorings summarized, rest of diff):
                    ```
                    %s
                    ```

                    Which is more useful for a reviewer? Reply with WINNER: A | B | TIE then your reason.
                    """
                            .formatted(vanilla, rmShaded);
            String combinedPrompt = JUDGE_SYSTEM_PROMPT + "\n\n---\n\n" + userContent;

            try {
                ResponseCreateParams params =
                        ResponseCreateParams.builder().input(combinedPrompt).model(openaiModel).build();
                Response response = client.responses().create(params);
                String text = extractOutputText(response);
                String winner = parseWinner(text);
                String reasoning = parseReasoning(text);
                if (winner != null) {
                    winnerDisplay = winner;
                } else {
                    winnerDisplay = "?";
                }
                judged.add(
                        new RmShadingEvalRunner.RmShadingCorpusItem(
                                item.id(),
                                item.fromRef(),
                                item.toRef(),
                                item.refactoringCount(),
                                item.vanillaDiff(),
                                item.rmShadedDiff(),
                                item.refactoringSummary(),
                                item.vanillaDiffLineCount(),
                                item.rmShadedDiffLineCount(),
                                item.filesChanged(),
                                winner != null ? winner : "",
                                reasoning != null ? reasoning : ""));
            } catch (Exception e) {
                System.err.printf("[%s] OpenAI error: %s%n", item.id(), e.getMessage());
                judged.add(item);
                winnerDisplay = "error";
            }
            printProgress(index, total, item.id(), winnerDisplay, startMs);
        }
    }

    private void runWithBrokk(
            List<RmShadingEvalRunner.RmShadingCorpusItem> items,
            List<RmShadingEvalRunner.RmShadingCorpusItem> judged,
            long startMs,
            int total) {
        try (MainProject project = new MainProject(projectDir);
                ContextManager cm = new ContextManager(project)) {
            var model = cm.getService().getModel(project.getModelConfig(ModelProperties.ModelType.ARCHITECT));
            var llm = cm.getLlm(new Llm.Options(model, "RM-shading eval judge", TaskResult.Type.REVIEW));
            int index = 0;
            for (RmShadingEvalRunner.RmShadingCorpusItem item : items) {
                index++;
                String winnerDisplay = "?";
                String vanilla = truncateToLines(item.vanillaDiff(), maxDiffLines);
                String rmShaded = truncateToLines(item.rmShadedDiff(), maxDiffLines);
                String userContent =
                        """
                        Version A (full diff):
                        ```
                        %s
                        ```

                        Version B (refactorings summarized, rest of diff):
                        ```
                        %s
                        ```

                        Which is more useful for a reviewer? Reply with WINNER: A | B | TIE then your reason.
                        """
                                .formatted(vanilla, rmShaded);
                List<ChatMessage> messages =
                        List.of(new SystemMessage(JUDGE_SYSTEM_PROMPT), new UserMessage(userContent));
                try {
                    var result = llm.sendRequest(messages);
                    if (result.error() != null) {
                        System.err.printf("[%s] LLM error: %s%n", item.id(), result.error().getMessage());
                        judged.add(item);
                        winnerDisplay = "error";
                    } else {
                        String text = result.text();
                        String winner = parseWinner(text);
                        String reasoning = parseReasoning(text);
                        if (winner != null) {
                            winnerDisplay = winner;
                        } else {
                            winnerDisplay = "?";
                        }
                        judged.add(
                                new RmShadingEvalRunner.RmShadingCorpusItem(
                                        item.id(),
                                        item.fromRef(),
                                        item.toRef(),
                                        item.refactoringCount(),
                                        item.vanillaDiff(),
                                        item.rmShadedDiff(),
                                        item.refactoringSummary(),
                                        item.vanillaDiffLineCount(),
                                        item.rmShadedDiffLineCount(),
                                        item.filesChanged(),
                                        winner != null ? winner : "",
                                        reasoning != null ? reasoning : ""));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                printProgress(index, total, item.id(), winnerDisplay, startMs);
            }
        }
    }

    /** Extract concatenated text from OpenAI Responses API Response (output items -> message -> content -> outputText). */
    private static String extractOutputText(Response response) {
        if (response == null || response.output() == null) return "";
        return response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(msg -> msg.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(ot -> ot.text())
                .collect(Collectors.joining());
    }

    private void printProgress(int index, int total, String itemId, String winnerDisplay, long startMs) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        long estRemainingMs =
                index < total && elapsedMs > 0 ? Math.max(0, (elapsedMs * total) / index - elapsedMs) : 0;
        System.out.printf("[%d/%d] %s WINNER=%s", index, total, itemId, winnerDisplay);
        if (estRemainingMs > 0) {
            System.out.printf(" (est. %d min remaining)", estRemainingMs / 60_000);
        }
        System.out.println();
    }

    private static String truncateToLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) return "";
        List<String> lines = text.lines().toList();
        if (lines.size() <= maxLines) return text;
        return String.join("\n", lines.subList(0, maxLines))
                + "\n\n... (truncated "
                + (lines.size() - maxLines)
                + " more lines)";
    }

    private static String parseWinner(String text) {
        if (text == null) return null;
        Matcher m = WINNER_PATTERN.matcher(text);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    private static String parseReasoning(String text) {
        if (text == null) return null;
        int idx = text.indexOf('\n');
        if (idx < 0) return text.trim();
        return text.substring(idx).trim();
    }

    private record JudgeSummary(int winnerA, int winnerB, int tie, int failed, int total) {}
}
