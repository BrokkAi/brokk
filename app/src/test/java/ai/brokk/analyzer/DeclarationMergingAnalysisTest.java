package ai.brokk.analyzer;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterTypescript;

/**
 * Test to analyze the AST structure of TypeScript declaration merging
 * to understand how to detect and handle it properly.
 */
public class DeclarationMergingAnalysisTest {

    @Test
    void analyzeFunctionNamespaceMerge() {
        var source =
                """
            export function observableFromEvent<T>(x: T): T {
                return x;
            }

            export namespace observableFromEvent {
                export const Observer = "obs";
                export function helper() { }
            }
            """;

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterTypescript());
        var tree = parser.parseString(null, source);
        var root = tree.getRootNode();

        System.out.println("=== Full AST ===");
        printTree(root, source.getBytes(StandardCharsets.UTF_8), 0, 3);

        // Find the two export_statement nodes
        System.out.println("\n=== Analyzing export_statement nodes ===");
        for (int i = 0; i < root.getChildCount(); i++) {
            TSNode child = root.getChild(i);
            if ("export_statement".equals(child.getType())) {
                System.out.println("\nExport statement " + i + ":");
                printTree(child, source.getBytes(StandardCharsets.UTF_8), 0, 2);

                // Check what's being exported
                for (int j = 0; j < child.getChildCount(); j++) {
                    TSNode exportedItem = child.getChild(j);
                    String type = exportedItem.getType();
                    if (!"export".equals(type)) {
                        System.out.println("  Exported item type: " + type);
                    }
                }
            }
        }
    }

    private void printTree(TSNode node, byte[] source, int depth, int maxDepth) {
        if (node == null || node.isNull() || depth > maxDepth) {
            return;
        }

        String indent = "  ".repeat(depth);
        String nodeType = node.getType();

        String text = "";
        if (node.getChildCount() == 0 || node.getEndByte() - node.getStartByte() < 30) {
            text = new String(
                    source, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
            text = " [" + text.replace("\n", "\\n") + "]";
        }

        System.out.println(indent + nodeType + text);

        for (int i = 0; i < node.getChildCount(); i++) {
            printTree(node.getChild(i), source, depth + 1, maxDepth);
        }
    }
}
