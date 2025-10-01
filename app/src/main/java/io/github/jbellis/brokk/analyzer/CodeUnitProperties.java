package io.github.jbellis.brokk.analyzer;

import java.util.Collections;
import java.util.List;

/**
 * The properties for a given {@link CodeUnit} for {@link TreeSitterAnalyzer}.
 *
 * @param children the AST children for this code unit.
 * @param signatures signatures used for building summaries.
 * @param ranges the location ranges this code unit spans.
 */
public record CodeUnitProperties(
        List<CodeUnit> children, List<String> signatures, List<TreeSitterAnalyzer.Range> ranges) {

    public static CodeUnitProperties empty() {
        return new CodeUnitProperties(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
}
