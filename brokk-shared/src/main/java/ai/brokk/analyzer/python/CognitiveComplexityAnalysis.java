package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.ASTTraversalUtils.namedChildren;
import static ai.brokk.analyzer.ASTTraversalUtils.typeOf;
import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT)),
            Set.of(nodeType(ELIF_CLAUSE)),
            Set.of(nodeType(FOR_STATEMENT), nodeType(WHILE_STATEMENT)),
            Set.of(nodeType(EXCEPT_CLAUSE)),
            Set.of(nodeType(CONDITIONAL_EXPRESSION)),
            Set.of(nodeType(CASE_CLAUSE)),
            Set.of(),
            Set.of(nodeType(BOOLEAN_OPERATOR)),
            Set.of("and", "or"),
            Set.of(),
            Set.of(nodeType(FUNCTION_DEFINITION)),
            Set.of(nodeType(LAMBDA)),
            Set.of(),
            (node, sourceContent) -> false,
            CognitiveComplexityAnalysis::isDecoratedFunctionBoundary);

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }

    private static boolean isDecoratedFunctionBoundary(TSNode node) {
        if (!nodeType(DECORATED_DEFINITION).equals(typeOf(node))) {
            return false;
        }
        return namedChildren(node).stream()
                .anyMatch(child -> nodeType(FUNCTION_DEFINITION).equals(typeOf(child)));
    }
}
