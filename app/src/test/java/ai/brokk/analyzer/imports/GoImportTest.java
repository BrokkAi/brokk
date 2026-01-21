package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.GoAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoImportTest {

    @Test
    void testNoImports() throws IOException {
        String code = """
                package main
                func main() {}
                """;
        IProject project = InlineTestProjectCreator.code(code, "main.go").build();
        IAnalyzer analyzer = new GoAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "main.go");

        List<String> imports = analyzer.importStatementsOf(file);
        assertEquals(List.of(), imports);
    }

    @Test
    void testSingleImport() throws IOException {
        String code = """
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
        // Tree-sitter Go grammar treats the entire import (...) block as one import_declaration
        String code = """
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
        assertEquals(1, imports.size());
        assertEquals("""
                import (
                    "fmt"
                    "os"
                )""", imports.getFirst());
    }

    @Test
    void testAliasedImport() throws IOException {
        String code = """
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
        String code = """
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
        String code = """
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
    void testMultipleImportDeclarations() throws IOException {
        String code = """
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
        assertEquals(3, imports.size());
        assertEquals("import \"fmt\"", imports.get(0));
        assertEquals("""
                import (
                    "os"
                )""", imports.get(1));
        assertEquals("import _ \"net/http\"", imports.get(2));
    }
}
