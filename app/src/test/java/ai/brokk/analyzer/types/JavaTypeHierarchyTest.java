package ai.brokk.analyzer.types;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class JavaTypeHierarchyTest {

    @Test
    @Disabled("Type hierarchy computation not finalized; enabling later")
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
    @Disabled("Type hierarchy computation not finalized; enabling later")
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
    @Disabled("Type hierarchy computation not finalized; enabling later")
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
    @Disabled("Type hierarchy computation not finalized; enabling later")
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
    @Disabled("Type hierarchy computation not finalized; enabling later")
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
}
