package ai.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

/**
 * Phase 3 of the RM-shading eval: reads corpus-with-judgments.json and prints
 * aggregate stats and breakdowns by refactoring count and diff size.
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

        System.out.println("================================================================================");
        System.out.println(" RM-shading eval – aggregate");
        System.out.println("================================================================================");
        System.out.println(" Total items:    " + items.size());
        System.out.println(" WINNER A:       " + aWins + " (vanilla)");
        System.out.println(" WINNER B:       " + bWins + " (RM-shaded)");
        System.out.println(" TIE:            " + ties);
        if (unknown > 0) System.out.println(" Unknown/failed: " + unknown);
        System.out.println("--------------------------------------------------------------------------------");

        int[] aByRef = new int[3], bByRef = new int[3], tieByRef = new int[3];
        for (var item : items) {
            int bucket = item.refactoringCount() == 0 ? 0 : (item.refactoringCount() <= 5 ? 1 : 2);
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
        System.out.println("   0 refactorings:  A=" + aByRef[0] + " B=" + bByRef[0] + " TIE=" + tieByRef[0]);
        System.out.println("   1-5 refactorings: A=" + aByRef[1] + " B=" + bByRef[1] + " TIE=" + tieByRef[1]);
        System.out.println("   6+ refactorings:  A=" + aByRef[2] + " B=" + bByRef[2] + " TIE=" + tieByRef[2]);
        System.out.println("--------------------------------------------------------------------------------");

        int[] aBySize = new int[3], bBySize = new int[3], tieBySize = new int[3];
        for (var item : items) {
            int lines = item.vanillaDiffLineCount();
            int bucket = lines < 200 ? 0 : (lines <= 1000 ? 1 : 2);
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
        System.out.println("   < 200:   A=" + aBySize[0] + " B=" + bBySize[0] + " TIE=" + tieBySize[0]);
        System.out.println("   200-1k:  A=" + aBySize[1] + " B=" + bBySize[1] + " TIE=" + tieBySize[1]);
        System.out.println("   > 1k:    A=" + aBySize[2] + " B=" + bBySize[2] + " TIE=" + tieBySize[2]);
        System.out.println("================================================================================");

        return 0;
    }
}
