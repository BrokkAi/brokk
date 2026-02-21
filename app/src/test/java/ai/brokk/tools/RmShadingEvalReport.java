package ai.brokk.tools;

import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * Streams corpus or corpus-with-judgments JSON (without loading the whole file)
 * to report: (1) token counts and savings (vanilla vs RM-shaded), (2) A/B/TIE
 * breakdown by refactoring type (when each type was present). Use this to answer
 * "are we saving tokens?" and "are some refactor types destroying information?"
 */
@NullMarked
@CommandLine.Command(
        name = "RmShadingEvalReport",
        mixinStandardHelpOptions = true,
        description = "Token savings and refactoring-type breakdown from corpus (streaming, safe for large files)")
public class RmShadingEvalReport implements Callable<Integer> {

    private static final Pattern REFACTORING_TYPE_HEADER = Pattern.compile("### ([^\n]+?)\\s*\\((\\d+)\\)");

    @CommandLine.Option(
            names = {"--input"},
            required = true,
            description = "Path to corpus.json or corpus-with-judgments.json")
    private Path inputPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RmShadingEvalReport()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
            System.err.println("Error: Input file not found: " + inputPath);
            return 1;
        }

        System.out.println("================================================================================");
        System.out.println(" RM-shading eval – token & refactoring-type report");
        System.out.println("================================================================================");
        System.out.println(" Input: " + inputPath);
        System.out.println(" (Streaming; safe for large files.)");
        System.out.println("--------------------------------------------------------------------------------");

        long totalVanillaTokens = 0;
        long totalRmShadedTokens = 0;
        int itemCount = 0;
        List<Long> vanillaTokenList = new ArrayList<>();
        List<Long> rmShadedTokenList = new ArrayList<>();

        // type -> [aWins, bWins, ties, count]
        Map<String, int[]> byType = new LinkedHashMap<>();

        try (InputStream in = Files.newInputStream(inputPath)) {
            var factory = new JsonFactory();
            try (var parser = factory.createParser(in)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    System.err.println("Error: Expected JSON array at root.");
                    return 1;
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    ItemFields fields = readItemFields(parser);
                    itemCount++;

                    long vTokens = Messages.getApproximateTokens(fields.vanillaDiff != null ? fields.vanillaDiff : "");
                    long rTokens = Messages.getApproximateTokens(fields.rmShadedDiff != null ? fields.rmShadedDiff : "");
                    totalVanillaTokens += vTokens;
                    totalRmShadedTokens += rTokens;
                    vanillaTokenList.add(vTokens);
                    rmShadedTokenList.add(rTokens);

                    if (fields.winner != null && fields.refactoringSummary != null && !fields.refactoringSummary.isEmpty()) {
                        List<String> types = parseRefactoringTypes(fields.refactoringSummary);
                        String w = fields.winner.toUpperCase();
                        int a = "A".equals(w) ? 1 : 0;
                        int b = "B".equals(w) ? 1 : 0;
                        int t = "TIE".equals(w) ? 1 : 0;
                        for (String type : types) {
                            byType.computeIfAbsent(type, k -> new int[4])
                                    [0] += a;
                            byType.get(type)[1] += b;
                            byType.get(type)[2] += t;
                            byType.get(type)[3] += 1;
                        }
                    }
                }
            }
        }

        if (itemCount == 0) {
            System.out.println(" No items in corpus.");
            System.out.println("================================================================================");
            return 0;
        }

        // Token summary
        long saved = totalVanillaTokens - totalRmShadedTokens;
        double pctSaved = totalVanillaTokens > 0 ? 100.0 * saved / totalVanillaTokens : 0;
        System.out.println(" Token counts (approximate, OpenAI-style):");
        System.out.println("   Total vanilla tokens:   " + totalVanillaTokens);
        System.out.println("   Total RM-shaded tokens: " + totalRmShadedTokens);
        System.out.println("   Saved:                  " + saved + " (" + String.format("%.1f%%", pctSaved) + ")");
        long medVanilla = median(vanillaTokenList);
        long medRmShaded = median(rmShadedTokenList);
        System.out.println("   Per-item median vanilla: " + medVanilla + "  RM-shaded: " + medRmShaded);
        System.out.println("--------------------------------------------------------------------------------");

        // By refactoring type (only when we had winner + refactoring summary)
        if (!byType.isEmpty()) {
            System.out.println(" By refactoring type (when this type was present in the change):");
            System.out.println("   (A = prefer vanilla, B = prefer RM-shaded, TIE = tie)");
            List<Map.Entry<String, int[]>> sorted = new ArrayList<>(byType.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue()[3], a.getValue()[3])); // by count desc
            for (var e : sorted) {
                String type = e.getKey();
                int[] v = e.getValue();
                int a = v[0], b = v[1], tie = v[2], n = v[3];
                double pctA = n > 0 ? 100.0 * a / n : 0;
                System.out.printf("   %-40s  n=%3d  A=%3d (%.0f%%)  B=%3d  TIE=%3d%n",
                        type.length() > 40 ? type.substring(0, 37) + "..." : type, n, a, pctA, b, tie);
            }
            System.out.println("--------------------------------------------------------------------------------");
        }

        System.out.println("================================================================================");
        return 0;
    }

    private static long median(List<Long> list) {
        if (list.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(list);
        sorted.sort(Long::compareTo);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private static List<String> parseRefactoringTypes(String refactoringSummary) {
        List<String> types = new ArrayList<>();
        Matcher m = REFACTORING_TYPE_HEADER.matcher(refactoringSummary);
        while (m.find()) {
            types.add(m.group(1).trim());
        }
        return types;
    }

    private static final class ItemFields {
        @Nullable String winner;
        @Nullable String refactoringSummary;
        @Nullable String vanillaDiff;
        @Nullable String rmShadedDiff;
    }

    /** Read one object from the parser (parser must be positioned at START_OBJECT). */
    @SuppressWarnings("deprecation")
    private static ItemFields readItemFields(com.fasterxml.jackson.core.JsonParser parser) throws java.io.IOException {
        ItemFields f = new ItemFields();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.getCurrentName();
            if (name == null) continue;
            parser.nextToken();
            switch (name) {
                case "id" -> parser.getValueAsString(); // skip
                case "winner" -> f.winner = parser.getValueAsString();
                case "refactoringSummary" -> f.refactoringSummary = parser.getValueAsString();
                case "vanillaDiff" -> f.vanillaDiff = parser.getValueAsString();
                case "rmShadedDiff" -> f.rmShadedDiff = parser.getValueAsString();
                default -> {
                    if (parser.currentToken() == JsonToken.START_OBJECT || parser.currentToken() == JsonToken.START_ARRAY) {
                        parser.skipChildren();
                    }
                }
            }
        }
        return f;
    }
}
