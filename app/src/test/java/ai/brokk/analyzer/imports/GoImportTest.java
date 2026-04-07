package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.GoAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoImportTest {

    @Test
    void testResolveImports_NoImports() throws IOException {
        String code = """
                package main
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(file);
        assertTrue(resolved.isEmpty(), "Expected no resolved imports for file with no imports");
    }

    @Test
    void testResolveImports_StandardImport() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println(a ...any) {}
                """,
                        "fmt/print.go")
                .addFileContents(
                        """
                package main
                import "fmt"
                func main() { fmt.Println("hi") }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);

        // Should resolve to the Println function in the fmt package
        boolean found = resolved.stream()
                .anyMatch(cu -> cu.isFunction() && "Println".equals(cu.shortName()) && "fmt".equals(cu.packageName()));
        assertTrue(found, "Should have resolved to fmt.Println");
    }

    @Test
    void testResolveImports_GroupedImports() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package os
                func Exit(code int) {}
                """,
                        "os/os.go")
                .addFileContents(
                        """
                package main
                import (
                    "fmt"
                    "os"
                )
                func main() { fmt.Println(); os.Exit(0) }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);

        boolean foundFmt = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        boolean foundOs = resolved.stream().anyMatch(cu -> "os".equals(cu.packageName()));

        assertTrue(foundFmt, "Should resolve fmt package from group");
        assertTrue(foundOs, "Should resolve os package from group");
    }

    @Test
    void testResolveImports_BlankImportSkipped() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package png
                func Decode() {}
                """,
                        "image/png/png.go")
                .addFileContents(
                        """
                package main
                import _ "image/png"
                func main() {}
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        assertTrue(resolved.isEmpty(), "Blank import should not resolve to CodeUnits");
    }

    @Test
    void testResolveImports_AliasedImport() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package main
                import f "fmt"
                func main() { f.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean found = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        assertTrue(found, "Aliased import should still resolve the underlying package symbols");
    }

    @Test
    void testResolveImports_DotImport() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package main
                import . "fmt"
                func main() { Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean found = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        assertTrue(found, "Dot import should resolve the underlying package symbols");
    }

    @Test
    void testResolveImports_BacktickQuotedPath() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package main
                import `fmt`
                func main() { fmt.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean found = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        assertTrue(found, "Backtick-quoted import should resolve the package symbols");
    }

    @Test
    void testResolveImports_CommentedImportIgnored() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package os
                func Exit(code int) {}
                """,
                        "os/os.go")
                .addFileContents(
                        """
                package main
                import (
                    "fmt"
                    // "os"
                )
                func main() { fmt.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean foundFmt = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        boolean foundOs = resolved.stream().anyMatch(cu -> "os".equals(cu.packageName()));

        assertTrue(foundFmt, "Non-commented import should be resolved");
        assertFalse(foundOs, "Commented import should not be resolved");
    }

    @Test
    void testResolveImports_BlockCommentIgnored() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package os
                func Exit(code int) {}
                """,
                        "os/os.go")
                .addFileContents(
                        """
                package main
                import (
                    "fmt"
                    /* "os" */
                )
                func main() { fmt.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean foundFmt = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        boolean foundOs = resolved.stream().anyMatch(cu -> "os".equals(cu.packageName()));

        assertTrue(foundFmt, "Non-commented import should be resolved");
        assertFalse(foundOs, "Block-commented import should not be resolved");
    }

    @Test
    void testResolveImports_AliasWithComment() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println() {}
                """,
                        "fmt/fmt.go")
                .addFileContents(
                        """
                package main
                import f /* alias */ "fmt"
                func main() { f.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean found = resolved.stream().anyMatch(cu -> "fmt".equals(cu.packageName()));
        assertTrue(found, "Import with comment between alias and path should still resolve");
    }

    @Test
    void testResolveImports_VersionedImportPath() throws IOException {
        // Create a project where gopkg.in/yaml.v3 maps to a directory containing package yaml
        IProject project = InlineTestProjectCreator.code(
                        """
                package yaml
                func Marshal(in any) ([]byte, error) { return nil, nil }
                """,
                        "vendor/gopkg.in/yaml.v3/yaml.go")
                .addFileContents(
                        """
                package main
                import "gopkg.in/yaml.v3"
                func main() { yaml.Marshal(nil) }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);

        // Should resolve to package "yaml", NOT "yaml.v3"
        boolean foundCorrectPackage =
                resolved.stream().anyMatch(cu -> "yaml".equals(cu.packageName()) && "Marshal".equals(cu.shortName()));
        boolean foundIncorrectPackage = resolved.stream().anyMatch(cu -> "yaml.v3".equals(cu.packageName()));

        assertTrue(foundCorrectPackage, "Should resolve to package 'yaml' by reading source file");
        assertFalse(
                foundIncorrectPackage,
                "Should not resolve to 'yaml.v3' via last-segment heuristic when source is available");
    }

    @Test
    void testResolveImports_DoesNotMatchStdlibByLastSegmentAlone() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package demo

                import "io/fs"

                type Context struct {
                    FS fs.FS
                }
                """,
                        "context.go")
                .addFileContents(
                        """
                package fs

                type FileSystem struct{}
                """,
                        "internal/fs/fs.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "context.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean foundInternalFs = resolved.stream()
                .anyMatch(cu -> cu.source().getRelPath().toString().equals("internal/fs/fs.go"));
        assertFalse(foundInternalFs, "stdlib import io/fs should not resolve to project internal/fs");
    }

    @Test
    void testResolveImports_ModulePrefixedProjectImport() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fs

                type FileSystem struct{}
                """,
                        "internal/fs/fs.go")
                .addFileContents(
                        """
                package main

                import filesystem "github.com/gin-gonic/gin/internal/fs"

                type Engine struct {
                    FS filesystem.FileSystem
                }
                """,
                        "gin.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "gin.go");

        Set<CodeUnit> resolved = analyzer.importedCodeUnitsOf(mainFile);
        boolean foundInternalFs = resolved.stream()
                .anyMatch(cu -> cu.source().getRelPath().toString().equals("internal/fs/fs.go"));
        assertTrue(foundInternalFs, "module-prefixed project import should resolve to internal/fs");
    }

    @Test
    void testSingleImport() throws IOException {
        String code =
                """
                package main
                import "fmt"
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("import \"fmt\""), imports);
    }

    @Test
    void testGroupedImports() throws IOException {
        String code =
                """
                package main
                import (
                    "fmt"
                    "os"
                )
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(2, imports.size());
        assertEquals("import \"fmt\"", imports.get(0));
        assertEquals("import \"os\"", imports.get(1));
    }

    @Test
    void testAliasedImport() throws IOException {
        String code =
                """
                package main
                import f "fmt"
                func main() { f.Println("hello") }
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("import f \"fmt\""), imports);
    }

    @Test
    void testDotImport() throws IOException {
        String code =
                """
                package main
                import . "fmt"
                func main() { Println("hello") }
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("import . \"fmt\""), imports);
    }

    @Test
    void testBlankImport() throws IOException {
        String code =
                """
                package main
                import _ "image/png"
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of("import _ \"image/png\""), imports);
    }

    @Test
    void testRelevantImportsForFunction() throws IOException {
        IProject project = InlineTestProjectCreator.code(
                        """
                package fmt
                func Println(a ...any) {}
                """,
                        "fmt/print.go")
                .addFileContents(
                        """
                package main
                import "fmt"
                func main() { fmt.Println("hi") }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");
        var mainFn = analyzer.getDeclarations(mainFile).stream()
                .filter(cu -> cu.identifier().equals("main"))
                .findFirst()
                .orElseThrow();

        Set<String> relevant = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.relevantImportsFor(mainFn))
                .orElse(Set.of());

        assertTrue(relevant.contains("import \"fmt\""), "Should include fmt import used in main");
    }

    @Test
    void testRelevantImportsExcludesUnused() throws IOException {
        IProject project = InlineTestProjectCreator.code("package fmt\nfunc Println() {}", "fmt/f.go")
                .addFileContents("package os\nfunc Exit(i int) {}", "os/o.go")
                .addFileContents(
                        """
                package main
                import "fmt"
                import "os"
                func main() { fmt.Println() }
                """,
                        "main.go")
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile mainFile = new ProjectFile(project.getRoot(), "main.go");
        var mainFn = analyzer.getDeclarations(mainFile).stream()
                .filter(cu -> cu.identifier().equals("main"))
                .findFirst()
                .orElseThrow();

        Set<String> relevant = analyzer.as(ImportAnalysisProvider.class)
                .map(p -> p.relevantImportsFor(mainFn))
                .orElse(Set.of());

        assertTrue(relevant.contains("import \"fmt\""), "Should include fmt import");
        assertFalse(relevant.contains("import \"os\""), "Should NOT include unused os import");
    }

    @Test
    void testBlankImportRawSnippetPreserved() throws IOException {
        String code =
                """
                package main
                import _ "image/png"
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<ImportInfo> infos = analyzer.importInfoOf(file);
        assertEquals(1, infos.size());
        assertEquals("import _ \"image/png\"", infos.get(0).rawSnippet());
        assertEquals("_", infos.get(0).identifier());
    }

    @Test
    void testDotImportRawSnippetPreserved() throws IOException {
        String code =
                """
                package main
                import . "fmt"
                func main() { Println("hello") }
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<ImportInfo> infos = analyzer.importInfoOf(file);
        assertEquals(1, infos.size());
        assertEquals("import . \"fmt\"", infos.get(0).rawSnippet());
        assertEquals(".", infos.get(0).identifier());
    }

    @Test
    void testAliasedImportRawSnippetPreserved() throws IOException {
        String code =
                """
                package main
                import f "fmt"
                func main() { f.Println("hello") }
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        GoAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<ImportInfo> infos = analyzer.importInfoOf(file);
        assertEquals(1, infos.size());
        assertEquals("import f \"fmt\"", infos.get(0).rawSnippet());
        assertEquals("f", infos.get(0).identifier());
        assertEquals("f", infos.get(0).alias());
    }

    @Test
    void testMultipleImportDeclarations() throws IOException {
        String code =
                """
                package main
                import "fmt"
                import (
                    "os"
                )
                import _ "net/http"
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        // Expecting individual imports even from groups
        assertEquals(3, imports.size());
        assertEquals("import \"fmt\"", imports.get(0));
        assertEquals("import \"os\"", imports.get(1));
        assertEquals("import _ \"net/http\"", imports.get(2));
    }

    @Test
    void testCouldImportFile_matchesFilesInImportPath() throws Exception {
        IProject project = InlineTestProjectCreator.code(
                        """
                package main
                import "myproject/pkg/utils"
                func main() {}
                """,
                        "main.go")
                .addFileContents("package utils", "pkg/utils/helper.go")
                .build();

        TreeSitterAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile sourceFile = new ProjectFile(project.getRoot(), "main.go");
        ProjectFile targetFile = new ProjectFile(project.getRoot(), "pkg/utils/helper.go");

        List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);
        boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

        assertTrue(result, "Should match file in the imported package path");
    }

    @Test
    void testCouldImportFile_standardLibraryReturnsFalse() throws Exception {
        IProject project = InlineTestProjectCreator.code(
                        """
                package main
                import "fmt"
                func main() {}
                """,
                        "main.go")
                .build();

        TreeSitterAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile sourceFile = new ProjectFile(project.getRoot(), "main.go");
        ProjectFile targetFile = new ProjectFile(project.getRoot(), "main.go");

        List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);
        boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

        assertFalse(result, "Standard library import should not match project files");
    }

    @Test
    void testCouldImportFile_aliasedImportWorks() throws Exception {
        IProject project = InlineTestProjectCreator.code(
                        """
                package main
                import f "myproject/pkg/utils"
                func main() {}
                """,
                        "main.go")
                .addFileContents("package utils", "pkg/utils/helper.go")
                .build();

        TreeSitterAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile sourceFile = new ProjectFile(project.getRoot(), "main.go");
        ProjectFile targetFile = new ProjectFile(project.getRoot(), "pkg/utils/helper.go");

        List<ImportInfo> imports = analyzer.importInfoOf(sourceFile);
        boolean result = analyzer.couldImportFile(sourceFile, imports, targetFile);

        assertTrue(result, "Aliased import should still match file in the imported package path");
    }
}
