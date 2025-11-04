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
 * Test to investigate duplicate FQName warnings when an abstract class has:
 * - An abstract getter declaration
 * - A concrete implementation with a field of the same name
 *
 * Example from VSCode BreadcrumbsConfig:
 * abstract class BreadcrumbsConfig {
 *     abstract get name(): string;           // abstract getter
 * }
 *
 * const concrete = new class implements BreadcrumbsConfig {
 *     readonly name = "value";               // field implementation
 * }
 */
public class AbstractGetterTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGetterTest.class);

    @Test
    void testAbstractGetterAndConcreteField(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("test.ts");
        Files.writeString(
                testFile,
                """
            abstract class BreadcrumbsConfig {
                abstract get name(): string;
            }

            const concrete = new class implements BreadcrumbsConfig {
                readonly name = "value";
            };
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        var allDeclarations = analyzer.getDeclarations(projectFile);

        logger.info("All declarations found:");
        for (var cu : allDeclarations) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        var nameUnits = allDeclarations.stream()
                .filter(cu -> cu.fqName().contains("name"))
                .collect(Collectors.toList());

        logger.info("name-related CodeUnits:");
        for (var cu : nameUnits) {
            logger.info("  - {} (kind={}, shortName={})", cu.fqName(), cu.kind(), cu.shortName());
        }

        // We should find both the abstract getter and the concrete field
        // But they should have distinct FQNames
        assertFalse(nameUnits.isEmpty(), "Should find name-related CodeUnits");

        // Check for duplicate FQNames
        var fqNames = nameUnits.stream().map(CodeUnit::fqName).collect(Collectors.toList());

        var uniqueFqNames = fqNames.stream().distinct().collect(Collectors.toList());

        logger.info("FQNames: {}", fqNames);
        logger.info("Unique FQNames: {}", uniqueFqNames);

        // After fix, should have no duplicate FQNames
        assertEquals(fqNames.size(), uniqueFqNames.size(), "Should have no duplicate FQNames. Found: " + fqNames);

        // Verify we have exactly 2 CodeUnits: abstract getter and concrete field
        assertEquals(2, nameUnits.size(), "Should have 2 name CodeUnits (abstract getter + concrete field)");

        // One should be the abstract getter (FUNCTION with $get suffix)
        var getters =
                nameUnits.stream().filter(cu -> cu.fqName().endsWith("$get")).collect(Collectors.toList());
        assertEquals(1, getters.size(), "Should have 1 getter");
        assertEquals(CodeUnitType.FUNCTION, getters.getFirst().kind());

        // One should be the concrete field
        var fields =
                nameUnits.stream().filter(cu -> cu.kind() == CodeUnitType.FIELD).collect(Collectors.toList());
        assertEquals(1, fields.size(), "Should have 1 field");
    }

    @Test
    void testVSCodeBreadcrumbsPattern(@TempDir Path tempDir) throws IOException {
        // More realistic pattern from VSCode
        var testFile = tempDir.resolve("breadcrumbs.ts");
        Files.writeString(
                testFile,
                """
            export abstract class BreadcrumbsConfig<T> {
                abstract get name(): string;
                abstract get onDidChange(): Event<void>;

                abstract getValue(): T;

                private static _stub<T>(configName: string) {
                    return {
                        bindTo(service: IConfigurationService) {
                            return new class implements BreadcrumbsConfig<T> {
                                readonly name = configName;
                                readonly onDidChange = service.onDidChange;

                                getValue(): T {
                                    return service.getValue(configName);
                                }
                            };
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

        // Check for duplicate FQNames specifically for 'name'
        var allFqNames = allDeclarations.stream().map(CodeUnit::fqName).collect(Collectors.toList());

        var duplicates = allFqNames.stream()
                .filter(fqName ->
                        allFqNames.stream().filter(f -> f.equals(fqName)).count() > 1)
                .distinct()
                .collect(Collectors.toList());

        logger.info("Duplicate FQNames found: {}", duplicates);

        assertTrue(duplicates.isEmpty(), "Should have no duplicate FQNames. Found duplicates: " + duplicates);
    }
}
