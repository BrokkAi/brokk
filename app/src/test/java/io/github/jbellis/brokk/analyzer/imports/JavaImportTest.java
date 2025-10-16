package io.github.jbellis.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaImportTest {

    private TreeSitterAnalyzer createAnalyzer(IProject project) {
        return (TreeSitterAnalyzer) project.getBuildLanguage().createAnalyzer(project);
    }

    @Test
    public void testOrdinaryImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.Baz;
                import Bar;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.Baz;", "import Bar;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testStaticImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import static foo.bar.Baz.method;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import static foo.bar.Baz.method;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testWildcardImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.*;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.*;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }
}
