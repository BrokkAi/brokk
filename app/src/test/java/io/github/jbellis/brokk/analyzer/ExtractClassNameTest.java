package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for extractClassName method across all analyzer implementations. Tests language-specific method
 * reference detection and class name extraction. Tests are written against the current heuristic logic and verify known
 * edge cases.
 */
public class ExtractClassNameTest {

    private static class MockProject implements IProject {
        public Path getRoot() {
            return Path.of("/test");
        }

        public String getName() {
            return "test";
        }

        public Set<String> getExcludedDirectories() {
            return Set.of();
        }
    }

    private final IProject mockProject = new MockProject();

    @Test
    @DisplayName("Java analyzer - extractClassName with various method references")
    void testJavaAnalyzerExtractClassName() {
        var analyzer = Languages.JAVA.createAnalyzer(mockProject);

        // Valid Java method references
        assertEquals(Optional.of("MyClass"), analyzer.extractClassName("MyClass.myMethod"));
        assertEquals(Optional.of("com.example.MyClass"), analyzer.extractClassName("com.example.MyClass.myMethod"));
        assertEquals(Optional.of("java.lang.String"), analyzer.extractClassName("java.lang.String.valueOf"));
        assertEquals(Optional.of("List"), analyzer.extractClassName("List.get"));

        // Valid with camelCase methods
        assertEquals(Optional.of("HttpClient"), analyzer.extractClassName("HttpClient.sendRequest"));
        assertEquals(Optional.of("StringBuilder"), analyzer.extractClassName("StringBuilder.append"));

        // New: Method calls with parameters
        assertEquals(Optional.of("SwingUtil"), analyzer.extractClassName("SwingUtil.runOnEdt(...)"));
        assertEquals(Optional.of("SwingUtilities"), analyzer.extractClassName("SwingUtilities.invokeLater(task)"));
        assertEquals(Optional.of("EventQueue"), analyzer.extractClassName("EventQueue.invokeAndWait(runnable)"));
        assertEquals(
                Optional.of("JOptionPane"),
                analyzer.extractClassName("JOptionPane.showMessageDialog(parent, message)"));

        // Test case for GitRepo.sanitizeBranchName(...)
        assertEquals(Optional.of("GitRepo"), analyzer.extractClassName("GitRepo.sanitizeBranchName(...)"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractClassName("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractClassName(".myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
        assertEquals(Optional.empty(), analyzer.extractClassName("   "));

        // Edge cases consistent with heuristic
        assertEquals(Optional.empty(), analyzer.extractClassName("myclass.myMethod")); // lowercase class
        assertEquals(
                Optional.empty(), analyzer.extractClassName("MyClass.MyMethod")); // uppercase method (not typical Java)

        // Inner-class style using $ is not recognized by this heuristic (tests ensure no exception)
        assertEquals(Optional.empty(), analyzer.extractClassName("com.example.Outer$Inner.method"));

        // Unicode names - heuristic limited to ASCII-style checks
        assertEquals(Optional.empty(), analyzer.extractClassName("ÜnicodeClass.method"));
    }

    @Test
    @DisplayName("C++ analyzer - extractClassName with :: separator and templates")
    void testCppAnalyzerExtractClassName() {
        var analyzer = new CppTreeSitterAnalyzer(mockProject, Set.of());

        // Valid C++ method references
        assertEquals(Optional.of("MyClass"), analyzer.extractClassName("MyClass::myMethod"));
        assertEquals(Optional.of("namespace::MyClass"), analyzer.extractClassName("namespace::MyClass::myMethod"));
        assertEquals(Optional.of("std::string"), analyzer.extractClassName("std::string::c_str"));

        // Templates are stripped/unsupported by regex heuristic -> should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("std::vector<int>::size"));

        // Nested namespaces
        assertEquals(Optional.of("ns1::ns2::Class"), analyzer.extractClassName("ns1::ns2::Class::method"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractClassName("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass::"));
        assertEquals(Optional.empty(), analyzer.extractClassName("::myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
        assertEquals(Optional.empty(), analyzer.extractClassName("   "));

        // C++ doesn't use dots for method references
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass.myMethod"));

        // Dollar-sign odd identifiers are not supported by the simple heuristic
        assertEquals(Optional.empty(), analyzer.extractClassName("ns$::Class::method"));
    }

    @Test
    @DisplayName("Rust analyzer - extractClassName with :: separator")
    void testRustAnalyzerExtractClassName() {
        var analyzer = new RustAnalyzer(mockProject, Set.of());

        // Valid Rust method references
        assertEquals(Optional.of("MyStruct"), analyzer.extractClassName("MyStruct::new"));
        assertEquals(
                Optional.of("std::collections::HashMap"),
                analyzer.extractClassName("std::collections::HashMap::insert"));
        assertEquals(Optional.of("Vec"), analyzer.extractClassName("Vec::push"));

        // Snake case methods (typical in Rust)
        assertEquals(Optional.of("HttpClient"), analyzer.extractClassName("HttpClient::send_request"));
        assertEquals(Optional.of("std::fs::File"), analyzer.extractClassName("std::fs::File::create_new"));

        // Module paths
        assertEquals(
                Optional.of("crate::utils::Helper"), analyzer.extractClassName("crate::utils::Helper::do_something"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("MyStruct"));
        assertEquals(Optional.empty(), analyzer.extractClassName("new"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyStruct::"));
        assertEquals(Optional.empty(), analyzer.extractClassName("::new"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
        assertEquals(Optional.empty(), analyzer.extractClassName("   "));

        // Rust doesn't use dots for method references
        assertEquals(Optional.empty(), analyzer.extractClassName("MyStruct.new"));
    }

    @Test
    @DisplayName("Python analyzer - extractClassName with . separator")
    void testPythonAnalyzerExtractClassName() {
        var analyzer = new PythonAnalyzer(mockProject, Set.of());

        // Valid Python method references
        assertEquals(Optional.of("MyClass"), analyzer.extractClassName("MyClass.my_method"));
        assertEquals(Optional.of("requests.Session"), analyzer.extractClassName("requests.Session.get"));
        assertEquals(Optional.of("os.path"), analyzer.extractClassName("os.path.join"));

        // Mixed case
        assertEquals(Optional.of("HttpClient"), analyzer.extractClassName("HttpClient.send_request"));
        assertEquals(Optional.of("json"), analyzer.extractClassName("json.loads"));

        // Module paths
        assertEquals(Optional.of("package.module.Class"), analyzer.extractClassName("package.module.Class.method"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractClassName("my_method"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractClassName(".my_method"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
        assertEquals(Optional.empty(), analyzer.extractClassName("   "));
    }

    @Test
    @DisplayName("Default analyzer - extractClassName returns empty by default")
    void testDefaultAnalyzerExtractClassName() {
        // Use DisabledAnalyzer which uses default implementation
        var analyzer = new DisabledAnalyzer();

        // Default is now Optional.empty() and should not throw
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass.myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName("com.example.Service.process"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
    }

    @Test
    @DisplayName("JavaScript/TypeScript analyzers - extract class names correctly")
    void testJsTsExtractClassName() {
        var js = new JavascriptAnalyzer(mockProject, Set.of());
        var ts = new TypescriptAnalyzer(mockProject, Set.of());

        // These should return empty due to lowercase anchors (heuristic for built-ins)
        assertEquals(Optional.empty(), js.extractClassName("console.log"));
        assertEquals(Optional.empty(), ts.extractClassName("document.querySelector"));

        // Built-in constructors with PascalCase should be extracted (they are legitimate classes)
        assertEquals(Optional.of("Array"), ts.extractClassName("Array.isArray"));

        // These should extract PascalCase class names
        assertEquals(Optional.of("MyClass"), js.extractClassName("MyClass.myMethod"));
        assertEquals(Optional.of("Component"), ts.extractClassName("React.Component.render"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - extracts from optional chaining")
    void testJsTsExtractsFromOptionalChaining() {
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass?.doWork()"));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("  MyClass?.doWork(arg1, arg2)  "));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass?.doWork"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - extracts from prototype chain")
    void testJsTsExtractsFromPrototypeChain() {
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array.prototype.map"));
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array.prototype['map']"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - picks rightmost PascalCase before method")
    void testJsTsPicksRightmostPascalCaseBeforeMethod() {
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyNamespace.MyClass.run"));
        assertEquals(Optional.of("Observable"), ClassNameExtractor.extractForJsTs("rxjs.Observable.of"));
        assertEquals(Optional.of("SwingUtilities"), ClassNameExtractor.extractForJsTs("SwingUtilities.invokeLater"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - handles generics and type args")
    void testJsTsHandlesGenericsAndTypeArgs() {
        assertEquals(Optional.of("Map"), ClassNameExtractor.extractForJsTs("Map<string, number>.set"));
        assertEquals(
                Optional.of("Map"),
                ClassNameExtractor.extractForJsTs("  Map < string , Array<number> >  . set ( 'k', 1 ) "));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method<T>()"));
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method<T extends Foo, U>()"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - normalizes bracket property access")
    void testJsTsNormalizesBracketPropertyAccess() {
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo['bar']()"));
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo[\"bar\"]"));
        assertEquals(Optional.of("Foo"), ClassNameExtractor.extractForJsTs("Foo['bar'].baz"));
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - does not extract lowercase anchors")
    void testJsTsDoesNotExtractLowercaseAnchors() {
        assertTrue(ClassNameExtractor.extractForJsTs("console.log").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("document.querySelector").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("window.fetch").isEmpty());
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - bare PascalCase now matches")
    void testJsTsBarePascalCaseNowMatches() {
        // Bare PascalCase class names should now be extracted
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore"));
        assertEquals(Optional.of("BubbleState"), ClassNameExtractor.extractForJsTs("BubbleState"));
        assertEquals(Optional.of("Array"), ClassNameExtractor.extractForJsTs("Array"));
        assertEquals(Optional.of("ResultMsg"), ClassNameExtractor.extractForJsTs("ResultMsg"));

        // Dotted references still work as before
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save"));
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save(...)"));
        assertEquals(Optional.of("BubleStore"), ClassNameExtractor.extractForJsTs("BubleStore.save(arg)"));

        // Still reject non-PascalCase bare names
        assertTrue(ClassNameExtractor.extractForJsTs("console").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("document").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("window").isEmpty());
        assertTrue(ClassNameExtractor.extractForJsTs("camelCase").isEmpty());
    }

    @Test
    @DisplayName("JS/TS extractForJsTs - respects parentheses when finding last dot")
    void testJsTsRespectsParenthesesWhenFindingLastDot() {
        // The last dot outside parens is between "MyClass" and "method"
        assertEquals(
                Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method(call.with.dots(and, more))"));
        // Method without call still ok
        assertEquals(Optional.of("MyClass"), ClassNameExtractor.extractForJsTs("MyClass.method   "));
    }

    @Test
    @DisplayName("Edge cases - whitespace and special characters")
    void testEdgeCases() {
        var javaAnalyzer = Languages.JAVA.createAnalyzer(mockProject);
        var cppAnalyzer = new CppTreeSitterAnalyzer(mockProject, Set.of());

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), javaAnalyzer.extractClassName("  MyClass.myMethod  "));
        assertEquals(Optional.of("MyClass"), cppAnalyzer.extractClassName("  MyClass::myMethod  "));

        // Multiple separators
        assertEquals(Optional.of("ns1::ns2::Class"), cppAnalyzer.extractClassName("ns1::ns2::Class::method"));
        assertEquals(
                Optional.of("com.example.deep.Class"), javaAnalyzer.extractClassName("com.example.deep.Class.method"));

        // Empty parts
        assertEquals(Optional.empty(), javaAnalyzer.extractClassName("..method"));
        // C++ starts-with-:: remains empty
        assertEquals(Optional.empty(), cppAnalyzer.extractClassName("::method")); // starts with ::
    }
}
