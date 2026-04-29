package ai.brokk.analyzer.php;

import static ai.brokk.analyzer.php.Constants.nodeType;
import static org.treesitter.PhpNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT), nodeType(ELSE_IF_CLAUSE))
            .loopTypes(
                    nodeType(FOR_STATEMENT),
                    nodeType(FOREACH_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT))
            .catchTypes(nodeType(CATCH_CLAUSE))
            .conditionalTypes(nodeType(CONDITIONAL_EXPRESSION))
            .caseTypes(nodeType(CASE_STATEMENT), "match_condition")
            .defaultCaseTypes(nodeType(DEFAULT_STATEMENT), nodeType(MATCH_DEFAULT_EXPRESSION))
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||", "and", "or", "??")
            .jumpTypes(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT))
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_DEFINITION), nodeType(METHOD_DECLARATION))
            .anonymousFunctionTypes(nodeType(ANONYMOUS_FUNCTION), nodeType(ARROW_FUNCTION))
            .elseClauseTypes(nodeType(ELSE_CLAUSE))
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
