package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterJava;

public class UTF8ByteOffsetTest {

    @Test
    public void testUnicodeCharacterByteOffsetHandling() {
        // This is the exact content pattern from CpgCache.java that causes issues
        String javaCodeWithUnicode =
                """
            package test;

            /* ─────────────────────────  Helpers  ───────────────────────── */

            public class TestClass {
                private String field = "test";

                public void method() {
                    System.out.println("Hello");
                }
            }
            """;

        // Parse with TreeSitter
        var parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        var tree = parser.parseString(null, javaCodeWithUnicode);
        var root = tree.getRootNode();

        // Find the class declaration node
        var classNode = findNodeByType(root, "class_declaration");
        assertNotNull(classNode, "Should find class declaration");

        // Get byte offsets from TreeSitter (this is what TreeSitter provides)
        int startByte = classNode.getStartByte();
        int endByte = classNode.getEndByte();

        // Create SourceContent wrapper for safe byte offset handling
        SourceContent content = SourceContent.of(javaCodeWithUnicode);

        // Calculate character positions for comparison
        int startChar = content.byteOffsetToCharPosition(startByte);
        int endChar = content.byteOffsetToCharPosition(endByte);

        // Demonstrate the issue: byte offsets != character positions due to Unicode
        assertNotEquals(startByte, startChar, "Byte offset should differ from char position due to Unicode");
        assertNotEquals(endByte, endChar, "Byte offset should differ from char position due to Unicode");

        // OLD APPROACH (would fail on master): Using byte offsets as character positions
        assertThrows(StringIndexOutOfBoundsException.class, () -> {
            // This would crash on master because byte positions are beyond string length
            if (startByte < javaCodeWithUnicode.length() && endByte <= javaCodeWithUnicode.length()) {
                String result = javaCodeWithUnicode.substring(startByte, endByte);
                // If we get here without exception, check if extraction is wrong
                assertFalse(result.contains("public class TestClass"), "Old approach should extract wrong content");
            } else {
                // Force the exception to demonstrate the issue
                javaCodeWithUnicode.substring(startByte, endByte);
            }
        });

        // NEW APPROACH (fixed): Using proper byte-to-character conversion
        String extractedText = content.substringFromByteOffsets(startByte, endByte);

        // Verify the fix works correctly
        assertTrue(
                extractedText.contains("public class TestClass"),
                "Fixed approach should correctly extract class declaration");
        assertTrue(
                extractedText.contains("private String field"), "Fixed approach should extract complete class content");
        assertTrue(extractedText.contains("public void method()"), "Fixed approach should extract method declaration");

        // Also test SourceContent for node text extraction
        String nodeText = content.substringFromByteOffsets(classNode.getStartByte(), classNode.getEndByte());
        assertTrue(nodeText.contains("public class TestClass"), "SourceContent should correctly extract node text");
    }

    @Test
    public void testMultipleUnicodeCharacters() {
        // Test with more Unicode characters to amplify the offset drift
        String codeWithManyUnicode =
                """
            /* ═══════════════════════════════════════════════════════════ */
            /* ─────────────────────────  Setup  ──────────────────────── */
            /* ═══════════════════════════════════════════════════════════ */

            public class UnicodeTest {
                // Comment with unicode: ★ ☆ ♦ ♣ ♠ ♥
                private String value;
            }
            """;

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        var tree = parser.parseString(null, codeWithManyUnicode);
        var root = tree.getRootNode();

        var classNode = findNodeByType(root, "class_declaration");
        assertNotNull(classNode);

        int startByte = classNode.getStartByte();
        int endByte = classNode.getEndByte();

        // Create SourceContent wrapper for safe byte offset handling
        SourceContent content = SourceContent.of(codeWithManyUnicode);

        // Calculate the significant offset drift
        int startChar = content.byteOffsetToCharPosition(startByte);
        int endChar = content.byteOffsetToCharPosition(endByte);

        int offsetDrift = startByte - startChar;
        assertTrue(offsetDrift > 20, "Should have significant byte offset drift: " + offsetDrift);

        // Old approach would definitely fail here
        if (startByte < codeWithManyUnicode.length()) {
            String oldResult =
                    codeWithManyUnicode.substring(startByte, Math.min(endByte, codeWithManyUnicode.length()));
            assertFalse(oldResult.trim().startsWith("public class"), "Old approach should not extract class correctly");
        }

        // New approach should work
        String newResult = content.substringFromByteOffsets(startByte, endByte);
        assertTrue(newResult.contains("public class UnicodeTest"), "New approach should extract class correctly");
    }

    // Helper method for finding nodes

    private TSNode findNodeByType(TSNode root, String nodeType) {
        if (root == null || root.isNull()) return null;

        if (nodeType.equals(root.getType())) {
            return root;
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            var child = root.getChild(i);
            if (child != null && !child.isNull()) {
                var result = findNodeByType(child, nodeType);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }
}
