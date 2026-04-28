package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.ASTTraversalUtils.namedChildren;
import static ai.brokk.analyzer.ASTTraversalUtils.typeOf;
import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT))
            .alternateIfTypes(nodeType(ELIF_CLAUSE))
            .loopTypes(nodeType(FOR_STATEMENT), nodeType(WHILE_STATEMENT))
            .catchTypes(nodeType(EXCEPT_CLAUSE))
            .conditionalTypes(nodeType(CONDITIONAL_EXPRESSION))
            .caseTypes(nodeType(CASE_CLAUSE))
            .binaryTypes(nodeType(BOOLEAN_OPERATOR))
            .logicalOperators("and", "or")
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_DEFINITION))
            .anonymousFunctionTypes(nodeType(LAMBDA))
            .namedFunctionBoundaryPredicate(CognitiveComplexityAnalysis::isDecoratedFunctionBoundary)
            .build();

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
