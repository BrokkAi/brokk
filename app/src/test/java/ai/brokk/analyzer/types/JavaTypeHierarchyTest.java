package ai.brokk.analyzer.types;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class JavaTypeHierarchyTest {

    @Test
    public void directExtends_singleFile() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                public class BaseClass {}
                class XExtendsY extends BaseClass {}
                """,
                        "BaseAndX.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeX = analyzer.getDefinition("XExtendsY");
            assertTrue(maybeX.isPresent(), "Definition for XExtendsY should be present");
            CodeUnit x = maybeX.get();

            List<String> direct = analyzer.getDirectAncestors(x).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("BaseClass"), direct, "XExtendsY should directly extend BaseClass");

            List<String> transitive =
                    analyzer.getAncestors(x).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("BaseClass"), transitive, "XExtendsY should have BaseClass as its only ancestor");
        }
    }

    @Test
    public void implementsOnly_singleFile() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                interface ServiceInterface {}
                class ServiceImpl implements ServiceInterface {}
                """,
                        "Service.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeImpl = analyzer.getDefinition("ServiceImpl");
            assertTrue(maybeImpl.isPresent(), "Definition for ServiceImpl should be present");
            CodeUnit impl = maybeImpl.get();

            List<String> direct = analyzer.getDirectAncestors(impl).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("ServiceInterface"), direct, "ServiceImpl should directly implement ServiceInterface");

            List<String> transitive =
                    analyzer.getAncestors(impl).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("ServiceInterface"), transitive, "No transitive ancestors beyond the interface");
        }
    }

    @Test
    public void extendsAndImplements_orderPreserved() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                class BaseClass {}
                interface ServiceInterface {}
                interface Interface {}
                class ExtendsAndImplements extends BaseClass implements ServiceInterface, Interface {}
                """,
                        "AllInOne.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeCls = analyzer.getDefinition("ExtendsAndImplements");
            assertTrue(maybeCls.isPresent(), "Definition for ExtendsAndImplements should be present");
            CodeUnit cls = maybeCls.get();

            List<String> direct = analyzer.getDirectAncestors(cls).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(
                    List.of("BaseClass", "ServiceInterface", "Interface"),
                    direct,
                    "Order should be [superclass, interfaces...] for Java");

            List<String> transitive =
                    analyzer.getAncestors(cls).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(
                    List.of("BaseClass", "ServiceInterface", "Interface"),
                    transitive,
                    "Transitive ancestors should maintain discovery order");
        }
    }

    @Test
    public void classWithNoAncestors_returnsEmpty() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                public class Plain {}
                """, "Plain.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybePlain = analyzer.getDefinition("Plain");
            assertTrue(maybePlain.isPresent(), "Definition for Plain should be present");
            CodeUnit plain = maybePlain.get();

            assertTrue(analyzer.getDirectAncestors(plain).isEmpty(), "Plain should have no direct ancestors");
            assertTrue(analyzer.getAncestors(plain).isEmpty(), "Plain should have no transitive ancestors");
        }
    }

    @Test
    public void inheritanceAcrossFiles_transitive() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                public class Base {}
                """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
                class Child extends Base {}
                """, "Child.java")
                .addFileContents(
                        """
                class GrandChild extends Child {}
                """, "GrandChild.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeGrand = analyzer.getDefinition("GrandChild");
            assertTrue(maybeGrand.isPresent(), "Definition for GrandChild should be present");
            CodeUnit grand = maybeGrand.get();

            List<String> direct = analyzer.getDirectAncestors(grand).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("Child"), direct, "GrandChild should directly extend Child");

            List<String> transitive =
                    analyzer.getAncestors(grand).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("Child", "Base"), transitive, "Transitive ancestors should be Child then Base");
        }
    }

    @Test
    public void interPackageInheritance_directImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public class A {}
                """, "A.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.A;
                public class B extends A {}
                """,
                        "B.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeB = analyzer.getDefinition("p2.B");
            assertTrue(maybeB.isPresent(), "Definition for p2.B should be present");
            CodeUnit b = maybeB.get();

            List<String> direct = analyzer.getDirectAncestors(b).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.A"), direct, "p2.B should directly extend p1.A (resolved via direct import)");

            List<String> transitive =
                    analyzer.getAncestors(b).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p1.A"), transitive, "Transitive ancestors should be p1.A");
        }
    }

    @Test
    public void interPackageInheritance_wildcardImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public class A {}
                """, "A.java");
        try (var testProject = builder.addFileContents(
                        """
                package p3;
                import p1.*;
                public class C extends A {}
                """,
                        "C.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeC = analyzer.getDefinition("p3.C");
            assertTrue(maybeC.isPresent(), "Definition for p3.C should be present");
            CodeUnit c = maybeC.get();

            List<String> direct = analyzer.getDirectAncestors(c).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.A"), direct, "p3.C should directly extend p1.A (resolved via wildcard import)");

            List<String> transitive =
                    analyzer.getAncestors(c).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p1.A"), transitive, "Transitive ancestors should be p1.A");
        }
    }

    @Test
    public void interPackageInheritance_mixed_directAndWildcard() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public class A {}
                """, "A.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.A;
                public class B extends A {}
                """,
                        "B.java")
                .addFileContents(
                        """
                package p3;
                import p1.*;
                public class C extends A {}
                """,
                        "C.java")
                .addFileContents(
                        """
                package p4;
                import p2.B;
                import p3.C;
                public class D extends B {}
                public class E extends C {}
                """,
                        "D_E.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            // Verify B's hierarchy
            var maybeB = analyzer.getDefinition("p2.B");
            assertTrue(maybeB.isPresent(), "Definition for p2.B should be present");
            CodeUnit b = maybeB.get();
            List<String> bDirect = analyzer.getDirectAncestors(b).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.A"), bDirect, "p2.B should extend p1.A");

            // Verify C's hierarchy
            var maybeC = analyzer.getDefinition("p3.C");
            assertTrue(maybeC.isPresent(), "Definition for p3.C should be present");
            CodeUnit c = maybeC.get();
            List<String> cDirect = analyzer.getDirectAncestors(c).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.A"), cDirect, "p3.C should extend p1.A");

            // Verify D's hierarchy (extends B which extends A)
            var maybeD = analyzer.getDefinition("p4.D");
            assertTrue(maybeD.isPresent(), "Definition for p4.D should be present");
            CodeUnit d = maybeD.get();
            List<String> dDirect = analyzer.getDirectAncestors(d).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p2.B"), dDirect, "p4.D should directly extend p2.B");

            List<String> dTransitive =
                    analyzer.getAncestors(d).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p2.B", "p1.A"), dTransitive, "p4.D's transitive ancestors should be p2.B then p1.A");

            // Verify E's hierarchy (extends C which extends A)
            var maybeE = analyzer.getDefinition("p4.E");
            assertTrue(maybeE.isPresent(), "Definition for p4.E should be present");
            CodeUnit e = maybeE.get();
            List<String> eDirect = analyzer.getDirectAncestors(e).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p3.C"), eDirect, "p4.E should directly extend p3.C");

            List<String> eTransitive =
                    analyzer.getAncestors(e).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p3.C", "p1.A"), eTransitive, "p4.E's transitive ancestors should be p3.C then p1.A");
        }
    }

    @Test
    public void interPackageInterfaceImplementation_directImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public interface I {}
                """, "I.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.I;
                public class D implements I {}
                """,
                        "D.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeD = analyzer.getDefinition("p2.D");
            assertTrue(maybeD.isPresent(), "Definition for p2.D should be present");
            CodeUnit d = maybeD.get();

            List<String> direct = analyzer.getDirectAncestors(d).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.I"), direct, "p2.D should directly implement p1.I (resolved via direct import)");
        }
    }

    @Test
    public void interPackageInterfaceImplementation_wildcardImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public interface I {}
                """, "I.java");
        try (var testProject = builder.addFileContents(
                        """
                package p3;
                import p1.*;
                public class E implements I {}
                """,
                        "E.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeE = analyzer.getDefinition("p3.E");
            assertTrue(maybeE.isPresent(), "Definition for p3.E should be present");
            CodeUnit e = maybeE.get();

            List<String> direct = analyzer.getDirectAncestors(e).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p1.I"), direct, "p3.E should directly implement p1.I (resolved via wildcard import)");
        }
    }

    @Test
    public void interPackageInterfaceImplementation_multipleInterfaces() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public interface I1 {}
                public interface I2 {}
                """,
                "Interfaces.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.I1;
                import p1.I2;
                public class F implements I1, I2 {}
                """,
                        "F.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeF = analyzer.getDefinition("p2.F");
            assertTrue(maybeF.isPresent(), "Definition for p2.F should be present");
            CodeUnit f = maybeF.get();

            List<String> direct = analyzer.getDirectAncestors(f).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(
                    List.of("p1.I1", "p1.I2"),
                    direct,
                    "p2.F should implement both p1.I1 and p1.I2 (resolved via direct imports)");
        }
    }

    @Test
    public void interPackageExtendsAndImplements_crossPackage() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p1;
                public class Base {}
                public interface Service {}
                """,
                "BaseAndService.java");
        try (var testProject = builder.addFileContents(
                        """
                package p2;
                import p1.*;
                public class Impl extends Base implements Service {}
                """,
                        "Impl.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeImpl = analyzer.getDefinition("p2.Impl");
            assertTrue(maybeImpl.isPresent(), "Definition for p2.Impl should be present");
            CodeUnit impl = maybeImpl.get();

            List<String> direct = analyzer.getDirectAncestors(impl).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(
                    List.of("p1.Base", "p1.Service"),
                    direct,
                    "p2.Impl should extend p1.Base and implement p1.Service (resolved via wildcard import)");
        }
    }

    @Test
    public void cyclicInterfaces_terminatesAndDeduplicates() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package p;
                public interface A extends B {}
                """,
                "A.java");
        try (var testProject = builder.addFileContents(
                        """
                package p;
                public interface B extends A {}
                """,
                        "B.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeA = analyzer.getDefinition("p.A");
            assertTrue(maybeA.isPresent(), "Definition for p.A should be present");
            CodeUnit a = maybeA.get();

            var maybeB = analyzer.getDefinition("p.B");
            assertTrue(maybeB.isPresent(), "Definition for p.B should be present");
            CodeUnit b = maybeB.get();

            // Direct ancestors
            List<String> aDirect = analyzer.getDirectAncestors(a).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p.B"), aDirect, "A should directly extend B");

            List<String> bDirect = analyzer.getDirectAncestors(b).stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            assertEquals(List.of("p.A"), bDirect, "B should directly extend A");

            // Transitive ancestors must terminate and de-duplicate
            List<String> aTransitive =
                    analyzer.getAncestors(a).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p.B"), aTransitive, "A's ancestors should contain B only once and terminate");

            List<String> bTransitive =
                    analyzer.getAncestors(b).stream().map(CodeUnit::fqName).collect(Collectors.toList());
            assertEquals(List.of("p.A"), bTransitive, "B's ancestors should contain A only once and terminate");
        }
    }
}
