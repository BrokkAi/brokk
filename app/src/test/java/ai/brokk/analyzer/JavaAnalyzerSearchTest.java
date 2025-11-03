package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaAnalyzerSearchTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaAnalyzerSearchTest.class);
    private static JavaAnalyzer analyzer;
    private static IProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Languages.JAVA);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
        analyzer = new JavaAnalyzer(testProject);
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
        assertTrue(innerFqNames.contains("A.AInner"), "Should find nested class 'A.AInner'");
        assertTrue(
                innerFqNames.contains("A.AInner.AInnerInner"),
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

    @Test
    public void testAutocomplete_BasicFieldsMethodsClasses() {
        var res1 = analyzer.autocompleteDefinitions("D.field1");
        assertFalse(res1.isEmpty(), "Should find 'D.field1' via autocomplete");
        var names1 = res1.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(names1.contains("D.field1"), "Autocomplete should include 'D.field1'");

        var res2 = analyzer.autocompleteDefinitions("iField");
        assertFalse(res2.isEmpty(), "Should find fields containing 'iField'");
        var names2 = res2.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                names2.stream().anyMatch(n -> n.contains("iField")),
                "Should include an 'iField' declaration (e.g. 'E.iField')");

        var res3 = analyzer.autocompleteDefinitions("method1");
        assertFalse(res3.isEmpty(), "Should find methods matching 'method1'");
        var names3List = res3.stream().map(CodeUnit::fqName).collect(Collectors.toList());
        var names3 = Set.copyOf(names3List);
        assertTrue(names3.contains("A.method1"), "Should autocomplete 'A.method1' for 'method1'");
        // Ensure it's ranked reasonably highly (within top 3)
        assertTrue(
                names3List.indexOf("A.method1") >= 0 && names3List.indexOf("A.method1") < 3,
                "A.method1 should be ranked near the top for query 'method1'");
    }

    @Test
    public void testAutocomplete_CamelCase() {
        var camel = analyzer.autocompleteDefinitions("CC");
        assertFalse(camel.isEmpty(), "Camel-case query 'CC' should return results");
        var camelNames = camel.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(camelNames.contains("CamelClass"), "Camel-case should match 'CamelClass'");
    }

    @Test
    public void testAutocomplete_HierarchicalWithDots() {
        var res = analyzer.autocompleteDefinitions("A.method2");
        assertFalse(res.isEmpty(), "Hierarchical query 'A.method2' should return results");
        var names = res.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(names.contains("A.method2"), "Should find 'A.method2' when querying 'A.method2'");
    }

    @Test
    public void testAutocomplete_CaseInsensitiveAndMixedCase() {
        var upper = analyzer.autocompleteDefinitions("E");
        var lower = analyzer.autocompleteDefinitions("e");

        var upperNames = upper.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var lowerNames = lower.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(upperNames, lowerNames, "Autocomplete should be case-insensitive for single letter 'E'/'e'");
        assertTrue(upperNames.contains("E"), "Should find class 'E' regardless of pattern case");
        assertTrue(upperNames.contains("UseE"), "Should find class 'UseE' regardless of pattern case");

        var mixedCase = analyzer.autocompleteDefinitions("UsE");
        var mixedCaseNames = mixedCase.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertFalse(mixedCaseNames.isEmpty(), "Mixed case 'UsE' should find symbols containing 'UsE'");
        assertTrue(mixedCaseNames.contains("UseE"), "Should find 'UseE' with pattern 'UsE'");

        var lowerUse = analyzer.autocompleteDefinitions("use");
        var lowerUseNames = lowerUse.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(
                mixedCaseNames, lowerUseNames, "Case-insensitive: 'UsE' and 'use' should return identical results");
    }

    @Test
    public void testAutocomplete_DotDollarEquivalence_NestedClasses() {
        // Query using dot-separated nested path; stored nested FQNs use '.' separators
        var dotQuery = analyzer.autocompleteDefinitions("A.AInner.AInnerInner");
        var dotNames = dotQuery.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                dotNames.contains("A.AInner.AInnerInner"),
                "Dot-hierarchy query should match nested class FQN with '.' separators");
    }

    @Test
    public void testAutocomplete_RecordComponentsAsFields() {
        // Record component should be treated as an implicit field
        var resFull = analyzer.autocompleteDefinitions("C.Foo.x");
        var namesFull = resFull.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(namesFull.contains("C.Foo.x"), "Should find record component as field 'C.Foo.x'");

        // Single-letter query still includes fields (autocomplete fallback ensures this)
        var resShort = analyzer.autocompleteDefinitions("x");
        var namesShort = resShort.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                namesShort.contains("C.Foo.x"), "Single-letter query should include record component field 'C.Foo.x'");
    }

    @Test
    public void testEnclosingMethod_A_method1() {
        var pf = getFileByName("A.java");
        var src = readFileText(pf);

        // Pick a point inside A.method1 body
        var needle = "System.out.println(\"hello\");";
        int start = src.indexOf(needle);
        assertTrue(start >= 0, "Expected to find needle inside A.method1");
        int end = start + needle.length();

        var range = toRange(src, start, end);
        var cu = analyzer.enclosingCodeUnit(pf, range).get();
        assertEquals("A.method1", cu.fqName());
    }

    @Test
    public void testEnclosingNestedInnerMethod_A_AInner_AInnerInner_method7() {
        var pf = getFileByName("A.java");
        var src = readFileText(pf);

        // Use the method header for method7
        var needle = "public void method7()";
        int start = src.indexOf(needle);
        assertTrue(start >= 0, "Expected to find method7 declaration in A.AInner.AInnerInner");
        int end = start + needle.length();

        var range = toRange(src, start, end);
        var cu = analyzer.enclosingCodeUnit(pf, range).get();
        assertEquals("A.AInner.AInnerInner.method7", cu.fqName());
    }

    @Test
    public void testEnclosingRecordComponentField_C_Foo_x() {
        var pf = getFileByName("C.java");
        var src = readFileText(pf);

        // Locate the record component 'x' inside 'record Foo(int x) {'
        var needle = "int x";
        int start = src.indexOf(needle);
        assertTrue(start >= 0, "Expected to find 'int x' in record component");
        int end = start + needle.length();

        var range = toRange(src, start, end);
        var cu = analyzer.enclosingCodeUnit(pf, range).get();
        assertEquals("C.Foo.x", cu.fqName());
    }

    @Test
    public void testEnclosingMethod_D_methodD2() {
        var pf = getFileByName("D.java");
        var src = readFileText(pf);

        // Inside methodD2 body: 'field1 = 42;'
        var needle = "field1 = 42;";
        int start = src.indexOf(needle);
        assertTrue(start >= 0, "Expected to find body content inside D.methodD2");
        int end = start + needle.length();

        var range = toRange(src, start, end);
        var cu = analyzer.enclosingCodeUnit(pf, range).get();
        assertEquals("D.methodD2", cu.fqName());
    }

    @Test
    public void testEnclosing_EmptyRange_ReturnsNull() {
        var pf = getFileByName("A.java");
        var src = readFileText(pf);

        var idx = src.indexOf("class A");
        assertTrue(idx >= 0, "Expected to find 'class A'");
        // Create an empty range (start == end) which should be treated as empty
        var range = new IAnalyzer.Range(idx, idx, 0, 0, idx);

        var cu = analyzer.enclosingCodeUnit(pf, range);
        assertTrue(cu.isEmpty(), "Empty range should return null as per contract");
    }

    // --------------- helpers (moved from JavaTreeSitterAnalyzerEnclosingTest) ---------------

    private ProjectFile getFileByName(String fileName) {
        var opt = analyzer.getProject().getAllFiles().stream()
                .filter(p -> fileName.equals(p.getFileName()))
                .findFirst();
        assertTrue(opt.isPresent(), "Project file not found: " + fileName);
        return opt.get();
    }

    private String readFileText(ProjectFile pf) {
        var opt = pf.read();
        assertTrue(opt.isPresent(), "Unable to read file: " + pf);
        return opt.get();
    }

    private IAnalyzer.Range toRange(String src, int startChar, int endChar) {
        // For ASCII test sources, char index == byte index in UTF-8
        int startByte = startChar;
        int endByte = endChar;

        int startLine = countLinesUpTo(src, startChar);
        int endLine = countLinesUpTo(src, endChar);

        return new IAnalyzer.Range(startByte, endByte, startLine, endLine, startByte);
    }

    private int countLinesUpTo(String s, int endExclusive) {
        int count = 0;
        for (int i = 0; i < endExclusive && i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }
}
