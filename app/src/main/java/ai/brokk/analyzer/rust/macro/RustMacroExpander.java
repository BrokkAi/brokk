package ai.brokk.analyzer.rust.macro;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import java.util.List;
import org.treesitter.TSNode;

/**
 * Interface for strategies that expand Rust macros into synthetic CodeUnits.
 */
public interface RustMacroExpander {
    /**
     * Returns true if this expander supports the given node (e.g., if it has the required attributes).
     */
    boolean supports(TSNode targetNode, SourceContent source);

    List<CodeUnit> expand(TSNode targetNode, SourceContent source, ProjectFile file, String packageName);
}
