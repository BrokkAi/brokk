package ai.brokk.analyzer.rust;

import static ai.brokk.analyzer.rust.Constants.nodeType;
import static org.treesitter.RustNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_EXPRESSION))
            .loopTypes(nodeType(FOR_EXPRESSION), nodeType(WHILE_EXPRESSION), nodeType(LOOP_EXPRESSION))
            .caseTypes(nodeType(MATCH_ARM))
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||")
            .jumpTypes(nodeType(BREAK_EXPRESSION), nodeType(CONTINUE_EXPRESSION))
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_ITEM))
            .anonymousFunctionTypes(nodeType(CLOSURE_EXPRESSION))
            .elseClauseTypes(nodeType(ELSE_CLAUSE))
            .defaultCasePredicate(CognitiveComplexitySupport::isWildcardCase)
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
