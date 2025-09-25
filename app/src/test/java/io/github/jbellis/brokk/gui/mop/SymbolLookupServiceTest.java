package io.github.jbellis.brokk.gui.mop;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SymbolLookupService improvements, particularly the findBestMatch logic that prioritizes exact matches over
 * substring matches.
 */
class SymbolLookupServiceTest {

    @Nested
    @DisplayName("findBestMatch method tests")
    class FindBestMatchTests {

        @Test
        @DisplayName("Should prioritize exact simple name matches over substring matches")
        void shouldPrioritizeExactSimpleNameMatches() {
            var searchResults = List.of(
                    createCodeUnit("io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer", "TreeSitterAnalyzer"),
                    createCodeUnit("io.github.jbellis.brokk.analyzer.CppTreeSitterAnalyzer", "CppTreeSitterAnalyzer"),
                    createCodeUnit(
                            "io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer", "JavaTreeSitterAnalyzer"));

            var bestMatch = invokePrivateFindBestMatch("TreeSitterAnalyzer", searchResults);

            assertEquals(
                    "io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer",
                    bestMatch.fqName(),
                    "Should select exact simple name match over substring matches");
        }

        @Test
        @DisplayName("Should prioritize exact simple name matches for TSParser over EditBlockConflictsParser")
        void shouldPrioritizeTSParserOverEditBlockConflictsParser() {
            // This test verifies the specific bug fix where searching for "TSParser" was returning
            // "EditBlockConflictsParser" because the old algorithm used substring matching and
            // EditBlockConflictsParser was found first (since it contains "TSParser")
            var searchResults = List.of(
                    createCodeUnit(
                            "io.github.jbellis.brokk.prompts.EditBlockConflictsParser", "EditBlockConflictsParser"),
                    createCodeUnit("org.treesitter.TSParser", "TSParser"),
                    createCodeUnit("some.other.package.CustomTSParser", "CustomTSParser"));

            var bestMatch = invokePrivateFindBestMatch("TSParser", searchResults);

            // The fixed algorithm should prioritize exact simple name match ("TSParser")
            // over substring match ("EditBlockConflictsParser" contains "TSParser")
            assertEquals(
                    "org.treesitter.TSParser",
                    bestMatch.fqName(),
                    "Should select TSParser over EditBlockConflictsParser which only contains 'TSParser'");

            // Verify it's not selecting the wrong one
            assertNotEquals(
                    "io.github.jbellis.brokk.prompts.EditBlockConflictsParser",
                    bestMatch.fqName(),
                    "Should NOT select EditBlockConflictsParser when searching for TSParser");
        }

        @Test
        @DisplayName("Should prefer shorter FQN when multiple exact matches exist")
        void shouldPreferShorterFqnForMultipleExactMatches() {
            var searchResults = List.of(
                    createCodeUnit("very.long.package.name.space.TreeSitterAnalyzer", "TreeSitterAnalyzer"),
                    createCodeUnit("short.TreeSitterAnalyzer", "TreeSitterAnalyzer"),
                    createCodeUnit("io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer", "TreeSitterAnalyzer"));

            var bestMatch = invokePrivateFindBestMatch("TreeSitterAnalyzer", searchResults);

            assertEquals(
                    "short.TreeSitterAnalyzer", bestMatch.fqName(), "Should prefer shortest FQN among exact matches");
        }

        @Test
        @DisplayName("Should use 'ends with' matching when no exact simple name match exists")
        void shouldUseEndsWithMatchingWhenNoExactMatch() {
            var searchResults = List.of(
                    createCodeUnit("io.github.jbellis.brokk.analyzer.CppTreeSitterAnalyzer", "CppTreeSitterAnalyzer"),
                    createCodeUnit("io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer", "JavaTreeSitterAnalyzer"),
                    createCodeUnit(
                            "io.github.jbellis.brokk.prompts.EditBlockConflictsParser", "EditBlockConflictsParser"));

            var bestMatch = invokePrivateFindBestMatch("TreeSitterAnalyzer", searchResults);

            // Should pick one of the TreeSitter classes since they end with the search term
            assertTrue(
                    bestMatch.fqName().endsWith("TreeSitterAnalyzer"),
                    "Should select a class that ends with the search term");
            assertFalse(
                    bestMatch.fqName().contains("EditBlockConflicts"),
                    "Should not select EditBlockConflictsParser which only contains the search term");
        }

        @Test
        @DisplayName("Should fallback to 'contains' matching with shortest FQN preference")
        void shouldFallbackToContainsMatching() {
            var searchResults = List.of(
                    createCodeUnit("very.long.package.name.ContainsAnalyzerInMiddle", "ContainsAnalyzerInMiddle"),
                    createCodeUnit("short.AnalyzerHelper", "AnalyzerHelper"),
                    createCodeUnit("io.github.jbellis.brokk.SomeAnalyzerClass", "SomeAnalyzerClass"));

            var bestMatch = invokePrivateFindBestMatch("Analyzer", searchResults);

            assertEquals(
                    "short.AnalyzerHelper",
                    bestMatch.fqName(),
                    "Should select shortest FQN when using fallback contains matching");
        }

        @Test
        @DisplayName("Should handle edge case with single character search terms")
        void shouldHandleSingleCharacterSearchTerms() {
            var searchResults = List.of(
                    createCodeUnit("io.github.jbellis.brokk.analyzer.A", "A"),
                    createCodeUnit("io.github.jbellis.brokk.analyzer.AnotherClass", "AnotherClass"),
                    createCodeUnit("io.github.jbellis.brokk.analyzer.SomeAClass", "SomeAClass"));

            var bestMatch = invokePrivateFindBestMatch("A", searchResults);

            assertEquals(
                    "io.github.jbellis.brokk.analyzer.A",
                    bestMatch.fqName(),
                    "Should select exact match for single character search term");
        }

        @Test
        @DisplayName("Should handle exact FQN matches correctly")
        void shouldHandleExactFqnMatches() {
            var searchResults = List.of(
                    createCodeUnit("io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer", "TreeSitterAnalyzer"));

            var bestMatch =
                    invokePrivateFindBestMatch("io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer", searchResults);

            assertEquals(
                    "io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer",
                    bestMatch.fqName(),
                    "Should handle exact FQN matches");
        }

        @Test
        @DisplayName("Should reject unreasonable fallback matches when simple name is much longer than search term")
        void shouldRejectUnreasonableFallbackMatches() {
            // Test the specific case: TSParser (8 chars) vs EditBlockConflictsParser (25 chars)
            // Since 25 > 8*3 (24), EditBlockConflictsParser should be filtered out in selective fallback
            var searchResults = List.of(createCodeUnit(
                    "io.github.jbellis.brokk.prompts.EditBlockConflictsParser", "EditBlockConflictsParser"));

            var bestMatch = invokePrivateFindBestMatch("TSParser", searchResults);

            // It should still return EditBlockConflictsParser, but only as unrestricted fallback
            assertEquals(
                    "io.github.jbellis.brokk.prompts.EditBlockConflictsParser",
                    bestMatch.fqName(),
                    "Should return EditBlockConflictsParser as unrestricted fallback when no reasonable matches exist");
        }

        @Test
        @DisplayName("Should allow reasonable fallback matches when simple name length is acceptable")
        void shouldAllowReasonableFallbackMatches() {
            // Test with a reasonable match: Parser (6 chars) vs TSParser (8 chars)
            // Since 8 <= 6*3 (18), TSParser should be allowed as a reasonable match
            var searchResults = List.of(
                    createCodeUnit("org.treesitter.TSParser", "TSParser"),
                    createCodeUnit(
                            "io.github.jbellis.brokk.prompts.EditBlockConflictsParser", "EditBlockConflictsParser"));

            var bestMatch = invokePrivateFindBestMatch("Parser", searchResults);

            // Should select TSParser as it's more reasonable than EditBlockConflictsParser
            assertEquals(
                    "org.treesitter.TSParser",
                    bestMatch.fqName(),
                    "Should prefer reasonable length matches in selective fallback");
        }
    }

    @Nested
    @DisplayName("findAllClassMatches method tests")
    class FindAllJavaClassMatchesTests {

        @Test
        @DisplayName("Should return all classes with exact simple name match")
        void shouldReturnAllExactClassMatches() {
            var searchResults = List.of(
                    createCodeUnit("com.example.Parser", "Parser"),
                    createCodeUnit("org.apache.commons.Parser", "Parser"),
                    createCodeUnit("my.project.data.Parser", "Parser"),
                    createCodeUnit("com.example.util.ParserUtil", "ParserUtil"), // Should NOT match
                    createCodeUnit("com.example.data.XmlParser", "XmlParser")); // Should NOT match

            var javaMatches = SymbolLookupService.findAllClassMatches("Parser", searchResults);

            assertEquals(3, javaMatches.size(), "Should find all 3 classes named 'Parser'");

            var fqns = javaMatches.stream().map(CodeUnit::fqName).toList();
            assertTrue(fqns.contains("com.example.Parser"));
            assertTrue(fqns.contains("org.apache.commons.Parser"));
            assertTrue(fqns.contains("my.project.data.Parser"));
            assertFalse(fqns.contains("com.example.util.ParserUtil"));
            assertFalse(fqns.contains("com.example.data.XmlParser"));
        }

        @Test
        @DisplayName("Should filter to classes only, not methods or fields")
        void shouldReturnOnlyClasses() {
            var searchResults = List.of(
                    createCodeUnit("com.example.Parser", "Parser"), // CLASS - should match
                    CodeUnit.fn(
                            new ProjectFile(
                                    Path.of(System.getProperty("java.io.tmpdir"))
                                            .resolve("test")
                                            .toAbsolutePath(),
                                    "Test.java"),
                            "com.example",
                            "Parser.parse"), // METHOD - should NOT match
                    CodeUnit.field(
                            new ProjectFile(
                                    Path.of(System.getProperty("java.io.tmpdir"))
                                            .resolve("test")
                                            .toAbsolutePath(),
                                    "Test.java"),
                            "com.example",
                            "Config.PARSER_ENABLED")); // FIELD - should NOT match

            var javaMatches = SymbolLookupService.findAllClassMatches("Parser", searchResults);

            assertEquals(1, javaMatches.size(), "Should find only the class, not methods or fields");
            assertEquals("com.example.Parser", javaMatches.get(0).fqName());
            assertTrue(javaMatches.get(0).isClass());
        }

        @Test
        @DisplayName("Should return empty list when no exact class matches exist")
        void shouldReturnEmptyWhenNoExactMatches() {
            var searchResults = List.of(
                    createCodeUnit("com.example.util.ParserUtil", "ParserUtil"),
                    createCodeUnit("com.example.data.XmlParser", "XmlParser"),
                    createCodeUnit("com.example.json.JsonParser", "JsonParser"));

            var javaMatches = SymbolLookupService.findAllClassMatches("Parser", searchResults);

            assertTrue(javaMatches.isEmpty(), "Should find no exact matches for 'Parser'");
        }
    }

    @Nested
    @DisplayName("getSimpleName method tests")
    class GetSimpleNameTests {

        @Test
        @DisplayName("Should extract simple name from fully qualified name")
        void shouldExtractSimpleNameFromFqn() {
            assertEquals(
                    "TreeSitterAnalyzer",
                    invokePrivateGetSimpleName("io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer"));
            assertEquals("String", invokePrivateGetSimpleName("java.lang.String"));
            assertEquals("TSParser", invokePrivateGetSimpleName("org.treesitter.TSParser"));
        }

        @Test
        @DisplayName("Should return original name when no package exists")
        void shouldReturnOriginalNameWhenNoPackage() {
            assertEquals("String", invokePrivateGetSimpleName("String"));
            assertEquals("Test", invokePrivateGetSimpleName("Test"));
        }

        @Test
        @DisplayName("Should handle edge cases")
        void shouldHandleEdgeCases() {
            assertEquals("", invokePrivateGetSimpleName(""));
            assertEquals("Test", invokePrivateGetSimpleName(".Test"));
            assertEquals("", invokePrivateGetSimpleName("com.example."));
        }
    }

    // Helper methods to create test data and invoke private methods

    private CodeUnit createCodeUnit(String fqName, String simpleName) {
        // Extract package name from FQN
        String packageName = "";
        int lastDot = fqName.lastIndexOf('.');
        if (lastDot >= 0) {
            packageName = fqName.substring(0, lastDot);
        }

        // Use an absolute path that works cross-platform
        Path absoluteTestPath =
                Path.of(System.getProperty("java.io.tmpdir")).resolve("test").toAbsolutePath();
        return new CodeUnit(
                new ProjectFile(absoluteTestPath, "Test.java"), CodeUnitType.CLASS, packageName, simpleName);
    }

    private CodeUnit invokePrivateFindBestMatch(String searchTerm, List<CodeUnit> searchResults) {
        try {
            var method = SymbolLookupService.class.getDeclaredMethod("findBestMatch", String.class, List.class);
            method.setAccessible(true);
            return (CodeUnit) method.invoke(null, searchTerm, searchResults);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke private method findBestMatch", e);
        }
    }

    private String invokePrivateGetSimpleName(String fqName) {
        try {
            var method = SymbolLookupService.class.getDeclaredMethod("getSimpleName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, fqName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke private method getSimpleName", e);
        }
    }
}
