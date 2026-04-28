package ai.brokk.analyzer.rust;

import static ai.brokk.analyzer.rust.Constants.nodeType;
import static org.treesitter.RustNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_EXPRESSION)),
            Set.of(),
            Set.of(nodeType(FOR_EXPRESSION), nodeType(WHILE_EXPRESSION), nodeType(LOOP_EXPRESSION)),
            Set.of(),
            Set.of(),
            Set.of(nodeType(MATCH_ARM)),
            Set.of(),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||"),
            Set.of(nodeType(BREAK_EXPRESSION), nodeType(CONTINUE_EXPRESSION)),
            Set.of(nodeType(FUNCTION_ITEM)),
            Set.of(nodeType(CLOSURE_EXPRESSION)),
            Set.of(nodeType(ELSE_CLAUSE)),
            CognitiveComplexitySupport::isWildcardCase,
            node -> false);

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
