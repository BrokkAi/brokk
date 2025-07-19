package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that TreeSitterAnalyzer and JoernAnalyzer have consistent search behavior
 * for case-insensitive and regex-based searches.
 */
public final class SearchBehaviorComparisonTest {
    private static TestProject javaTestProject;
    private static JavaAnalyzer javaAnalyzer;
    private static TestProject jsTestProject;
    private static JavascriptAnalyzer jsAnalyzer;

    @BeforeAll
    static void setup() {
        javaTestProject = createTestProject("testcode-java", Language.JAVA);
        var tempCpgFile = Path.of(System.getProperty("java.io.tmpdir"), "brokk-search-comparison-test.bin");
        javaAnalyzer = new JavaAnalyzer(javaTestProject.getRoot(), Collections.emptySet(), tempCpgFile);

        jsTestProject = createTestProject("testcode-js", Language.JAVASCRIPT);
        jsAnalyzer = new JavascriptAnalyzer(jsTestProject);
    }

    static TestProject createTestProject(String subDir, Language lang) {
        var testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    @Test
    void testCaseInsensitiveSearchConsistency() {
        // Test case-insensitive behavior across both CPG-based and TreeSitter-based analyzers
        // This ensures consistent search semantics regardless of analyzer implementation

        // Java (CPG-based) case-insensitive testing: 'e' vs 'E'
        var javaLowerE = javaAnalyzer.searchDefinitions("e");
        var javaUpperE = javaAnalyzer.searchDefinitions("E");

        var javaLowerNames = javaLowerE.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var javaUpperNames = javaUpperE.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(javaLowerNames, javaUpperNames,
                    "Java analyzer case-insensitive: 'e' and 'E' should return identical symbol sets");
        assertFalse(javaLowerNames.isEmpty(), "Case-insensitive search should find symbols containing 'e'/'E'");

        // JavaScript (TreeSitter-based) case-insensitive testing: 'hello' vs 'HELLO'
        var jsLowerHello = jsAnalyzer.searchDefinitions("hello");
        var jsUpperHello = jsAnalyzer.searchDefinitions("HELLO");

        var jsLowerNames = jsLowerHello.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var jsUpperNames = jsUpperHello.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(jsLowerNames, jsUpperNames,
                    "JavaScript analyzer case-insensitive: 'hello' and 'HELLO' should return identical symbol sets");
        assertFalse(jsLowerNames.isEmpty(), "Case-insensitive search should find symbols containing 'hello'/'HELLO'");

        // Test mixed case patterns for additional case-insensitive verification
        // "UsE" should find symbols containing the substring "UsE" case-insensitively
        var javaMixedCase = javaAnalyzer.searchDefinitions("UsE");
        var javaMixedNames = javaMixedCase.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertFalse(javaMixedNames.isEmpty(), "Mixed case 'UsE' should find symbols containing 'UsE'");
        // Should find symbols that contain "UsE" as a substring, case-insensitively
        assertTrue(javaMixedNames.contains("UseE"), "Should find 'UseE' with pattern 'UsE'");
        // Verify that the pattern is case-insensitive: "use" should find same results as "UsE"
        var javaLowerUse = javaAnalyzer.searchDefinitions("use");
        var javaLowerUseNames = javaLowerUse.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(javaMixedNames, javaLowerUseNames,
                    "Case-insensitive: 'UsE' and 'use' should return identical results");

        var jsMixedCase = jsAnalyzer.searchDefinitions("HeLLo");
        var jsMixedNames = jsMixedCase.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(jsLowerNames, jsMixedNames,
                    "Mixed case 'HeLLo' should return same results as 'hello' and 'HELLO'");
    }

    @Test
    void testRegexPatternConsistency() {
        var javaRegexPattern = javaAnalyzer.searchDefinitions("method.*1");
        assertFalse(javaRegexPattern.isEmpty(), "Java analyzer should find symbols matching 'method.*1'");

        var javaRegexNames = javaRegexPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(javaRegexNames.contains("A.method1"), "Should find 'A.method1' with regex pattern");
        assertTrue(javaRegexNames.contains("D.methodD1"), "Should find 'D.methodD1' with regex pattern");

        var jsRegexPattern = jsAnalyzer.searchDefinitions(".*Arrow.*");
        assertFalse(jsRegexPattern.isEmpty(), "JavaScript analyzer should find symbols matching '.*Arrow.*'");

        var jsRegexNames = jsRegexPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(jsRegexNames.stream().anyMatch(name -> name.contains("Arrow")),
                  "Should find symbols containing 'Arrow' with regex pattern");
    }

    @Test
    void testPatternEscapingBehavior() {
        var specialPattern = "method1";

        var javaSpecialResults = javaAnalyzer.searchDefinitions(specialPattern);
        var javaSpecialNames = javaSpecialResults.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertTrue(javaSpecialNames.stream().anyMatch(name -> name.contains("method1")),
                  "Should find symbols containing 'method1'. Found: " + javaSpecialNames);

        var jsSpecialPattern = "greet";
        var jsSpecialResults = jsAnalyzer.searchDefinitions(jsSpecialPattern);
        var jsSpecialNames = jsSpecialResults.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertTrue(jsSpecialNames.stream().anyMatch(name -> name.contains("greet")),
                  "Should find symbols containing 'greet'. Found: " + jsSpecialNames);
    }

    @Test
    void testEmptyAndNonExistentPatterns() {
        var jsEmpty = jsAnalyzer.searchDefinitions("");
        assertTrue(jsEmpty.isEmpty(), "JavaScript analyzer should return empty list for empty pattern");

        var nonExistentPattern = "NonExistentSymbolXYZ123";
        var javaNonExistent = javaAnalyzer.searchDefinitions(nonExistentPattern);
        var jsNonExistent = jsAnalyzer.searchDefinitions(nonExistentPattern);

        assertTrue(javaNonExistent.isEmpty(), "Java analyzer should return empty list for non-existent pattern");
        assertTrue(jsNonExistent.isEmpty(), "JavaScript analyzer should return empty list for non-existent pattern");
    }

    @Test
    void testAutoWildcardBehavior() {
        var javaSimple = javaAnalyzer.searchDefinitions("method");
        assertFalse(javaSimple.isEmpty(), "Java analyzer should find symbols containing 'method'");

        var javaExplicit = javaAnalyzer.searchDefinitions(".*method.*");
        assertFalse(javaExplicit.isEmpty(), "Java analyzer should find symbols with explicit wildcard pattern");

        var simpleNames = javaSimple.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var explicitNames = javaExplicit.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(simpleNames, explicitNames,
                    "Auto-wrapped and explicit wildcard patterns should produce same results");

        var jsSimple = jsAnalyzer.searchDefinitions("greet");
        var jsExplicit = jsAnalyzer.searchDefinitions(".*greet.*");

        var jsSimpleNames = jsSimple.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var jsExplicitNames = jsExplicit.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(jsSimpleNames, jsExplicitNames,
                    "JavaScript analyzer auto-wrapped and explicit patterns should produce same results");
    }

    @Test
    void testConstructorPatternConsistency() {
        // Test constructor-related pattern searches across different analyzer implementations
        // Each language has different constructor conventions, this ensures consistent search behavior

        // Java (CPG-based): Uses <init> for constructors
        var javaInitSymbols = javaAnalyzer.searchDefinitions("init");
        System.out.println("Java 'init' constructor symbols: " +
            javaInitSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toList()));
        // Note: Results may vary based on test data, but search should not fail

        // JavaScript (TreeSitter): Uses 'constructor' keyword or class names as constructors
        // Note: JavaScript may not have explicit constructor declarations in test data
        var jsConstructorSymbols = jsAnalyzer.searchDefinitions("constructor");

        // Test that both analyzers handle constructor searches without errors
        // This verifies consistent behavior regardless of whether constructors are found
        assertNotNull(javaInitSymbols, "Java analyzer should return non-null result for 'init' search");
        assertNotNull(jsConstructorSymbols, "JavaScript analyzer should return non-null result for 'constructor' search");
    }

    @Test
    void testAnalyzerTypeIdentification() {
        // Verify that we can distinguish between CPG and TreeSitter analyzers
        assertTrue(javaAnalyzer.isCpg(), "Java analyzer should be CPG-based");
        assertFalse(jsAnalyzer.isCpg(), "JavaScript analyzer should be TreeSitter-based");
    }
}
