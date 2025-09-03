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
 * reference detection and class name extraction.
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
        var analyzer = Language.JAVA.createAnalyzer(mockProject);

        // Valid Java method references
        assertEquals(Optional.of("MyClass"), analyzer.extractClassName("MyClass.myMethod"));
        assertEquals(Optional.of("com.example.MyClass"), analyzer.extractClassName("com.example.MyClass.myMethod"));
        assertEquals(Optional.of("java.lang.String"), analyzer.extractClassName("java.lang.String.valueOf"));
        assertEquals(Optional.of("List"), analyzer.extractClassName("List.get"));

        // Valid with camelCase/snake_case methods
        assertEquals(Optional.of("HttpClient"), analyzer.extractClassName("HttpClient.sendRequest"));
        assertEquals(Optional.of("StringBuilder"), analyzer.extractClassName("StringBuilder.append"));

        // Invalid cases - should return empty
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass"));
        assertEquals(Optional.empty(), analyzer.extractClassName("myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass."));
        assertEquals(Optional.empty(), analyzer.extractClassName(".myMethod"));
        assertEquals(Optional.empty(), analyzer.extractClassName(""));
        assertEquals(Optional.empty(), analyzer.extractClassName("   "));

        // Edge cases
        assertEquals(Optional.empty(), analyzer.extractClassName("myclass.myMethod")); // lowercase class
        assertEquals(Optional.empty(), analyzer.extractClassName("MyClass.MyMethod")); // uppercase method (not typical Java)
    }

    @Test
    @DisplayName("C++ analyzer - extractClassName with :: separator")
    void testCppAnalyzerExtractClassName() {
        var analyzer = new CppTreeSitterAnalyzer(mockProject, Set.of());

        // Valid C++ method references
        assertEquals(Optional.of("MyClass"), analyzer.extractClassName("MyClass::myMethod"));
        assertEquals(Optional.of("namespace::MyClass"), analyzer.extractClassName("namespace::MyClass::myMethod"));
        assertEquals(Optional.of("std::string"), analyzer.extractClassName("std::string::c_str"));
        assertEquals(Optional.empty(), analyzer.extractClassName("std::vector<int>::size")); // '<>' characters not in regex

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
    }

    @Test
    @DisplayName("Rust analyzer - extractClassName with :: separator")
    void testRustAnalyzerExtractClassName() {
        var analyzer = new RustAnalyzer(mockProject, Set.of());

        // Valid Rust method references
        assertEquals(Optional.of("MyStruct"), analyzer.extractClassName("MyStruct::new"));
        assertEquals(Optional.of("std::collections::HashMap"), analyzer.extractClassName("std::collections::HashMap::insert"));
        assertEquals(Optional.of("Vec"), analyzer.extractClassName("Vec::push"));

        // Snake case methods (typical in Rust)
        assertEquals(Optional.of("HttpClient"), analyzer.extractClassName("HttpClient::send_request"));
        assertEquals(Optional.of("std::fs::File"), analyzer.extractClassName("std::fs::File::create_new"));

        // Module paths
        assertEquals(Optional.of("crate::utils::Helper"), analyzer.extractClassName("crate::utils::Helper::do_something"));

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
    @DisplayName("Default analyzer - extractClassName with Java-like behavior")
    void testDefaultAnalyzerExtractClassName() {
        // Use DisabledAnalyzer which uses default implementation
        var analyzer = new DisabledAnalyzer();

        // Should throw UnsupportedOperationException (new default behavior)
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass.myMethod"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("com.example.Service.process"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName(""));
    }

    @Test
    @DisplayName("JavaScript analyzer - extractClassName with . separator")
    void testJavaScriptAnalyzerExtractClassName() {
        var analyzer = new JavascriptAnalyzer(mockProject, Set.of());

        // JavaScript uses default implementation (throws UnsupportedOperationException)
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass.myMethod"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("console.log"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("JSON.stringify"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("Array.from"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("fs.readFile"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("path.join"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("myMethod"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName(""));
    }

    @Test
    @DisplayName("TypeScript analyzer - extractClassName with . separator")
    void testTypeScriptAnalyzerExtractClassName() {
        var analyzer = new TypescriptAnalyzer(mockProject, Set.of());

        // TypeScript uses default implementation (throws UnsupportedOperationException)
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass.myMethod"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("Array.isArray"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("Promise.resolve"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("React.Component.render"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("MyClass"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName("myMethod"));
        assertThrows(UnsupportedOperationException.class, () -> analyzer.extractClassName(""));
    }

    @Test
    @DisplayName("Edge cases - whitespace and special characters")
    void testEdgeCases() {
        var javaAnalyzer = Language.JAVA.createAnalyzer(mockProject);
        var cppAnalyzer = new CppTreeSitterAnalyzer(mockProject, Set.of());

        // Whitespace handling
        assertEquals(Optional.of("MyClass"), javaAnalyzer.extractClassName("  MyClass.myMethod  "));
        assertEquals(Optional.of("MyClass"), cppAnalyzer.extractClassName("  MyClass::myMethod  "));

        // Multiple separators
        assertEquals(Optional.of("ns1::ns2::Class"), cppAnalyzer.extractClassName("ns1::ns2::Class::method"));
        assertEquals(Optional.of("com.example.deep.Class"), javaAnalyzer.extractClassName("com.example.deep.Class.method"));

        // Empty parts
        assertEquals(Optional.empty(), javaAnalyzer.extractClassName("..method"));
        // Fix the C++ test - need valid identifiers before ::
        assertEquals(Optional.empty(), cppAnalyzer.extractClassName("::method")); // starts with ::
    }
}
