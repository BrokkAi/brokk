package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestProject;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Basic tests for TreeSitterAnalyzer.buildTypeInferenceContext.
 */
public class TypeInferenceContextTest {

    @Test
    public void buildContext_basic() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;
                public class L {
                    public void m(String param) {
                        int x = 1;
                    }
                }
                """,
                "L.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "L.java");
            var srcOpt = pf.read();
            assertTrue(srcOpt.isPresent(), "Source should be readable");
            String src = srcOpt.get();

            int idx = src.indexOf("int x = 1");
            assertTrue(idx >= 0, "Expected 'int x = 1' in sample source");

            int byteOffset = src.substring(0, idx).getBytes(StandardCharsets.UTF_8).length + 4; // inside 'x'

            var ctx = ((TreeSitterAnalyzer) analyzer).buildTypeInferenceContext(pf, byteOffset);
            assertNotNull(ctx, "Context should not be null");
            assertEquals(pf, ctx.file(), "Context file should match");
            assertEquals(byteOffset, ctx.offset(), "Context offset should match");

            // We expect to be inside a method and inside a class for this sample
            assertNotNull(ctx.enclosingClass(), "Expected enclosing class");
            assertNotNull(ctx.enclosingMethod(), "Expected enclosing method");

            // Visible imports should be present (may be empty set) but not null
            assertNotNull(ctx.visibleImports(), "visibleImports should not be null");
        }
    }
}
