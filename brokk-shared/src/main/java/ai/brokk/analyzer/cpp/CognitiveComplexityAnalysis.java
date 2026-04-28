package ai.brokk.analyzer.cpp;

import static ai.brokk.analyzer.cpp.Constants.nodeType;
import static org.treesitter.CppNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT))
            .loopTypes(nodeType(FOR_STATEMENT), nodeType(WHILE_STATEMENT), nodeType(DO_STATEMENT))
            .catchTypes(nodeType(CATCH_CLAUSE))
            .conditionalTypes(nodeType(CONDITIONAL_EXPRESSION))
            .caseTypes(nodeType(CASE_STATEMENT))
            .defaultCaseTypes("default_statement")
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||", "and", "or")
            .jumpTypes(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT))
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_DEFINITION))
            .anonymousFunctionTypes(nodeType(LAMBDA_EXPRESSION))
            .elseClauseTypes(nodeType(ELSE_CLAUSE))
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
