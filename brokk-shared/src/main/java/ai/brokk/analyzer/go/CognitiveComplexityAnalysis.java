package ai.brokk.analyzer.go;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of("if_statement"),
            Set.of("for_statement"),
            Set.of(),
            Set.of(),
            Set.of("expression_case", "type_case", "communication_case"),
            Set.of("default_case"),
            Set.of("binary_expression"),
            Set.of("&&", "||"),
            Set.of("break_statement", "continue_statement"),
            Set.of("function_declaration", "method_declaration"),
            Set.of("func_literal"),
            Set.of("else_clause"));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
