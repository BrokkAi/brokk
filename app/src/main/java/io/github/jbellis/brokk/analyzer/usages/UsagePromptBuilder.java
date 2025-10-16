package io.github.jbellis.brokk.analyzer.usages;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import java.util.List;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Builds a single-usage prompt record for LLM-based relevance scoring.
 *
 * <p>The builder emits:
 *
 * <ul>
 *   <li>filterDescription: concise text describing the intended target (e.g., the short name and optional candidates)
 *   <li>candidateText: the snippet representing this single usage
 *   <li>promptText: an XML-like block including file path, imports, a &lt;candidates&gt; section, and a single
 *       &lt;usage&gt; block (no IDs)
 * </ul>
 *
 * <p>All textual XML content is escaped, and a conservative token-to-character budget is enforced.
 */
public final class UsagePromptBuilder {

    private UsagePromptBuilder() {}

    /**
     * Build a prompt for a single usage hit.
     *
     * @param hit single usage occurrence (snippet should contain ~3 lines above/below already if desired)
     * @param codeUnitTarget the intended target code unit
     * @param alternativeCodeUnits other plausible code units that share the short name (target excluded if present)
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, candidateText, and promptText (no IDs)
     */
    public static UsagePrompt buildPrompt(
            UsageHit hit,
            CodeUnit codeUnitTarget,
            List<CodeUnit> alternativeCodeUnits,
            IAnalyzer analyzer,
            String shortName,
            int maxTokens) {

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);
        var sb = new StringBuilder(Math.min(maxChars, 32_000));

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription = buildFilterDescription(codeUnitTarget);

        // Candidate text is the raw snippet for this single usage (unescaped)
        String candidateText = hit.snippet();

        // Header comments
        sb.append("<!-- shortName: ")
                .append(StringEscapeUtils.escapeXml10(shortName))
                .append(" -->\n");
        sb.append("<!-- codeUnit: ")
                .append(StringEscapeUtils.escapeXml10(codeUnitTarget.toString()))
                .append(" -->\n");

        // Gather imports (best effort)
        List<String> imports;
        try {
            imports = analyzer.importStatementsOf(hit.file());
        } catch (Throwable t) {
            imports = List.of(); // fail open
        }

        // Start file block
        sb.append("<file path=\"")
                .append(StringEscapeUtils.escapeXml10(hit.file().absPath().toString()))
                .append("\">\n");

        // Imports block
        sb.append("<imports>\n");
        for (String imp : imports) {
            sb.append(StringEscapeUtils.escapeXml10(imp)).append("\n");
        }
        sb.append("</imports>\n\n");

        // Alternatives section (exclude target if present)
        sb.append("<candidates>\n");
        for (CodeUnit alt : alternativeCodeUnits) {
            if (!alt.fqName().equals(codeUnitTarget.fqName())) {
                sb.append(StringEscapeUtils.escapeXml10(alt.fqName())).append("\n");
            }
        }
        sb.append("</candidates>\n\n");

        // Single usage block, no id attribute
        int beforeUsageLen = sb.length();
        sb.append("<usage>\n");
        sb.append(StringEscapeUtils.escapeXml10(candidateText)).append("\n");
        sb.append("</usage>\n");
        if (sb.length() > maxChars) {
            sb.setLength(beforeUsageLen);
            sb.append("<!-- truncated due to token limit -->\n");
            sb.append("</file>\n");
            return new UsagePrompt(filterDescription, candidateText, sb.toString());
        }

        sb.append("</file>\n");
        if (sb.length() > maxChars) {
            sb.append("<!-- truncated due to token limit -->\n");
        }

        return new UsagePrompt(filterDescription, candidateText, sb.toString());
    }

    private static String buildFilterDescription(CodeUnit targetCodeUnit) {
        if (UsageConfig.isBooleanUsageMode()) {
            return ("Determine if the snippet represents a usage of " + targetCodeUnit
                    + ". Consider the <candidates> list of alternative code units and decide if the usage "
                    + "matches ONLY the target (not any alternative).");
        } else {
            return ("Determine if the snippet represents a usage of " + targetCodeUnit
                    + ". Consider the <candidates> list of alternative code units and score how likely the usage "
                    + "matches ONLY the target (not any alternative). Return a real number in [0.0, 1.0].");
        }
    }
}
