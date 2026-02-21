package ai.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * Reads judge reasonings and calls an LLM to summarize them into themes for
 * presentation (e.g. to a product lead). Reasonings can come from either a
 * reasonings-only JSON (from RmShadingEvalAggregate --write-reasonings) or
 * directly from corpus-with-judgments.json so you don't need to re-run Judge
 * or export a separate file.
 */
@NullMarked
@CommandLine.Command(
        name = "RmShadingEvalSummarizeReasonings",
        mixinStandardHelpOptions = true,
        description = "Summarizes judge reasonings (A/B/TIE) into themes using an LLM; writes markdown")
public class RmShadingEvalSummarizeReasonings implements Callable<Integer> {

    private static final String SUMMARIZE_PROMPT_PREFIX =
            """
            You are summarizing the reasons an LLM judge gave for preferring one of two code-diff representations for reviewers:
            - Version A = full unified diff
            - Version B = RM-shaded diff (refactorings removed and replaced with a short summary)

            Below are the judge's reasoning strings, grouped by which version won (A, B, or TIE). Produce a concise structured summary (about 1-2 pages) suitable for a product/engineering lead. Use clear headings and bullet points.

            Include:
            1. **Overall takeaway** (2-3 sentences): What does this eval suggest about when full diff vs RM-shaded is more useful?
            2. **Why A (vanilla) was preferred**: Main recurring themes (e.g. "refactorings mixed with behavior changes", "need full context").
            3. **Why B (RM-shaded) was preferred** (if any): Main themes.
            4. **Why TIE** (if any): When did the judge find them equally useful?
            5. **Caveats or patterns**: e.g. by refactoring count, diff size, or repo type.

            Output valid markdown only (no preamble like "Here is the summary").

            ---

            """;

    @CommandLine.Option(
            names = {"--reasonings"},
            description = "Path to reasonings.json (from RmShadingEvalAggregate --write-reasonings)")
    private @Nullable Path reasoningsPath;

    @CommandLine.Option(
            names = {"--corpus-with-judgments"},
            description = "Path to corpus-with-judgments.json (extract reasonings from judge output; no separate reasonings file needed)")
    private @Nullable Path corpusWithJudgmentsPath;

    @CommandLine.Option(
            names = {"--output"},
            description = "Output markdown file (default: <dir of input>/reasoning-summary.md)")
    private @Nullable Path outputPath;

    @CommandLine.Option(
            names = {"--openai-api-key"},
            description = "OpenAI API key; or set OPENAI_API_KEY env")
    private @Nullable String openaiApiKey;

    @CommandLine.Option(
            names = {"--openai-model"},
            description = "Model for summarization (default: gpt-4o-mini)")
    private String openaiModel = "gpt-5.2";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RmShadingEvalSummarizeReasonings()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        boolean useReasonings = reasoningsPath != null && !reasoningsPath.toString().isBlank();
        boolean useCorpus = corpusWithJudgmentsPath != null && !corpusWithJudgmentsPath.toString().isBlank();
        if (useReasonings == useCorpus) {
            System.err.println("Error: Pass exactly one of --reasonings or --corpus-with-judgments");
            return 1;
        }

        Path inputPath = useReasonings ? reasoningsPath : corpusWithJudgmentsPath;
        if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
            System.err.println("Error: Input file not found: " + inputPath);
            return 1;
        }

        String apiKey = openaiApiKey != null ? openaiApiKey : System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: Set OPENAI_API_KEY or pass --openai-api-key");
            return 1;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<ReasoningRow> rows;
        if (useReasonings) {
            rows = mapper.readValue(
                    Files.readString(inputPath),
                    new TypeReference<List<ReasoningRow>>() {});
        } else {
            List<RmShadingEvalRunner.RmShadingCorpusItem> items = mapper.readValue(
                    Files.readString(inputPath),
                    new TypeReference<List<RmShadingEvalRunner.RmShadingCorpusItem>>() {});
            rows = new ArrayList<>();
            for (var item : items) {
                rows.add(new ReasoningRow(
                        item.id(),
                        item.winner() != null ? item.winner() : "",
                        item.reasoning() != null ? item.reasoning() : ""));
            }
        }

        if (rows.isEmpty()) {
            System.err.println("Error: No entries in reasonings file.");
            return 1;
        }

        List<String> aReasons = new ArrayList<>();
        List<String> bReasons = new ArrayList<>();
        List<String> tieReasons = new ArrayList<>();
        for (ReasoningRow r : rows) {
            if (r.reasoning == null || r.reasoning.isBlank()) continue;
            switch (r.winner != null ? r.winner.toUpperCase() : "") {
                case "A" -> aReasons.add(r.reasoning.trim());
                case "B" -> bReasons.add(r.reasoning.trim());
                case "TIE" -> tieReasons.add(r.reasoning.trim());
                default -> {}
            }
        }

        StringBuilder body = new StringBuilder();
        body.append("## Prefer A (vanilla) – ").append(aReasons.size()).append(" reasonings\n\n");
        for (int i = 0; i < aReasons.size(); i++) {
            body.append(i + 1).append(". ").append(aReasons.get(i)).append("\n\n");
        }
        body.append("## Prefer B (RM-shaded) – ").append(bReasons.size()).append(" reasonings\n\n");
        for (int i = 0; i < bReasons.size(); i++) {
            body.append(i + 1).append(". ").append(bReasons.get(i)).append("\n\n");
        }
        body.append("## TIE – ").append(tieReasons.size()).append(" reasonings\n\n");
        for (int i = 0; i < tieReasons.size(); i++) {
            body.append(i + 1).append(". ").append(tieReasons.get(i)).append("\n\n");
        }

        String prompt = SUMMARIZE_PROMPT_PREFIX + body;
        if (prompt.length() > 120_000) {
            System.err.println("Warning: Prompt is very long (" + prompt.length() + " chars); truncating to ~120k.");
            prompt = prompt.substring(0, 120_000) + "\n\n... (truncated)";
        }

        System.out.println("Calling " + openaiModel + " to summarize " + rows.size() + " reasonings (A=" + aReasons.size() + " B=" + bReasons.size() + " TIE=" + tieReasons.size() + ") ...");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        ResponseCreateParams params = ResponseCreateParams.builder()
                .input(prompt)
                .model(openaiModel)
                .build();
        Response response = client.responses().create(params);
        String summary = extractOutputText(response);

        if (summary.isBlank()) {
            System.err.println("Error: Empty summary from model.");
            return 1;
        }

        Path out = outputPath != null ? outputPath : inputPath.getParent().resolve("reasoning-summary.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, summary);
        System.out.println("Wrote summary to: " + out);
        return 0;
    }

    private static String extractOutputText(Response response) {
        if (response == null || response.output() == null) return "";
        return response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(msg -> msg.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(ot -> ot.text())
                .collect(Collectors.joining());
    }

    private record ReasoningRow(String id, String winner, String reasoning) {}
}
