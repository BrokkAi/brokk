package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterJavascript;

class ASTTraversalUtilsTest {

    @Test
    void typeOfReturnsNullForTreeSitterNullNodeWrapper() {
        TSNode ifStatement = firstNodeOfType("function f(x) { if (x) return 1; }", "if_statement");
        TSNode missingAlternative = ifStatement.getChildByFieldName("alternative");

        assertNull(ASTTraversalUtils.typeOf(missingAlternative));
        assertFalse(ASTTraversalUtils.isValid(missingAlternative));
    }

    @Test
    void sameRangeReturnsFalseForInvalidNodes() {
        TSNode ifStatement = firstNodeOfType("function f(x) { if (x) return 1; }", "if_statement");
        TSNode missingAlternative = ifStatement.getChildByFieldName("alternative");

        assertFalse(ASTTraversalUtils.sameRange(ifStatement, missingAlternative));
    }

    @Test
    void sameRangeComparesValidNodesByByteRange() {
        TSNode ifStatement = firstNodeOfType("function f(x) { if (x) return 1; }", "if_statement");

        assertEquals("if_statement", ASTTraversalUtils.typeOf(ifStatement));
        assertTrue(ASTTraversalUtils.isValid(ifStatement));
        assertTrue(ASTTraversalUtils.sameRange(ifStatement, ifStatement));
    }

    private static TSNode firstNodeOfType(String source, String type) {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJavascript());
        TSNode result = ASTTraversalUtils.findNodeRecursive(
                parser.parseString(null, source).getRootNode(), node -> {
                    String nodeType = ASTTraversalUtils.typeOf(node);
                    return type.equals(nodeType);
                });
        if (result == null) {
            throw new AssertionError("Node not found: " + type);
        }
        return result;
    }
}
