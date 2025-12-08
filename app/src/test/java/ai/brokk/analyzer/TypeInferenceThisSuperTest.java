package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestProject;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for basic 'this.' and 'super.' member resolution via JavaAnalyzer.inferTypeAt.
 */
public class TypeInferenceThisSuperTest {

    @Test
    public void thisAndSuperMemberResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;
                public class Base {
                    protected int field;
                    public String method() { return \"\"; }
                }

                public class Derived extends Base {
                    public void m() {
                        int a = this.field;
                        String s = this.method();
                        this.method();
                        super.method();
                        super.field = 2;
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "X.java");
            var srcOpt = pf.read();
            assertTrue(srcOpt.isPresent(), "Source should be readable");
            String src = srcOpt.get();

            // Find offsets inside the identifier occurrences
            int idxThisField = src.indexOf("this.field");
            assertTrue(idxThisField >= 0, "Expected 'this.field' in sample");
            int offThisField = src.substring(0, idxThisField).getBytes(StandardCharsets.UTF_8).length + 6; // inside 'field'

            int idxThisMethod = src.indexOf("this.method()");
            assertTrue(idxThisMethod >= 0, "Expected 'this.method()' in sample");
            int offThisMethod = src.substring(0, idxThisMethod).getBytes(StandardCharsets.UTF_8).length + 6; // inside 'method'

            int idxSuperMethod = src.indexOf("super.method()");
            assertTrue(idxSuperMethod >= 0, "Expected 'super.method()' in sample");
            int offSuperMethod = src.substring(0, idxSuperMethod).getBytes(StandardCharsets.UTF_8).length + 6;

            int idxSuperField = src.indexOf("super.field");
            assertTrue(idxSuperField >= 0, "Expected 'super.field' in sample");
            int offSuperField = src.substring(0, idxSuperField).getBytes(StandardCharsets.UTF_8).length + 6;

            // this.field -> Base.field (inherited)
            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offThisField);
            assertTrue(r1.isPresent(), "this.field should resolve");
            assertEquals("p.Base.field", r1.get().fqName());

            // this.method() -> Base.method (inherited)
            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offThisMethod);
            assertTrue(r2.isPresent(), "this.method() should resolve");
            assertEquals("p.Base.method", r2.get().fqName());

            // super.method() -> Base.method
            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offSuperMethod);
            assertTrue(r3.isPresent(), "super.method() should resolve");
            assertEquals("p.Base.method", r3.get().fqName());

            // super.field -> Base.field
            Optional<CodeUnit> r4 = analyzer.inferTypeAt(pf, offSuperField);
            assertTrue(r4.isPresent(), "super.field should resolve");
            assertEquals("p.Base.field", r4.get().fqName());
        }
    }
}
