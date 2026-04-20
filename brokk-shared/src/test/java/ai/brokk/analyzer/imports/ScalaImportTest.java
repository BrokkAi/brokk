package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ScalaImportTest {

    @Test
    public void testOrdinaryImport() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                import foo.bar.Baz
                import Bar

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.Baz", "import Bar");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testStaticImport() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                import foo.bar.{Baz as Bar}

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.{Baz as Bar}");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testWildcardImport() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                import foo.bar.*

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.*");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }
}
