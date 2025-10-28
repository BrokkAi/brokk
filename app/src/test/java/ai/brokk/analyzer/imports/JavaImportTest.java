package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaImportTest {

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
            var analyzer = createTreeSitterAnalyzer(testProject);
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
            var analyzer = createTreeSitterAnalyzer(testProject);
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
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.*;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }
}
