package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Consolidated type inference tests that were previously split across multiple files.
 */
public class TypeInferenceTest {

    @Test
    public void chainedFieldAndMethodResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;

                public class Leaf {
                    public int value;
                }

                public class Node {
                    public Leaf leaf;
                    public Leaf getLeaf() { return leaf; }
                }

                public class Helper {
                    public Node process() { return new Node(); }
                }

                public class Use {
                    public void test() {
                        Node n = new Node();
                        int a = n.leaf.value;
                        int b = n.getLeaf().value;
                        int c = new Helper().process().getLeaf().value;
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "X.java");
            var src = pf.read().orElseThrow();

            int idxA = src.indexOf("n.leaf.value");
            assertTrue(idxA >= 0);
            int offA = src.substring(0, idxA).getBytes(StandardCharsets.UTF_8).length + "n.leaf.".length(); // inside 'value'

            int idxB = src.indexOf("n.getLeaf().value");
            assertTrue(idxB >= 0);
            int offB = src.substring(0, idxB).getBytes(StandardCharsets.UTF_8).length + "n.getLeaf().".length();

            int idxC = src.indexOf("new Helper().process().getLeaf().value");
            assertTrue(idxC >= 0);
            int offC = src.substring(0, idxC).getBytes(StandardCharsets.UTF_8).length + "new Helper().process().getLeaf().".length();

            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offA);
            assertTrue(r1.isPresent(), "n.leaf.value should resolve");
            assertEquals("p.Leaf.value", r1.get().fqName());

            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offB);
            assertTrue(r2.isPresent(), "n.getLeaf().value should resolve");
            assertEquals("p.Leaf.value", r2.get().fqName());

            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offC);
            assertTrue(r3.isPresent(), "new Helper().process().getLeaf().value should resolve");
            assertEquals("p.Leaf.value", r3.get().fqName());
        }
    }

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

    @Test
    public void patternMatchingInstanceofResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                package p;

                public interface Repo {
                    String getName();
                }

                public class GitRepo implements Repo {
                    public String getName() { return "git"; }
                    public String getTopLevel() { return "/top"; }
                }

                public class Use {
                    public void test(Repo repo) {
                        if (repo instanceof GitRepo gitRepo) {
                            String top = gitRepo.getTopLevel();
                            gitRepo.getName();
                        }
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "X.java");
            var srcOpt = pf.read();
            assertTrue(srcOpt.isPresent(), "Source should be readable");
            String src = srcOpt.get();

            // Test 1: gitRepo.getTopLevel() - pattern variable qualified method call
            int idxGetTopLevel = src.indexOf("gitRepo.getTopLevel()");
            assertTrue(idxGetTopLevel >= 0, "expected 'gitRepo.getTopLevel()' in sample");
            int offGetTopLevel = src.substring(0, idxGetTopLevel).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + "gitRepo.".length();

            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offGetTopLevel);
            assertTrue(r1.isPresent(), "gitRepo.getTopLevel should resolve");
            assertEquals("p.GitRepo.getTopLevel", r1.get().fqName());

            // Test 2: gitRepo.getName() - inherited method call on pattern variable
            int idxGetName = src.indexOf("gitRepo.getName()");
            assertTrue(idxGetName >= 0, "expected 'gitRepo.getName()' in sample");
            int offGetName = src.substring(0, idxGetName).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + "gitRepo.".length();

            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offGetName);
            assertTrue(r2.isPresent(), "gitRepo.getName should resolve");
            // getName is defined on GitRepo (which implements Repo)
            assertEquals("p.GitRepo.getName", r2.get().fqName());
        }
    }

    @Test
    public void typeRefSampleInnerClassAndEnumResolution() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                """
                public class TypeRefSample {
                    public enum MyEnum {
                        FIRST,
                        SECOND;

                        public void enumMethod() {}
                    }

                    public static class StaticInner {
                        public void innerMethod() {}
                    }

                    public void instanceMethod(StaticInner paramInner, MyEnum paramEnum) {
                        StaticInner localInner = new StaticInner();
                        MyEnum localEnum = MyEnum.SECOND;
                        paramInner.innerMethod();
                        localInner.innerMethod();
                        paramEnum.enumMethod();
                        localEnum.enumMethod();
                    }
                }
                """,
                "TypeRefSample.java").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var pf = new ProjectFile(project.getRoot(), "TypeRefSample.java");
            var srcOpt = pf.read();
            assertTrue(srcOpt.isPresent(), "Source should be readable");
            String src = srcOpt.get();

            // Test 1: MyEnum.SECOND enum constant resolution
            // The analyzer resolves the enum type itself (MyEnum), not the specific constant (SECOND)
            int idxEnumSecond = src.indexOf("MyEnum localEnum = MyEnum.SECOND");
            assertTrue(idxEnumSecond >= 0, "expected 'MyEnum localEnum = MyEnum.SECOND'");
            int offEnumSecond = src.substring(0, idxEnumSecond).getBytes(StandardCharsets.UTF_8).length
                    + "MyEnum localEnum = MyEnum.".length();

            Optional<CodeUnit> r1 = analyzer.inferTypeAt(pf, offEnumSecond);
            assertTrue(r1.isPresent(), "MyEnum.SECOND should resolve to the enum constant");
            assertEquals("TypeRefSample.MyEnum.SECOND", r1.get().fqName());

            // Test 2: paramInner.innerMethod() - parameter qualified method call
            int idxParamInnerMethod = src.indexOf("paramInner.innerMethod();");
            assertTrue(idxParamInnerMethod >= 0, "expected 'paramInner.innerMethod();'");
            int offParamInnerMethod = src.substring(0, idxParamInnerMethod).getBytes(StandardCharsets.UTF_8).length
                    + "paramInner.".length();

            Optional<CodeUnit> r2 = analyzer.inferTypeAt(pf, offParamInnerMethod);
            assertTrue(r2.isPresent(), "paramInner.innerMethod should resolve");
            assertEquals("TypeRefSample.StaticInner.innerMethod", r2.get().fqName());

            // Test 3: localInner.innerMethod() - local variable qualified method call
            int idxLocalInnerMethod = src.indexOf("localInner.innerMethod();");
            assertTrue(idxLocalInnerMethod >= 0, "expected 'localInner.innerMethod();'");
            int offLocalInnerMethod = src.substring(0, idxLocalInnerMethod).getBytes(StandardCharsets.UTF_8).length
                    + "localInner.".length();

            Optional<CodeUnit> r3 = analyzer.inferTypeAt(pf, offLocalInnerMethod);
            assertTrue(r3.isPresent(), "localInner.innerMethod should resolve");
            assertEquals("TypeRefSample.StaticInner.innerMethod", r3.get().fqName());

            // Test 4: paramEnum.enumMethod() - parameter enum method call
            int idxParamEnumMethod = src.indexOf("paramEnum.enumMethod();");
            assertTrue(idxParamEnumMethod >= 0, "expected 'paramEnum.enumMethod();'");
            int offParamEnumMethod = src.substring(0, idxParamEnumMethod).getBytes(StandardCharsets.UTF_8).length
                    + "paramEnum.".length();

            Optional<CodeUnit> r4 = analyzer.inferTypeAt(pf, offParamEnumMethod);
            assertTrue(r4.isPresent(), "paramEnum.enumMethod should resolve");
            assertEquals("TypeRefSample.MyEnum.enumMethod", r4.get().fqName());

            // Test 5: localEnum.enumMethod() - local enum variable method call
            int idxLocalEnumMethod = src.indexOf("localEnum.enumMethod();");
            assertTrue(idxLocalEnumMethod >= 0, "expected 'localEnum.enumMethod();'");
            int offLocalEnumMethod = src.substring(0, idxLocalEnumMethod).getBytes(StandardCharsets.UTF_8).length
                    + "localEnum.".length();

            Optional<CodeUnit> r5 = analyzer.inferTypeAt(pf, offLocalEnumMethod);
            assertTrue(r5.isPresent(), "localEnum.enumMethod should resolve");
            assertEquals("TypeRefSample.MyEnum.enumMethod", r5.get().fqName());
        }
    }
}
