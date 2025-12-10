package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ContextBuildRelatedIdentifiersTest {

    @Test
    void excludesAnonymousUnits() {
        var file = new ProjectFile(Path.of("/root"), "src/Foo.java");

        var foo = CodeUnit.cls(file, "com.acme", "Foo");
        var anonTop = CodeUnit.cls(file, "com.acme", "$anon$1");

        var bar = CodeUnit.fn(file, "com.acme", "Foo.bar");
        var anonChild = CodeUnit.fn(file, "com.acme", "Foo.$anon$0");

        var baz = CodeUnit.fn(file, "com.acme", "Foo.baz");
        var anonGrand = CodeUnit.fn(file, "com.acme", "Foo.$anon$2");

        IAnalyzer analyzer = new TestAnalyzer(List.of(foo, anonTop), Map.of()) {
            private final Map<CodeUnit, List<CodeUnit>> children = Map.of(
                    foo, List.of(bar, anonChild),
                    bar, List.of(baz, anonGrand));

            @Override
            public List<CodeUnit> getDirectChildren(CodeUnit cu) {
                return children.getOrDefault(cu, List.of());
            }
        };

        String out = Context.buildRelatedIdentifiers(analyzer, file);

        assertTrue(out.contains("- com.acme.Foo"), "should include top-level Foo");
        assertTrue(out.contains("  - bar"), "should include child bar");
        assertTrue(out.contains("    - baz"), "should include grandchild baz");
        assertFalse(out.contains("$anon$"), "must not contain any $anon$ entries");
    }
}
