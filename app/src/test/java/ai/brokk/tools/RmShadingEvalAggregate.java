package ai.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * Phase 3 of the RM-shading eval: reads corpus-with-judgments.json and prints
 * aggregate stats and breakdowns by refactoring count and diff size.
 * Optionally writes a reasonings-only JSON for the reasoning summarizer.
 */
@NullMarked
@CommandLine.Command(
        name = "RmShadingEvalAggregate",
        mixinStandardHelpOptions = true,
        description = "Aggregates judge results: overall preference and breakdown by refactoring count / diff size")
public class RmShadingEvalAggregate implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--corpus-with-judgments"},
            required = true,
            description = "Path to corpus-with-judgments.json from RmShadingEvalJudge")
    private Path corpusPath;

    @CommandLine.Option(
            names = {"--write-reasonings"},
            description = "Write a reasonings-only JSON (id, winner, reasoning) to this path for use with reasoning summarizer")
    private @Nullable Path writeReasoningsPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RmShadingEvalAggregate()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(corpusPath) || !Files.isRegularFile(corpusPath)) {
            System.err.println("Error: File not found: " + corpusPath);
            return 1;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<RmShadingEvalRunner.RmShadingCorpusItem> items = mapper.readValue(
                Files.readString(corpusPath),
                new TypeReference<List<RmShadingEvalRunner.RmShadingCorpusItem>>() {});

        if (items.isEmpty()) {
            System.err.println("Error: No items in corpus.");
            return 1;
        }

        int aWins = 0, bWins = 0, ties = 0, unknown = 0;
        for (var item : items) {
            String w = item.winner();
            if (w == null || w.isBlank()) unknown++;
            else switch (w.toUpperCase()) {
                case "A" -> aWins++;
                case "B" -> bWins++;
                case "TIE" -> ties++;
                default -> unknown++;
            }
        }

        int totalJudged = aWins + bWins + ties;
        double pctA = totalJudged > 0 ? 100.0 * aWins / totalJudged : 0;
        double pctB = totalJudged > 0 ? 100.0 * bWins / totalJudged : 0;
        double pctTie = totalJudged > 0 ? 100.0 * ties / totalJudged : 0;

        System.out.println("================================================================================");
        System.out.println(" RM-shading eval – aggregate");
        System.out.println("================================================================================");
        System.out.println(" Total items:    " + items.size());
        System.out.println(" WINNER A:       " + aWins + " (vanilla)   " + String.format("%.1f%%", pctA));
        System.out.println(" WINNER B:       " + bWins + " (RM-shaded) " + String.format("%.1f%%", pctB));
        System.out.println(" TIE:            " + ties + "              " + String.format("%.1f%%", pctTie));
        if (unknown > 0) System.out.println(" Unknown/failed: " + unknown);
        System.out.println("--------------------------------------------------------------------------------");

        int[] aByRef = new int[3], bByRef = new int[3], tieByRef = new int[3], nByRef = new int[3];
        for (var item : items) {
            int bucket = item.refactoringCount() == 0 ? 0 : (item.refactoringCount() <= 5 ? 1 : 2);
            nByRef[bucket]++;
            String w = item.winner();
            if (w == null || w.isBlank()) continue;
            switch (w.toUpperCase()) {
                case "A" -> aByRef[bucket]++;
                case "B" -> bByRef[bucket]++;
                case "TIE" -> tieByRef[bucket]++;
                default -> {}
            }
        }
        System.out.println(" By refactoring count:");
        System.out.println("   0 refactorings:   A=" + aByRef[0] + " B=" + bByRef[0] + " TIE=" + tieByRef[0] + (nByRef[0] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aByRef[0] / nByRef[0]) + " of bucket)" : ""));
        System.out.println("   1-5 refactorings: A=" + aByRef[1] + " B=" + bByRef[1] + " TIE=" + tieByRef[1] + (nByRef[1] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aByRef[1] / nByRef[1]) + " of bucket)" : ""));
        System.out.println("   6+ refactorings:  A=" + aByRef[2] + " B=" + bByRef[2] + " TIE=" + tieByRef[2] + (nByRef[2] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aByRef[2] / nByRef[2]) + " of bucket)" : ""));
        System.out.println("--------------------------------------------------------------------------------");

        int[] aBySize = new int[3], bBySize = new int[3], tieBySize = new int[3], nBySize = new int[3];
        for (var item : items) {
            int lines = item.vanillaDiffLineCount();
            int bucket = lines < 200 ? 0 : (lines <= 1000 ? 1 : 2);
            nBySize[bucket]++;
            String w = item.winner();
            if (w == null || w.isBlank()) continue;
            switch (w.toUpperCase()) {
                case "A" -> aBySize[bucket]++;
                case "B" -> bBySize[bucket]++;
                case "TIE" -> tieBySize[bucket]++;
                default -> {}
            }
        }
        System.out.println(" By vanilla diff size (lines):");
        System.out.println("   < 200:   A=" + aBySize[0] + " B=" + bBySize[0] + " TIE=" + tieBySize[0] + (nBySize[0] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aBySize[0] / nBySize[0]) + " of bucket)" : ""));
        System.out.println("   200-1k:  A=" + aBySize[1] + " B=" + bBySize[1] + " TIE=" + tieBySize[1] + (nBySize[1] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aBySize[1] / nBySize[1]) + " of bucket)" : ""));
        System.out.println("   > 1k:    A=" + aBySize[2] + " B=" + bBySize[2] + " TIE=" + tieBySize[2] + (nBySize[2] > 0 ? "  (A " + String.format("%.0f%%", 100.0 * aBySize[2] / nBySize[2]) + " of bucket)" : ""));

        if (bWins > 0) {
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println(" When B (RM-shaded) won – refactoring count & vanilla lines:");
            for (var item : items) {
                if ("B".equalsIgnoreCase(item.winner())) {
                    String note = item.refactoringCount() == 0 ? "  (A and B were identical – judge noise)" : "";
                    System.out.println("   id=" + item.id() + " refactorings=" + item.refactoringCount() + " vanillaLines=" + item.vanillaDiffLineCount() + note);
                }
            }
        }

        System.out.println("================================================================================");

        if (writeReasoningsPath != null) {
            List<ReasoningEntry> reasonings = new ArrayList<>();
            for (var item : items) {
                reasonings.add(new ReasoningEntry(item.id(), item.winner() != null ? item.winner() : "", item.reasoning() != null ? item.reasoning() : ""));
            }
            Files.createDirectories(writeReasoningsPath.getParent());
            ObjectMapper writer = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(writeReasoningsPath, writer.writeValueAsString(reasonings));
            System.out.println(" Wrote reasonings to: " + writeReasoningsPath);
        }

        return 0;
    }

    private record ReasoningEntry(String id, String winner, String reasoning) {}
}
