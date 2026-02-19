package ai.brokk.analyzer.rust.macro;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.treesitter.TSNode;

/**
 * Interface for strategies that expand Rust macros into synthetic CodeUnits.
 */
@NullMarked
public interface RustMacroExpander {
    List<CodeUnit> expand(TSNode targetNode, SourceContent source, ProjectFile file, String packageName);
}
