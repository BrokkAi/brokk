package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to verify that TypeScript index signatures and call signatures are properly captured.
 */
public class IndexCallSignatureTest {

    private static final Logger logger = LoggerFactory.getLogger(IndexCallSignatureTest.class);

    @Test
    void testIndexSignature(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("dictionary.ts");
        Files.writeString(
                testFile,
                """
            interface IStringDictionary<V> {
                [name: string]: V;
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Declarations found: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var indexSig = declarations.stream()
                .filter(cu -> cu.fqName().contains("[index]"))
                .findFirst();

        assertTrue(indexSig.isPresent(), "Index signature should be captured");
        assertFalse(indexSig.get().isFunction(), "Index signature should be field-like");
        assertTrue(
                indexSig.get().fqName().contains("IStringDictionary"),
                "Index signature should be within IStringDictionary");
    }

    @Test
    void testCallSignature(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("disposable.ts");
        Files.writeString(
                testFile,
                """
            interface IDisposable {
                (): void;
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Declarations found: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var callSig = declarations.stream()
                .filter(cu -> cu.fqName().contains("[call]"))
                .findFirst();

        assertTrue(callSig.isPresent(), "Call signature should be captured");
        assertTrue(callSig.get().isFunction(), "Call signature should be function-like");
        assertTrue(callSig.get().fqName().contains("IDisposable"), "Call signature should be within IDisposable");
    }

    @Test
    void testVSCodePatterns(@TempDir Path tempDir) throws IOException {
        // Real patterns from VSCode codebase
        var testFile = tempDir.resolve("vscodePatterns.ts");
        Files.writeString(
                testFile,
                """
            // From vscode/src/vs/base/common/collections.ts
            export interface IStringDictionary<V> {
                [name: string]: V;
            }

            // From vscode/src/vs/base/common/lifecycle.ts
            export interface IDisposable {
                (): void;
            }

            // Complex case: both index signature and call signature
            export interface IComplexInterface<T> {
                [key: string]: T;
                (): void;
                regularMethod(): string;
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "All declarations: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        // Count interfaces
        var interfaces = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> cu.fqName().contains("Interface")
                        || cu.fqName().contains("Dictionary")
                        || cu.fqName().contains("Disposable"))
                .toList();
        assertEquals(3, interfaces.size(), "Should have 3 interfaces");

        // Check IComplexInterface has index signature, call signature, and regular method
        var complexMembers = declarations.stream()
                .filter(cu -> cu.fqName().contains("IComplexInterface"))
                .filter(cu -> !cu.isClass()) // Not the interface itself
                .toList();

        logger.info(
                "IComplexInterface members: {}",
                complexMembers.stream().map(CodeUnit::fqName).toList());

        assertTrue(
                complexMembers.stream().anyMatch(cu -> cu.fqName().contains("[index]")), "Should have index signature");
        assertTrue(
                complexMembers.stream().anyMatch(cu -> cu.fqName().contains("[call]")), "Should have call signature");
        assertTrue(
                complexMembers.stream().anyMatch(cu -> cu.fqName().contains("regularMethod")),
                "Should have regular method");
    }

    @Test
    void testSkeletonRendering(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("skeletonTest.ts");
        Files.writeString(
                testFile,
                """
            interface IExample {
                [key: string]: any;
                (x: number): string;
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var skeletons = analyzer.getSkeletons(projectFile);
        logger.info("Skeletons: {}", skeletons);

        // Verify the interface skeleton includes members
        var interfaceSkeleton = skeletons.entrySet().stream()
                .filter(e -> e.getKey().fqName().equals("IExample"))
                .findFirst();

        assertTrue(interfaceSkeleton.isPresent(), "Should have IExample skeleton");
        String skeleton = interfaceSkeleton.get().getValue();
        logger.info("IExample skeleton:\n{}", skeleton);

        // Skeleton should contain the interface and potentially its members
        assertTrue(skeleton.contains("interface IExample"), "Skeleton should contain interface declaration");
    }
}
