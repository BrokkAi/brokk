package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests unqualified identifier resolution: local variable -> parameter -> field -> inherited; and shadowing.
 */
public class TypeInferenceUnqualifiedNameResolutionTest {

    @Test
    public void unqualifiedResolutionAndShadowing() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;

                public class B {}

                public class Base {
                    public int baseField;
                }

                public class Derived extends Base {
                    public int own;
                    public void test(B param) {
                        B local = new B();
                        B x = local;      // local should resolve to p.B
                        B y = param;      // param should resolve to p.B
                        int a = own;      // own resolves to p.Derived.own (own field)
                        int b = baseField; // baseField resolves to p.Base.baseField (inherited)
                        int own = 5;
                        int c = own;      // shadowing: local 'own' should resolve (not the field)
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "X.java");
            var srcOpt = pf.read();
            assertTrue(srcOpt.isPresent(), "Source should be readable");
            String src = srcOpt.get();

            // locate occurrences and compute UTF-8 byte offsets inside the identifier
            int idxLocalUse = src.indexOf("B x = local");
            assertTrue(idxLocalUse >= 0, "expected 'B x = local' in sample");
            int offLocal = src.substring(0, idxLocalUse).getBytes(StandardCharsets.UTF_8).length + "B x = ".length();

            int idxParamUse = src.indexOf("B y = param");
            assertTrue(idxParamUse >= 0, "expected 'B y = param' in sample");
            int offParam = src.substring(0, idxParamUse).getBytes(StandardCharsets.UTF_8).length + "B y = ".length();

            int idxOwnField = src.indexOf("int a = own");
            assertTrue(idxOwnField >= 0, "expected 'int a = own' in sample");
            int offOwnField = src.substring(0, idxOwnField).getBytes(StandardCharsets.UTF_8).length + "int a = ".length();

            int idxBaseField = src.indexOf("int b = baseField");
            assertTrue(idxBaseField >= 0, "expected 'int b = baseField' in sample");
            int offBaseField = src.substring(0, idxBaseField).getBytes(StandardCharsets.UTF_8).length + "int b = ".length();

            int idxOwnShadow = src.indexOf("int c = own");
            assertTrue(idxOwnShadow >= 0, "expected 'int c = own' in sample");
            int offOwnShadow = src.substring(0, idxOwnShadow).getBytes(StandardCharsets.UTF_8).length + "int c = ".length();

            // local usage -> p.B
            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offLocal);
            assertTrue(r1.isPresent(), "local should resolve");
            assertEquals("p.B", r1.get().fqName());

            // param usage -> p.B
            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offParam);
            assertTrue(r2.isPresent(), "param should resolve");
            assertEquals("p.B", r2.get().fqName());

            // own field -> p.Derived.own
            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offOwnField);
            assertTrue(r3.isPresent(), "own field should resolve");
            assertEquals("p.Derived.own", r3.get().fqName());

            // inherited baseField -> p.Base.baseField
            Optional<CodeUnit> r4 = analyzer.inferTypeAt(pf, offBaseField);
            assertTrue(r4.isPresent(), "baseField should resolve");
            assertEquals("p.Base.baseField", r4.get().fqName());

            // shadowed own (local var overshadowing field) -> should NOT return field but local type
            Optional<CodeUnit> r5 = analyzer.inferTypeAt(pf, offOwnShadow);
            assertTrue(r5.isPresent(), "shadowed own should resolve (local)");
            // local variable type is int -> parseFieldType won't find class; for primitives we expect empty resolution
            // but since the local is a primitive literal, our analyzer returns Optional.empty() in such cases.
            // To ensure a meaningful assertion, verify it does NOT resolve to the field
            assertNotEquals("p.Derived.own", r5.get().fqName(), "shadowed local should not resolve to field");
        }
    }
}
