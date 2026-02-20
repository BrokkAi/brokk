package ai.brokk.tools;

import ai.brokk.context.DiffService;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

/**
 * CLI that produces vanilla and RM-shaded review diffs for a dataset of commit
 * ranges and writes a diff corpus (Phase 1 of the RM-shading eval harness).
 */
@NullMarked
@CommandLine.Command(
        name = "RmShadingEvalRunner",
        mixinStandardHelpOptions = true,
        description = "Produces vanilla and RM-shaded diffs for commit ranges; writes corpus JSON (no LLM judge yet)")
public class RmShadingEvalRunner implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--project-dir"},
            description = "Project root (Git repo); default: current directory")
    private Path projectDir = Path.of(".").toAbsolutePath().normalize();

    @CommandLine.Option(
            names = {"--dataset"},
            required = true,
            description = "Path to dataset JSON file (entries with id, fromRef, toRef)")
    private Path datasetPath;

    @CommandLine.Option(
            names = {"--output"},
            description = "Output directory for corpus.json (default: build/reports/rm-shading-eval)")
    private Path output =
            Path.of("build/reports/rm-shading-eval").toAbsolutePath().normalize();

    @CommandLine.Option(
            names = {"--limit"},
            description = "Process only the first N dataset entries (default: 0 = all). Use for quick runs.")
    private int limit = 0;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RmShadingEvalRunner()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
            System.err.println("Error: --project-dir does not exist or is not a directory: " + projectDir);
            return 1;
        }
        if (!GitRepoFactory.hasGitRepo(projectDir)) {
            System.err.println("Error: No Git repository at: " + projectDir);
            return 1;
        }
        if (!Files.exists(datasetPath) || !Files.isRegularFile(datasetPath)) {
            System.err.println("Error: --dataset file not found: " + datasetPath);
            return 1;
        }

        RmShadingEvalTypes.RmShadingDataset dataset = new ObjectMapper()
                .readValue(Files.readString(datasetPath), RmShadingEvalTypes.RmShadingDataset.class);
        if (dataset.entries().isEmpty()) {
            System.err.println("Error: Dataset has no entries.");
            return 1;
        }

        List<RmShadingEvalTypes.RmShadingDatasetEntry> entries = dataset.entries();
        if (limit > 0) {
            entries = entries.subList(0, Math.min(limit, entries.size()));
            System.out.println("Limit applied: processing first " + entries.size() + " entry(ies).");
        }

        System.out.println("================================================================================");
        System.out.println(" RmShadingEvalRunner - Diff corpus generation");
        System.out.println("================================================================================");
        System.out.println(" Project:  " + projectDir);
        System.out.println(" Dataset:  " + datasetPath);
        System.out.println(" Output:   " + output);
        System.out.println(" Entries:  " + entries.size());
        System.out.println("--------------------------------------------------------------------------------");
        if (entries.size() > 10) {
            System.out.println(" Processing may take several hours. Progress (elapsed + ETA) is printed per entry.");
            System.out.println(" Checkpoint every 5 entries. Use tail -f or watch the log to track progress.");
            System.out.println("--------------------------------------------------------------------------------");
        }

        Files.createDirectories(output);

        List<RmShadingCorpusItem> corpus = new ArrayList<>();
        int failed = 0;
        int total = entries.size();
        long startMs = System.currentTimeMillis();

        try (GitRepo repo = new GitRepo(projectDir)) {
            int index = 0;
            for (RmShadingEvalTypes.RmShadingDatasetEntry entry : entries) {
                index++;
                try {
                    RmShadingCorpusItem item = processEntry(repo, entry);
                    corpus.add(item);
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    long estTotalMs = index < total && elapsedMs > 0 ? (elapsedMs * total) / index : 0;
                    long estRemainingMs = Math.max(0, estTotalMs - elapsedMs);
                    String elapsedStr = formatDuration(elapsedMs);
                    String remainingStr = estRemainingMs > 0 ? formatDuration(estRemainingMs) : "";
                    System.out.printf(
                            "[%d/%d] %s refactoringCount=%d vanillaLines=%d rmShadedLines=%d | Elapsed: %s",
                            index, total, entry.id(),
                            item.refactoringCount(),
                            item.vanillaDiffLineCount(),
                            item.rmShadedDiffLineCount(),
                            elapsedStr);
                    if (!remainingStr.isEmpty()) {
                        System.out.printf(" | Est. remaining: %s", remainingStr);
                    }
                    System.out.println();
                    System.out.flush();
                    if (index % 5 == 0 && index < total) {
                        System.out.printf("---------- Checkpoint: %d/%d done (Elapsed: %s | Est. remaining: %s) ----------%n",
                                index, total, elapsedStr, remainingStr.isEmpty() ? "?" : remainingStr);
                        System.out.flush();
                    }
                } catch (Exception e) {
                    System.err.printf("[%d/%d] %s FAILED: %s%n", index, total, entry.id(), e.getMessage());
                    System.err.flush();
                    failed++;
                }
            }
        }

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path corpusPath = output.resolve("corpus.json");
        Files.writeString(
                corpusPath,
                mapper.writeValueAsString(corpus),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" Corpus written to: " + corpusPath);
        if (failed > 0) {
            System.out.printf(" Failed: %d entries%n", failed);
        }
        System.out.println("================================================================================");

        return failed > 0 ? 1 : 0;
    }

    private static String formatDuration(long millis) {
        if (millis < 0) return "0m";
        long sec = (millis / 1000) % 60;
        long min = (millis / 60_000) % 60;
        long hr = millis / 3_600_000;
        if (hr > 0) {
            return String.format("%dh %dm", hr, min);
        }
        if (min > 0) {
            return String.format("%dm", min);
        }
        return sec > 0 ? String.format("%ds", sec) : "0m";
    }

    private RmShadingCorpusItem processEntry(GitRepo repo, RmShadingEvalTypes.RmShadingDatasetEntry entry)
            throws GitAPIException {
        String fromRef = entry.fromRef();
        String toRef = entry.toRef();

        String resolvedFrom = repo.resolveToCommit(fromRef).name();
        String resolvedEnd = toRef.equals("WORKING")
                ? repo.resolveToCommit("HEAD").name()
                : repo.resolveToCommit(toRef).name();

        List<CommitInfo> commits = List.of();
        if (!resolvedEnd.equals(resolvedFrom)) {
            commits = repo.listCommitsBetweenBranches(resolvedFrom, resolvedEnd, false);
        }

        var cumulativeChanges = DiffService.computeCumulativeDiff(repo, resolvedFrom, toRef, commits);

        var pair = RmShadingEvalHelper.produceBothDiffs(cumulativeChanges, null);

        return new RmShadingCorpusItem(
                entry.id(),
                fromRef,
                toRef,
                pair.refactoringCount(),
                pair.vanillaDiff(),
                pair.rmShadedDiff(),
                pair.refactoringSummary(),
                pair.vanillaDiffLineCount(),
                pair.rmShadedDiffLineCount(),
                cumulativeChanges.filesChanged(),
                null,
                null);
    }

    /**
     * One item in the diff corpus (and later judge output). Phase 1 fills diff
     * fields; Phase 2 adds winner and reasoning.
     */
    public record RmShadingCorpusItem(
            String id,
            String fromRef,
            String toRef,
            int refactoringCount,
            String vanillaDiff,
            String rmShadedDiff,
            String refactoringSummary,
            int vanillaDiffLineCount,
            int rmShadedDiffLineCount,
            int filesChanged,
            String winner,
            String reasoning) {

        public RmShadingCorpusItem {
            if (winner == null) winner = "";
            if (reasoning == null) reasoning = "";
        }
    }
}
