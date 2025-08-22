package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaTreeSitterAnalyzerSearchTest {

    private static Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerSearchTest.class);
    private static JavaTreeSitterAnalyzer analyzer;
    private static IProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
        analyzer = new JavaTreeSitterAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testSearchDefinitions_BasicPatterns() {
        var eSymbols = analyzer.searchDefinitions("e");
        assertFalse(eSymbols.isEmpty(), "Should find symbols containing 'e'.");

        var eFqNames = eSymbols.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());
        assertTrue(eFqNames.contains("E"), "Should find class 'E'");
        assertTrue(eFqNames.contains("UseE"), "Should find class 'UseE'");
        assertTrue(eFqNames.contains("AnonymousUsage"), "Should find class 'AnonymousUsage'");
        assertTrue(eFqNames.contains("Interface"), "Should find class 'Interface'");

        var method1Symbols = analyzer.searchDefinitions("method1");
        assertFalse(method1Symbols.isEmpty(), "Should find symbols containing 'method1'.");
        var method1FqNames = method1Symbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(method1FqNames.contains("A.method1"), "Should find 'A.method1'");

        var methodD1Symbols = analyzer.searchDefinitions("method.*1");
        assertFalse(methodD1Symbols.isEmpty(), "Should find symbols matching 'method.*1'.");
        var methodD1FqNames = methodD1Symbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(methodD1FqNames.contains("A.method1"), "Should find 'A.method1'");
        assertTrue(methodD1FqNames.contains("D.methodD1"), "Should find 'D.methodD1'");
    }

    @Test
    public void testSearchDefinitions_CaseInsensitive() {
        var upperE = analyzer.searchDefinitions("E");
        var lowerE = analyzer.searchDefinitions("e");

        var upperENames = upperE.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var lowerENames = lowerE.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(upperENames, lowerENames, "Case-insensitive search: 'E' and 'e' should return identical results");
        assertTrue(upperENames.contains("E"), "Should find class 'E' regardless of pattern case");
        assertTrue(upperENames.contains("UseE"), "Should find class 'UseE' regardless of pattern case");
        assertTrue(upperENames.contains("Interface"), "Should find class 'Interface' regardless of pattern case");

        var mixedCase = analyzer.searchDefinitions("UsE");
        var mixedCaseNames = mixedCase.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertFalse(mixedCaseNames.isEmpty(), "Mixed case 'UsE' should find symbols containing 'UsE'");
        assertTrue(mixedCaseNames.contains("UseE"), "Should find 'UseE' with pattern 'UsE'");

        var lowerUse = analyzer.searchDefinitions("use");
        var lowerUseNames = lowerUse.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(
                mixedCaseNames, lowerUseNames, "Case-insensitive: 'UsE' and 'use' should return identical results");
    }

    @Test
    public void testSearchDefinitions_RegexPatternsFields() {
        var fieldSymbols = analyzer.searchDefinitions(".*field.*");
        assertFalse(fieldSymbols.isEmpty(), "Should find symbols containing 'field'.");

        var fieldFqNames = fieldSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(fieldFqNames.contains("D.field1"), "Should find 'D.field1'");
        assertTrue(fieldFqNames.contains("D.field2"), "Should find 'D.field2'");
        assertTrue(fieldFqNames.contains("E.iField"), "Should find 'E.iField'");
        assertTrue(fieldFqNames.contains("E.sField"), "Should find 'E.sField'");
    }

    @Test
    public void testSearchDefinitions_RegexPatternsMethods() {
        var methodSymbols = analyzer.searchDefinitions("method.*");
        var methodFqNames = methodSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(methodFqNames.contains("A.method1"), "Should find 'A.method1'");
        assertTrue(methodFqNames.contains("A.method2"), "Should find 'A.method2'");
        assertTrue(methodFqNames.contains("D.methodD1"), "Should find 'D.methodD1'");
        assertTrue(methodFqNames.contains("D.methodD2"), "Should find 'D.methodD2'");
    }

    @Test
    public void testSearchDefinitions_SpecificClasses() {
        var aSymbols = analyzer.searchDefinitions("A");
        var aClassNames = aSymbols.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());
        assertTrue(aClassNames.contains("A"), String.format("Should find class 'A'. Found classes: %s", aClassNames));

        var baseClassSymbols = analyzer.searchDefinitions(".*Class");
        var baseClassNames = baseClassSymbols.stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());
        assertTrue(
                baseClassNames.contains("BaseClass"),
                String.format("Should find 'BaseClass'. Found: %s", baseClassNames));
        assertTrue(
                baseClassNames.contains("CamelClass"),
                String.format("Should find 'CamelClass'. Found: %s", baseClassNames));
    }

    @Test
    public void testSearchDefinitions_EmptyAndNonExistent() {
        var emptyPatternSymbols = analyzer.searchDefinitions("");
        assertTrue(emptyPatternSymbols.isEmpty(), "Empty pattern should return no results");

        var nonExistentSymbols = analyzer.searchDefinitions("NonExistentPatternXYZ123");
        assertTrue(nonExistentSymbols.isEmpty(), "Non-existent pattern should return no results");
    }

    @Test
    public void testSearchDefinitions_NestedClasses() {
        var innerSymbols = analyzer.searchDefinitions("Inner");
        assertFalse(innerSymbols.isEmpty(), "Should find nested classes containing 'Inner'");

        var innerFqNames = innerSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(innerFqNames.contains("A$AInner"), "Should find nested class 'A.AInner'");
        assertTrue(
                innerFqNames.contains("A$AInner$AInnerInner"),
                "Should find deeply nested class 'A.AInner.AInnerInner'");
    }

    @Test
    public void testSearchDefinitions_Constructors() {
        var constructorSymbols = analyzer.searchDefinitions("init");
        var constructorFqNames =
                constructorSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        System.out.println(String.format("Found constructor symbols: %s", constructorFqNames));

        if (!constructorSymbols.isEmpty()) {
            assertTrue(
                    constructorSymbols.stream()
                            .anyMatch(symbol -> symbol.fqName().contains("init")),
                    String.format("Should find symbols containing 'init'. Found: %s", constructorFqNames));
        } else {
            System.out.println("No constructor symbols found - this will be compared with TreeSitter behavior");
        }
    }

    @Test
    public void testSearchDefinitions_PatternWrapping() {
        var methodSymbols1 = analyzer.searchDefinitions("method2");
        var methodSymbols2 = analyzer.searchDefinitions(".*method2.*");

        var method2Names1 = methodSymbols1.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var method2Names2 = methodSymbols2.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(method2Names1, method2Names2, "Auto-wrapped pattern should match explicit pattern");
        assertTrue(method2Names1.contains("A.method2"), "Should find 'A.method2'");
    }
}
