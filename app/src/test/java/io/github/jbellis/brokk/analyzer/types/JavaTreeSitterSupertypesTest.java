package io.github.jbellis.brokk.analyzer.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JavaTreeSitterSupertypesTest {

    private static TestProject testProject;
    private static JavaTreeSitterAnalyzer analyzer;

    @BeforeAll
    static void setup() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "brokk-tsa-supertypes");
        testProject = new TestProject(tempDir, Languages.JAVA);
        analyzer = new JavaTreeSitterAnalyzer(testProject);
    }

    @Test
    void ancestors_singleExtends() {
        var maybeX = analyzer.getDefinition("XExtendsY");
        assertTrue(maybeX.isPresent(), "Definition for XExtendsY should be present");
        CodeUnit x = maybeX.get();

        List<String> ancestorNames =
                analyzer.getAncestors(x).stream().map(CodeUnit::fqName).collect(Collectors.toList());
        assertEquals(List.of("BaseClass"), ancestorNames, "XExtendsY should extend BaseClass");
    }

    @Test
    void ancestors_implementsOnly() {
        var maybeImpl = analyzer.getDefinition("ServiceImpl");
        assertTrue(maybeImpl.isPresent(), "Definition for ServiceImpl should be present");
        CodeUnit impl = maybeImpl.get();

        List<String> ancestorNames =
                analyzer.getAncestors(impl).stream().map(CodeUnit::fqName).collect(Collectors.toList());
        assertEquals(List.of("ServiceInterface"), ancestorNames, "ServiceImpl should implement ServiceInterface");
    }

    @Test
    void ancestors_extendsAndImplements_orderPreserved() {
        var maybeCls = analyzer.getDefinition("ExtendsAndImplements");
        assertTrue(maybeCls.isPresent(), "Definition for ExtendsAndImplements should be present");
        CodeUnit cls = maybeCls.get();

        List<String> ancestorNames =
                analyzer.getAncestors(cls).stream().map(CodeUnit::fqName).collect(Collectors.toList());
        assertEquals(
                List.of("BaseClass", "ServiceInterface", "Interface"),
                ancestorNames,
                "Order should be [superclass, interfaces...] for Java");
    }

    @Test
    void ancestors_nonClassReturnsEmpty() {
        var maybeFn = analyzer.getDefinition("A.method2");
        assertTrue(maybeFn.isPresent(), "Definition for A.method2 should be present");
        CodeUnit method = maybeFn.get();

        List<CodeUnit> ancestors = analyzer.getAncestors(method);
        assertTrue(ancestors.isEmpty(), "Non-class code units should return empty ancestors");
    }
}
