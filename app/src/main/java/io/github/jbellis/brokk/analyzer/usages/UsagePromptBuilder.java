package io.github.jbellis.brokk.analyzer.usages;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds an XML-styled prompt for LLM-based usage disambiguation.
 *
 * <p>The output consists of a sequence of <file> blocks, each containing:
 *
 * <ul>
 *   <li>an <code>&lt;imports&gt;</code> section with the file's import statements (from
 *       TreeSitterAnalyzer.importStatementsOf)
 *   <li>multiple <code>&lt;usage id="..."&gt;</code> blocks embedding snippets (3 lines above and below the occurrence)
 * </ul>
 *
 * All text content is XML-escaped. A rough token budget (maxTokens) is enforced using a conservative character limit
 * (approx 4 chars per token).
 */
public final class UsagePromptBuilder {

    private UsagePromptBuilder() {}

    public static String buildPrompt(
            Map<ProjectFile, List<UsageHit>> hitsByFile,
            List<CodeUnit> candidateTargets,
            TreeSitterAnalyzer analyzer,
            String shortName,
            int maxTokens) {

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);

        var sb = new StringBuilder(Math.min(maxChars, 64_000));

        // Small context header (comment) to aid the model; escape defensively
        sb.append("<!-- shortName: ").append(escapeXml(shortName)).append(" -->\n");
        if (candidateTargets != null && !candidateTargets.isEmpty()) {
            sb.append("<!-- candidates: ");
            // Keep candidate summary concise
            var names = new ArrayList<String>(candidateTargets.size());
            for (var cu : candidateTargets) {
                names.add(escapeXml(cu.fqName()));
            }
            sb.append(String.join(", ", names));
            sb.append(" -->\n");
        }

        // Deterministic ordering: sort files by path string
        var entries = new ArrayList<>(hitsByFile.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().absPath().toString(), String.CASE_INSENSITIVE_ORDER));

        int usageId = 1;

        for (var entry : entries) {
            if (sb.length() >= maxChars) break;

            ProjectFile file = entry.getKey();
            List<UsageHit> hits = entry.getValue();
            if (hits == null || hits.isEmpty()) {
                continue;
            }

            // Gather imports via analyzer (best effort)
            List<String> imports;
            try {
                imports = analyzer.importStatementsOf(file);
            } catch (Throwable t) {
                imports = List.of(); // fail open
            }

            // Start file block
            sb.append("<file path=\"")
                    .append(escapeXml(file.absPath().toString()))
                    .append("\">\n");

            // Imports block
            sb.append("<imports>\n");
            for (String imp : imports) {
                sb.append(escapeXml(imp)).append("\n");
            }
            sb.append("</imports>\n\n");

            // Usage blocks for this file, preserving input order
            for (UsageHit hit : hits) {
                int beforeUsageLen = sb.length();

                sb.append("<usage id=\"").append(usageId).append("\">\n");
                String snippet = hit.snippet() == null ? "" : hit.snippet();
                sb.append(escapeXml(snippet)).append("\n");
                sb.append("</usage>\n");

                // Enforce budget; if we exceed, roll back this usage and close file with truncation note
                if (sb.length() > maxChars) {
                    sb.setLength(beforeUsageLen);
                    sb.append("<!-- truncated due to token limit -->\n");
                    sb.append("</file>\n");
                    return sb.toString();
                }

                usageId++;
            }

            sb.append("</file>\n\n");

            // If we exceeded after closing file, append a truncation note and stop
            if (sb.length() > maxChars) {
                // Keep well-formedness – last block already closed
                sb.append("<!-- truncated due to token limit -->\n");
                break;
            }
        }

        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder((int) (s.length() * 1.1));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
