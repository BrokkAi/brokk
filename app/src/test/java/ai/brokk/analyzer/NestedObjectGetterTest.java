package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to investigate duplicate FQName warnings when a class has both:
 * - A field with name X
 * - A getter with name X inside a nested object literal
 *
 * Example from VSCode:
 * class ExtHostTerminal {
 *     shellIntegration: TerminalShellIntegration | undefined;  // field
 *     value = {
 *         get shellIntegration() { return that.shellIntegration; }  // getter in object literal
 *     }
 * }
 */
public class NestedObjectGetterTest {

    private static final Logger logger = LoggerFactory.getLogger(NestedObjectGetterTest.class);

    @Test
    void testFieldAndNestedObjectGetter(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            export class ExtHostTerminal {
                shellIntegration: string | undefined;

                readonly value = {
                    get shellIntegration(): string | undefined {
                        return this.shellIntegration;
                    }
                };
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        var allDeclarations = analyzer.getDeclarations(projectFile);

        logger.info("All declarations found:");
        for (var cu : allDeclarations) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        var shellIntegrationUnits = allDeclarations.stream()
                .filter(cu -> cu.fqName().contains("shellIntegration"))
                .collect(Collectors.toList());

        logger.info("shellIntegration CodeUnits:");
        for (var cu : shellIntegrationUnits) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        // We expect to find shellIntegration references
        assertFalse(shellIntegrationUnits.isEmpty(), "Should find shellIntegration CodeUnits");

        // After the fix, we should only find the field, not the getter in the object literal
        // The getter is skipped to avoid duplicate FQNames
        assertEquals(
                1,
                shellIntegrationUnits.size(),
                "Should find exactly 1 shellIntegration CodeUnit (field only, getter in object literal is skipped)");

        var cu = shellIntegrationUnits.getFirst();
        assertEquals(CodeUnitType.FIELD, cu.kind(), "The shellIntegration CodeUnit should be a FIELD, not a FUNCTION");
        assertEquals("ExtHostTerminal.shellIntegration", cu.fqName(), "FQName should match the field");
    }

    @Test
    void testVSCodeExtHostTerminalPattern(@TempDir Path tempDir) throws IOException {
        // More realistic pattern from VSCode
        var testFile = tempDir.resolve("extHostTerminal.ts");
        Files.writeString(
                testFile,
                """
            export class ExtHostTerminal {
                shellIntegration: TerminalShellIntegration | undefined;

                readonly value: Terminal;

                constructor() {
                    const that = this;
                    this.value = {
                        get shellIntegration(): TerminalShellIntegration | undefined {
                            return that.shellIntegration;
                        }
                    };
                }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        var allDeclarations = analyzer.getDeclarations(projectFile);

        logger.info("VSCode pattern - All declarations:");
        for (var cu : allDeclarations) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        var shellIntegrationUnits = allDeclarations.stream()
                .filter(cu -> cu.identifier().equals("shellIntegration"))
                .collect(Collectors.toList());

        logger.info("VSCode pattern - shellIntegration CodeUnits:");
        for (var cu : shellIntegrationUnits) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        // Check for duplicate FQNames
        var fqNames = shellIntegrationUnits.stream().map(CodeUnit::fqName).collect(Collectors.toList());

        var uniqueFqNames = fqNames.stream().distinct().collect(Collectors.toList());

        logger.info("FQNames: {}", fqNames);
        logger.info("Unique FQNames: {}", uniqueFqNames);

        // After the fix, we should only find the field, not the getter in the object literal
        assertEquals(1, fqNames.size(), "Should find exactly 1 shellIntegration CodeUnit (field only)");
        assertEquals(1, uniqueFqNames.size(), "Should have no duplicate FQNames");

        // Verify it's the field, not a function
        var cu = shellIntegrationUnits.getFirst();
        assertEquals(CodeUnitType.FIELD, cu.kind(), "Should be a FIELD");
    }
}
