package ai.brokk.analyzer.cpp;

import static ai.brokk.analyzer.cpp.Constants.nodeType;
import static org.treesitter.CppNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT)),
            Set.of(nodeType(FOR_STATEMENT), nodeType(WHILE_STATEMENT), nodeType(DO_STATEMENT)),
            Set.of(nodeType(CATCH_CLAUSE)),
            Set.of(nodeType(CONDITIONAL_EXPRESSION)),
            Set.of(nodeType(CASE_STATEMENT)),
            Set.of("default_statement"),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||", "and", "or"),
            Set.of(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT)),
            Set.of(nodeType(FUNCTION_DEFINITION)),
            Set.of(nodeType(LAMBDA_EXPRESSION)),
            Set.of(nodeType(ELSE_CLAUSE)));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
