package ai.brokk.analyzer.go;

import static ai.brokk.analyzer.go.Constants.nodeType;
import static org.treesitter.GoNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT)),
            Set.of(nodeType(FOR_STATEMENT)),
            Set.of(),
            Set.of(),
            Set.of(nodeType(EXPRESSION_CASE), nodeType(TYPE_CASE), nodeType(COMMUNICATION_CASE)),
            Set.of(nodeType(DEFAULT_CASE)),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||"),
            Set.of(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT)),
            Set.of(nodeType(FUNCTION_DECLARATION), nodeType(METHOD_DECLARATION)),
            Set.of(nodeType(FUNC_LITERAL)),
            Set.of("else_clause"));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
