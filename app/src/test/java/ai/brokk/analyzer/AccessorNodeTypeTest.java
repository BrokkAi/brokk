package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterTypescript;

public class AccessorNodeTypeTest {

    @Test
    void testGetterSetterNodeTypes() {
        var source =
                """
            class WebviewViewPane {
                private _badge: number = 0;

                get badge(): number {
                    return this._badge;
                }

                set badge(value: number) {
                    this._badge = value;
                }

                normalMethod(): void {
                    console.log("Normal method");
                }
            }
            """;

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterTypescript());
        var tree = parser.parseString(null, source);
        var root = tree.getRootNode();

        // Find all method_definition nodes
        var results = new ArrayList<String>();
        collectMethodNodes(root, source.getBytes(StandardCharsets.UTF_8), results);

        var resultSummary = String.join("\n", results);
        System.out.println("Method definitions found:\n" + resultSummary);

        assertEquals(3, results.size(), "Should find exactly 3 method definitions. Found:\n" + resultSummary);

        // Check that we can distinguish getter from setter from normal method
        var hasGetter = results.stream().anyMatch(s -> s.contains("badge") && s.contains("getter"));
        var hasSetter = results.stream().anyMatch(s -> s.contains("badge") && s.contains("setter"));
        var hasNormal = results.stream().anyMatch(s -> s.contains("normalMethod"));

        assertTrue(hasGetter, "Should find getter. Found:\n" + resultSummary);
        assertTrue(hasSetter, "Should find setter. Found:\n" + resultSummary);
        assertTrue(hasNormal, "Should find normal method. Found:\n" + resultSummary);
    }

    @Test
    void testGetterSetterDistinctFQNames(@TempDir Path tempDir) throws IOException {
        // Create a test TypeScript file with getter and setter
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            class WebviewViewPane {
                private _badge: number = 0;

                get badge(): number {
                    return this._badge;
                }

                set badge(value: number) {
                    this._badge = value;
                }
            }
            """);

        // Use the TypeScript analyzer to parse the file
        var project = new ai.brokk.testutil.TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        // Get all code units for badge (accessors only, not the private field)
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        var badgeCodeUnits = analyzer.getDeclarations(projectFile).stream()
                .filter(cu -> cu.fqName().contains("badge"))
                .filter(cu -> cu.fqName().endsWith("$get") || cu.fqName().endsWith("$set"))
                .toList();

        var allBadge = analyzer.getDeclarations(projectFile).stream()
                .filter(cu -> cu.fqName().contains("badge"))
                .toList();

        System.out.println("All badge-related CodeUnits found:");
        for (var cu : allBadge) {
            System.out.println("  - " + cu.fqName() + " (kind=" + cu.kind() + ")");
        }

        // Verify we have exactly 2 badge accessor code units (getter and setter)
        assertEquals(
                2,
                badgeCodeUnits.size(),
                "Should have 2 distinct accessor CodeUnits for badge property. Found: "
                        + badgeCodeUnits.stream().map(CodeUnit::fqName).toList());

        // Verify they have different FQNames
        var fqNames = badgeCodeUnits.stream().map(CodeUnit::fqName).sorted().toList();

        assertTrue(
                fqNames.getFirst().endsWith("$get"), "First FQName should end with $get. Found: " + fqNames.getFirst());
        assertTrue(
                fqNames.getLast().endsWith("$set"), "Second FQName should end with $set. Found: " + fqNames.getLast());

        // Verify both start with the same base name
        var baseName = fqNames.getFirst().replace("$get", "");
        assertTrue(fqNames.getLast().startsWith(baseName), "Both FQNames should share the same base name");
    }

    private void collectMethodNodes(TSNode node, byte[] source, List<String> results) {
        var nodeType = node.getType();

        if ("method_definition".equals(nodeType)) {
            // Check for getter/setter via child nodes
            var childCount = node.getChildCount();
            var methodInfo = new StringBuilder();
            methodInfo.append("method_definition");

            boolean isGetter = false;
            boolean isSetter = false;
            String methodName = null;

            for (int i = 0; i < childCount; i++) {
                var child = node.getChild(i);
                var childType = child.getType();

                if ("get".equals(childType)) {
                    isGetter = true;
                } else if ("set".equals(childType)) {
                    isSetter = true;
                } else if ("property_identifier".equals(childType)) {
                    methodName = new String(
                            source,
                            child.getStartByte(),
                            child.getEndByte() - child.getStartByte(),
                            StandardCharsets.UTF_8);
                }
            }

            if (methodName != null) {
                methodInfo.append(" name=").append(methodName);
            }
            if (isGetter) {
                methodInfo.append(" [getter]");
            }
            if (isSetter) {
                methodInfo.append(" [setter]");
            }

            results.add(methodInfo.toString());
        }

        // Recurse to children
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMethodNodes(node.getChild(i), source, results);
        }
    }
}
