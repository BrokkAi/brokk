package io.github.jbellis.brokk.analyzer;

import org.junit.jupiter.api.Test;
import org.treesitter.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test to examine TreeSitter AST structure for C++ global declarations
 */
public class CppTreeSitterGrammarTest {

    @Test
    public void examineGlobalDeclarationsAST() throws Exception {
        // Create TreeSitter parser for C++
        TSParser parser = new TSParser();
        TSLanguage language = new TreeSitterCpp();
        parser.setLanguage(language);

        // Test geometry.h content to debug actual patterns
        String source = """
            typedef unsigned int uint32_t;
            using String = char*;
            """;

        System.out.println("=== Source Content ===");
        System.out.println(source);

        // Parse the source
        TSTree tree = parser.parseString(null, source);
        TSNode rootNode = tree.getRootNode();

        System.out.println("\n=== AST Structure ===");
        printAST(rootNode, source, 0);

        // Test TreeSitter query patterns
        String queryString = """
            (type_definition
              declarator: (_) @typedef.name) @typedef.definition

            (alias_declaration
              name: (type_identifier) @using.name) @using.definition
            """;

        System.out.println("\n=== Query Results ===");
        TSQuery query = new TSQuery(language, queryString);
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, rootNode);

        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            System.out.println("Match pattern: " + match.getPatternIndex());
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(capture.getIndex());
                TSNode captureNode = capture.getNode();
                String captureText = source.substring(captureNode.getStartByte(), captureNode.getEndByte());
                System.out.printf("  Capture: %s = %s (type: %s)%n",
                    captureName, captureText.trim(), captureNode.getType());
            }
        }
    }

    private void printAST(TSNode node, String source, int depth) {
        if (depth > 6) return; // Limit recursion depth

        String indent = "  ".repeat(depth);
        String nodeText = source.substring(node.getStartByte(), node.getEndByte());
        if (nodeText.length() > 50) {
            nodeText = nodeText.substring(0, 47) + "...";
        }
        nodeText = nodeText.replace('\n', ' ').replace('\r', ' ');

        System.out.printf("%s%s [%d-%d] \"%s\"%n",
            indent, node.getType(), node.getStartByte(), node.getEndByte(), nodeText);

        // Show field names
        for (int i = 0; i < node.getChildCount(); i++) {
            String fieldName = node.getFieldNameForChild(i);
            if (fieldName != null) {
                System.out.printf("%s  [field: %s]%n", indent, fieldName);
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            printAST(child, source, depth + 1);
        }
    }
}