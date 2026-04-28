package ai.brokk.analyzer.scala;

import static ai.brokk.analyzer.scala.Constants.nodeType;
import static org.treesitter.ScalaNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_EXPRESSION))
            .loopTypes(nodeType(FOR_EXPRESSION), nodeType(WHILE_EXPRESSION), nodeType(DO_WHILE_EXPRESSION))
            .caseTypes(nodeType(CASE_CLAUSE))
            .binaryTypes(nodeType(INFIX_EXPRESSION))
            .logicalOperators("&&", "||")
            .jumpTypes("break_expression", "continue_expression")
            .namedFunctionBoundaryTypes(nodeType(FUNCTION_DEFINITION))
            .anonymousFunctionTypes(nodeType(LAMBDA_EXPRESSION))
            .elseClauseTypes("else_clause")
            .defaultCasePredicate(CognitiveComplexitySupport::isWildcardCase)
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
