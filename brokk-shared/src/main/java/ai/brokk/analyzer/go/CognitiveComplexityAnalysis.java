package ai.brokk.analyzer.go;

import static ai.brokk.analyzer.go.Constants.nodeType;
import static org.treesitter.GoNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT))
            .loopTypes(nodeType(FOR_STATEMENT))
            .caseTypes(nodeType(EXPRESSION_CASE), nodeType(TYPE_CASE), nodeType(COMMUNICATION_CASE))
            .defaultCaseTypes(nodeType(DEFAULT_CASE))
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||")
            .jumpTypes(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT))
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_DECLARATION), nodeType(METHOD_DECLARATION))
            .anonymousFunctionTypes(nodeType(FUNC_LITERAL))
            .elseClauseTypes("else_clause")
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
