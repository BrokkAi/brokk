package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestProject;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for qualified/static member resolution and constructor resolution via JavaAnalyzer.inferTypeAt.
 */
public class TypeInferenceQualifiedConstructorAndStaticTest {

    @Test
    public void staticAndConstructorResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;

                public class A {
                    public static int staticField;
                    public static String staticMethod() { return \"\"; }
                }

                public enum Status {
                    ACTIVE, INACTIVE;
                }

                public class Use {
                    public void test() {
                        int x = A.staticField;
                        String s = A.staticMethod();
                        Status st = Status.ACTIVE;
                        A a = new A();
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
            int idxStaticField = src.indexOf("A.staticField");
            assertTrue(idxStaticField >= 0, "Expected 'A.staticField' in sample");
            int offStaticField = src.substring(0, idxStaticField).getBytes(StandardCharsets.UTF_8).length + 2; // inside 'A'

            int idxStaticMethod = src.indexOf("A.staticMethod()");
            assertTrue(idxStaticMethod >= 0, "Expected 'A.staticMethod()' in sample");
            int offStaticMethod = src.substring(0, idxStaticMethod).getBytes(StandardCharsets.UTF_8).length + 2; // inside 'A'

            int idxEnumConst = src.indexOf("Status.ACTIVE");
            assertTrue(idxEnumConst >= 0, "Expected 'Status.ACTIVE' in sample");
            int offEnumConst = src.substring(0, idxEnumConst).getBytes(StandardCharsets.UTF_8).length + 3; // inside 'ACTIVE'

            int idxNew = src.indexOf("new A()");
            assertTrue(idxNew >= 0, "Expected 'new A()' in sample");
            int offNew = src.substring(0, idxNew).getBytes(StandardCharsets.UTF_8).length + 4; // inside 'A'

            // A.staticField -> p.A.staticField
            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offStaticField);
            assertTrue(r1.isPresent(), "A.staticField should resolve");
            assertEquals("p.A.staticField", r1.get().fqName());

            // A.staticMethod() -> p.A.staticMethod
            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offStaticMethod);
            assertTrue(r2.isPresent(), "A.staticMethod() should resolve");
            assertEquals("p.A.staticMethod", r2.get().fqName());

            // Status.ACTIVE -> p.Status.ACTIVE
            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offEnumConst);
            assertTrue(r3.isPresent(), "Status.ACTIVE should resolve");
            assertEquals("p.Status.ACTIVE", r3.get().fqName());

            // new A() -> p.A (constructor target resolves to class)
            Optional<CodeUnit> r4 = analyzer.inferTypeAt(pf, offNew);
            assertTrue(r4.isPresent(), "new A() should resolve to class");
            assertEquals("p.A", r4.get().fqName());
        }
    }
}
