package io.github.jbellis.brokk.analyzer.imports;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ScalaImportTest {

    @Test
    public void testOrdinaryImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.Baz
                import Bar

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.Baz", "import Bar");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testStaticImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.{Baz as Bar}

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.{Baz as Bar}");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testWildcardImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.*

                class Foo
                """,
                        "Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.*");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }
}
