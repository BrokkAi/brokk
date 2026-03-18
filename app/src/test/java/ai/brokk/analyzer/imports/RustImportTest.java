package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.analyzer.TypeAliasProvider;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class RustImportTest {

    @Test
    void testBasicImport() throws IOException {
        String code = "use std::collections::HashMap;";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        IAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("use std::collections::HashMap;"), imports);
    }

    @Test
    void testNestedImports() throws IOException {
        String code = "use std::collections::{HashMap, HashSet};";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        IAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        List<String> imports = analyzer.importStatementsOf(file);
        // Implementation flattens nested imports into discrete statements for better symbol resolution
        assertTrue(imports.contains("use std::collections::HashMap;"));
        assertTrue(imports.contains("use std::collections::HashSet;"));
    }

    @Test
    void testAliasedImport() throws IOException {
        String code = "use std::collections::HashMap as MyMap;";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        IAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("use std::collections::HashMap as MyMap;"), imports);
    }

    @Test
    void testWildcardImport() throws IOException {
        String code = "use std::collections::*;";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        IAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("use std::collections::*;"), imports);
    }

    @Test
    void testSelfInImports() throws IOException {
        String code = "use std::io::{self, Read, Write};";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        IAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        List<String> imports = analyzer.importStatementsOf(file);
        assertTrue(imports.contains("use std::io;"));
        assertTrue(imports.contains("use std::io::Read;"));
        assertTrue(imports.contains("use std::io::Write;"));
    }

    @Test
    void testTypeAlias() throws IOException {
        String code = "type MyResult<T> = Result<T, Error>;";
        IProject project = InlineTestProjectCreator.code(code, "src/main.rs").build();
        RustAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        CodeUnit aliasCu = analyzer.getDeclarations(file).stream()
                .filter(cu -> cu.identifier().equals("MyResult"))
                .findFirst()
                .orElseThrow();

        boolean isAlias = analyzer.as(TypeAliasProvider.class)
                .map(p -> p.isTypeAlias(aliasCu))
                .orElse(false);

        assertTrue(isAlias, "CodeUnit should be identified as a type alias");
    }

    @Test
    @Disabled("Semantic import resolution for Rust is pending")
    void testResolveImports_Semantic() throws IOException {
        IProject project = InlineTestProjectCreator.code("pub struct MyStruct;", "src/my_module.rs")
                .addFileContents(
                        """
                use crate::my_module::MyStruct;
                fn main() { let _s = MyStruct; }
                """,
                        "src/main.rs")
                .build();

        RustAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "src/main.rs");

        var resolved = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.importedCodeUnitsOf(mainFile))
                .orElseThrow();

        boolean found = resolved.stream().anyMatch(cu -> cu.isClass() && "MyStruct".equals(cu.shortName()));
        assertTrue(found, "Should have resolved to MyStruct in my_module");
    }

    @Test
    @Disabled("Semantic aliased import resolution for Rust is pending")
    void testResolveImports_Aliased() throws IOException {
        IProject project = InlineTestProjectCreator.code("pub struct TargetStruct;", "src/lib.rs")
                .addFileContents(
                        """
                use crate::TargetStruct as AliasStruct;
                fn main() { let _s = AliasStruct; }
                """,
                        "src/main.rs")
                .build();

        RustAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "src/main.rs");

        var resolved = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.importedCodeUnitsOf(mainFile))
                .orElseThrow();

        // The resolved CodeUnit should be the original definition (TargetStruct)
        boolean found = resolved.stream().anyMatch(cu -> cu.isClass() && "TargetStruct".equals(cu.shortName()));
        assertTrue(found, "Aliased import should resolve to the original target CodeUnit");
    }
}
