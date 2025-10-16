package io.github.jbellis.brokk.analyzer.imports;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaImportTest {

    private TreeSitterAnalyzer createAnalyzer(IProject project) {
        return (TreeSitterAnalyzer) project.getBuildLanguage().createAnalyzer(project);
    }

    @Test
    public void testImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("""
                import foo.bar.Baz;
                import Bar;
                
                public class Foo {}
                """, "Foo.java").build()) {
            var analyzer = createAnalyzer(testProject);
            var file = analyzer.getFileFor("Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.Baz;", "import Bar;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

}
