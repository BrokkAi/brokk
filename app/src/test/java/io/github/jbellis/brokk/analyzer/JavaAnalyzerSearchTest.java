package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class JavaAnalyzerSearchTest {
    private static JavaAnalyzer javaAnalyzer;

    @BeforeAll
    public static void setup() {
        var javaTestProject = createTestProject("testcode-java", Language.JAVA);
        var tempCpgFile = Path.of(System.getProperty("java.io.tmpdir"), "brokk-java-search-test.bin");
        javaAnalyzer = new JavaAnalyzer(javaTestProject.getRoot(), Collections.emptySet(), tempCpgFile);
    }

    private static IProject createTestProject(String subDir, Language lang) {
        var testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), String.format("Test resource dir missing: %s", testDir));
        assertTrue(Files.isDirectory(testDir), String.format("%s is not a directory", testDir));
        
        return new IProject() {
            @Override
            public Path getRoot() {
                return testDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = testDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                    .map(file -> new ProjectFile(testDir, file.toPath()))
                    .collect(Collectors.toSet());
            }
        };
    }

    @Test
    public void testSearchDefinitions_BasicPatterns() {
        var eSymbols = javaAnalyzer.searchDefinitions("e");
        assertFalse(eSymbols.isEmpty(), "Should find symbols containing 'e'.");

        var eFqNames = eSymbols.stream()
            .filter(CodeUnit::isClass)
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(eFqNames.contains("E"), "Should find class 'E'");
        assertTrue(eFqNames.contains("UseE"), "Should find class 'UseE'");
        assertTrue(eFqNames.contains("AnonymousUsage"), "Should find class 'AnonymousUsage'");
        assertTrue(eFqNames.contains("Interface"), "Should find class 'Interface'");

        var method1Symbols = javaAnalyzer.searchDefinitions("method1");
        assertFalse(method1Symbols.isEmpty(), "Should find symbols containing 'method1'.");
        var method1FqNames = method1Symbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(method1FqNames.contains("A.method1"), "Should find 'A.method1'");

        var methodD1Symbols = javaAnalyzer.searchDefinitions("method.*1");
        assertFalse(methodD1Symbols.isEmpty(), "Should find symbols matching 'method.*1'.");
        var methodD1FqNames = methodD1Symbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(methodD1FqNames.contains("A.method1"), "Should find 'A.method1'");
        assertTrue(methodD1FqNames.contains("D.methodD1"), "Should find 'D.methodD1'");
    }

    @Test
    public void testSearchDefinitions_CaseInsensitive() {
        var upperE = javaAnalyzer.searchDefinitions("E");
        var lowerE = javaAnalyzer.searchDefinitions("e");

        var upperENames = upperE.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        var lowerENames = lowerE.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());

        assertEquals(upperENames, lowerENames, "Case-insensitive search: 'E' and 'e' should return identical results");
        assertTrue(upperENames.contains("E"), "Should find class 'E' regardless of pattern case");
        assertTrue(upperENames.contains("UseE"), "Should find class 'UseE' regardless of pattern case");
        assertTrue(upperENames.contains("Interface"), "Should find class 'Interface' regardless of pattern case");

        var mixedCase = javaAnalyzer.searchDefinitions("UsE");
        var mixedCaseNames = mixedCase.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertFalse(mixedCaseNames.isEmpty(), "Mixed case 'UsE' should find symbols containing 'UsE'");
        assertTrue(mixedCaseNames.contains("UseE"), "Should find 'UseE' with pattern 'UsE'");
        
        var lowerUse = javaAnalyzer.searchDefinitions("use");
        var lowerUseNames = lowerUse.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertEquals(mixedCaseNames, lowerUseNames, "Case-insensitive: 'UsE' and 'use' should return identical results");
    }

    @Test
    public void testSearchDefinitions_RegexPatterns() {
        var fieldSymbols = javaAnalyzer.searchDefinitions(".*field.*");
        assertFalse(fieldSymbols.isEmpty(), "Should find symbols containing 'field'.");

        var fieldFqNames = fieldSymbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(fieldFqNames.contains("D.field1"), "Should find 'D.field1'");
        assertTrue(fieldFqNames.contains("D.field2"), "Should find 'D.field2'");
        assertTrue(fieldFqNames.contains("E.iField"), "Should find 'E.iField'");
        assertTrue(fieldFqNames.contains("E.sField"), "Should find 'E.sField'");

        var methodSymbols = javaAnalyzer.searchDefinitions("method.*");
        var methodFqNames = methodSymbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(methodFqNames.contains("A.method1"), "Should find 'A.method1'");
        assertTrue(methodFqNames.contains("A.method2"), "Should find 'A.method2'");
        assertTrue(methodFqNames.contains("D.methodD1"), "Should find 'D.methodD1'");
        assertTrue(methodFqNames.contains("D.methodD2"), "Should find 'D.methodD2'");
    }

    @Test
    public void testSearchDefinitions_SpecificClasses() {
        var aSymbols = javaAnalyzer.searchDefinitions("A");
        var aClassNames = aSymbols.stream()
            .filter(CodeUnit::isClass)
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(aClassNames.contains("A"), String.format("Should find class 'A'. Found classes: %s", aClassNames));

        var baseClassSymbols = javaAnalyzer.searchDefinitions(".*Class");
        var baseClassNames = baseClassSymbols.stream()
            .filter(CodeUnit::isClass)
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(baseClassNames.contains("BaseClass"), String.format("Should find 'BaseClass'. Found: %s", baseClassNames));
        assertTrue(baseClassNames.contains("CamelClass"), String.format("Should find 'CamelClass'. Found: %s", baseClassNames));
    }

    @Test
    public void testSearchDefinitions_EmptyAndNonExistent() {
        var emptyPatternSymbols = javaAnalyzer.searchDefinitions("");
        assertTrue(emptyPatternSymbols.isEmpty(), "Empty pattern should return no results");

        var nonExistentSymbols = javaAnalyzer.searchDefinitions("NonExistentPatternXYZ123");
        assertTrue(nonExistentSymbols.isEmpty(), "Non-existent pattern should return no results");
    }

    @Test
    public void testSearchDefinitions_NestedClasses() {
        var innerSymbols = javaAnalyzer.searchDefinitions("Inner");
        assertFalse(innerSymbols.isEmpty(), "Should find nested classes containing 'Inner'");

        var innerFqNames = innerSymbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        assertTrue(innerFqNames.contains("A$AInner"), "Should find nested class 'A$AInner'");
        assertTrue(innerFqNames.contains("A$AInner$AInnerInner"), "Should find deeply nested class 'A$AInner$AInnerInner'");
    }

    @Test
    public void testSearchDefinitions_Constructors() {
        var constructorSymbols = javaAnalyzer.searchDefinitions("init");
        var constructorFqNames = constructorSymbols.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());

        System.out.println(String.format("Found constructor symbols: %s", constructorFqNames));

        if (!constructorSymbols.isEmpty()) {
            assertTrue(
                constructorSymbols.stream().anyMatch(symbol -> symbol.fqName().contains("init")),
                String.format("Should find symbols containing 'init'. Found: %s", constructorFqNames)
            );
        } else {
            System.out.println("No constructor symbols found - this will be compared with TreeSitter behavior");
        }
    }

    @Test
    public void testSearchDefinitions_PatternWrapping() {
        var methodSymbols1 = javaAnalyzer.searchDefinitions("method2");
        var methodSymbols2 = javaAnalyzer.searchDefinitions(".*method2.*");

        var method2Names1 = methodSymbols1.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());
        var method2Names2 = methodSymbols2.stream()
            .map(CodeUnit::fqName)
            .collect(Collectors.toSet());

        assertEquals(method2Names1, method2Names2, "Auto-wrapped pattern should match explicit pattern");
        assertTrue(method2Names1.contains("A.method2"), "Should find 'A.method2'");
    }

    @Test
    public void testGetDefinition() {
        var classDDef = javaAnalyzer.getDefinition("D");
        assertTrue(classDDef.isPresent(), "Should find definition for class 'D'");
        assertEquals("D", classDDef.get().fqName());
        assertTrue(classDDef.get().isClass());

        var method1Def = javaAnalyzer.getDefinition("A.method1");
        assertTrue(method1Def.isPresent(), "Should find definition for method 'A.method1'");
        assertEquals("A.method1", method1Def.get().fqName());
        assertTrue(method1Def.get().isFunction());

        var field1Def = javaAnalyzer.getDefinition("D.field1");
        assertTrue(field1Def.isPresent(), "Should find definition for field 'D.field1'");
        assertEquals("D.field1", field1Def.get().fqName());
        assertFalse(field1Def.get().isClass());
        assertFalse(field1Def.get().isFunction());

        var nonExistentDef = javaAnalyzer.getDefinition("NonExistentSymbol");
        assertFalse(nonExistentDef.isPresent(), "Should not find definition for non-existent symbol");
    }
}