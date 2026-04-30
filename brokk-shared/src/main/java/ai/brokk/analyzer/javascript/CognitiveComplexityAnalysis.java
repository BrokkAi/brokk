package ai.brokk.analyzer.javascript;

import static ai.brokk.analyzer.javascript.Constants.nodeType;
import static org.treesitter.TsxNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT))
            .loopTypes(
                    nodeType(FOR_STATEMENT),
                    nodeType(FOR_IN_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT))
            .catchTypes(nodeType(CATCH_CLAUSE))
            .conditionalTypes(nodeType(TERNARY_EXPRESSION))
            .caseTypes(nodeType(SWITCH_CASE))
            .defaultCaseTypes(nodeType(SWITCH_DEFAULT))
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||", "??")
            .jumpTypes(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT))
            .namedFunctionBoundaryTypes(
                    nodeType(FUNCTION_DECLARATION),
                    nodeType(FUNCTION_EXPRESSION),
                    nodeType(GENERATOR_FUNCTION),
                    nodeType(GENERATOR_FUNCTION_DECLARATION),
                    nodeType(METHOD_DEFINITION),
                    nodeType(ARROW_FUNCTION))
            .elseClauseTypes(nodeType(ELSE_CLAUSE))
            .build();

    private static final SourceContent EMPTY_SOURCE = SourceContent.of("");

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root) {
        return CognitiveComplexitySupport.compute(root, EMPTY_SOURCE, CONFIG);
    }
}
