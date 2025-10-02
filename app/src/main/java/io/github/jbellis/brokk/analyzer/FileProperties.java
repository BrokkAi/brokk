package io.github.jbellis.brokk.analyzer;

import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSTree;

/**
 * Properties for a given {@link ProjectFile} for {@link TreeSitterAnalyzer}.
 *
 * @param topLevelCodeUnits the top-level code units.
 * @param parsedTree the corresponding parse tree.
 * @param importStatements imports found on this file.
 */
public record FileProperties(
        List<CodeUnit> topLevelCodeUnits, @Nullable TSTree parsedTree, List<String> importStatements) {

    public static FileProperties empty() {
        return new FileProperties(Collections.emptyList(), null, Collections.emptyList());
    }
}
