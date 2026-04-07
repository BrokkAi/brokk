package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validation test for VSCode-like patterns to verify all improvements work together.
 */
public class VSCodePatternsValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(VSCodePatternsValidationTest.class);

    @Test
    void testVSCodePatternsComprehensive(@TempDir Path tempDir) throws Exception {
        // Load the VSCode patterns file from test resources
        var resourceUrl = getClass().getResource("/testcode-ts/VSCodePatterns.ts");
        assertNotNull(resourceUrl, "VSCodePatterns.ts resource should exist");
        var sourceFile = Path.of(resourceUrl.toURI());
        var testFile = tempDir.resolve("VSCodePatterns.ts");
        Files.copy(sourceFile, testFile);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info("Total declarations found: {}", declarations.size());
        logger.info(
                "All FQNs: {}",
                declarations.stream().map(CodeUnit::fqName).sorted().collect(Collectors.toList()));

        // Test 1: Namespace-as-package semantics
        var disposableClass = declarations.stream()
                .filter(cu -> cu.fqName().equals("vs.base.common.Disposable"))
                .findFirst();
        assertTrue(disposableClass.isPresent(), "Should find Disposable class");
        assertEquals("vs.base.common", disposableClass.get().packageName(), "Package should be the namespace path");
        assertEquals("Disposable", disposableClass.get().shortName(), "ShortName should not include namespace");

        // Test 2: Index signature capture
        var indexSig = declarations.stream()
                .filter(cu ->
                        cu.fqName().contains("IStringDictionary") && cu.fqName().contains("[index]"))
                .findFirst();
        assertTrue(indexSig.isPresent(), "Should capture index signature");
        logger.info("Index signature FQN: {}", indexSig.get().fqName());

        // Test 3: Call signature capture
        var callSig = declarations.stream()
                .filter(cu -> cu.fqName().contains("IDisposable") && cu.fqName().contains("[call]"))
                .findFirst();
        assertTrue(callSig.isPresent(), "Should capture call signature in interface");
        logger.info("Call signature FQN: {}", callSig.get().fqName());

        // Test 4: Arrow functions
        var createCancelable = declarations.stream()
                .filter(cu -> cu.fqName().contains("createCancelablePromise"))
                .filter(CodeUnit::isFunction)
                .findFirst();
        assertTrue(createCancelable.isPresent(), "Should capture arrow function");
        assertEquals(
                "vs.base.common", createCancelable.get().packageName(), "Arrow function should have namespace package");

        // Test 5: Nested namespace
        var formatFunc = declarations.stream()
                .filter(cu -> cu.fqName().contains("format") && cu.fqName().contains("strings"))
                .filter(CodeUnit::isFunction)
                .findFirst();
        assertTrue(formatFunc.isPresent(), "Should capture function in nested namespace");
        assertEquals("vs.base.common.strings", formatFunc.get().packageName(), "Nested namespace should be in package");

        // Test 6: Top-level arrow function
        var timeout = declarations.stream()
                .filter(cu -> cu.fqName().equals("timeout"))
                .filter(CodeUnit::isFunction)
                .findFirst();
        assertTrue(timeout.isPresent(), "Should capture top-level arrow function");

        // Test 7: Type aliases in namespace
        var eventType = declarations.stream()
                .filter(cu -> cu.fqName().contains("Event") && !cu.isFunction())
                .findFirst();
        assertTrue(eventType.isPresent(), "Should capture type alias in namespace");
        assertEquals("vs.base.common", eventType.get().packageName(), "Type alias should have namespace package");

        logger.info("✅ All VSCode pattern validations passed!");
        logger.info("✅ Namespace-as-package: WORKING");
        logger.info("✅ Index signatures: CAPTURED");
        logger.info("✅ Call signatures: CAPTURED");
        logger.info("✅ Arrow functions: OPTIMIZED");
    }
}
