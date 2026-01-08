package ai.brokk.context;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CodeFragmentTest {
    @TempDir
    Path tempDir;

    private TestContextManager contextManager;
    private TestAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TestAnalyzer();
        contextManager = new TestContextManager(tempDir, null, analyzer);
    }

    @Test
    void testCodeFragmentIncludesAncestorSkeletons() throws Exception {
        ProjectFile baseFile = new ProjectFile(tempDir, "Base.java");
        ProjectFile subFile = new ProjectFile(tempDir, "Sub.java");

        CodeUnit baseCls = CodeUnit.cls(baseFile, "com.example", "Base");
        CodeUnit subCls = CodeUnit.cls(subFile, "com.example", "Sub");

        // Re-initialize to ensure the analyzer used by contextManager is the one we configure
        analyzer = new TestAnalyzer(List.of(baseCls, subCls), java.util.Map.of());
        contextManager = new TestContextManager(tempDir, null, analyzer);

        analyzer.setDirectAncestors(subCls, List.of(baseCls));
        analyzer.setSkeleton(baseCls, "class Base {}");
        analyzer.setSource(subCls, "class Sub extends Base {}");

        var fragment = new ContextFragments.CodeFragment(contextManager, subCls);
        String text = fragment.text().join();

        assertCodeContains(text, "class Sub extends Base {}");
        assertCodeContains(text, "// Direct ancestors of Sub: Base");
        assertCodeContains(text, "package com.example;");
        assertCodeContains(text, "class Base {}");

        Set<CodeUnit> sources = fragment.sources().join();
        assertTrue(sources.contains(subCls));
        assertTrue(sources.contains(baseCls));

        Set<ProjectFile> files = fragment.files().join();
        assertTrue(files.contains(subFile));
        assertTrue(files.contains(baseFile));
        assertEquals(2, files.size());
    }

    @Test
    void testCodeFragmentForMethodDoesNotIncludeAncestors() {
        ProjectFile file = new ProjectFile(tempDir, "Example.java");
        CodeUnit cls = CodeUnit.cls(file, "com.example", "Example");
        CodeUnit method = CodeUnit.fn(file, "com.example", "Example.run");
        
        analyzer.setDirectAncestors(cls, List.of(CodeUnit.cls(file, "com.example", "Parent")));
        analyzer.setSource(method, "void run() {}");

        var fragment = new ContextFragments.CodeFragment(contextManager, method);
        String text = fragment.text().join();

        assertCodeContains(text, "void run() {}");
        assertTrue(!text.contains("Direct ancestors"), "Methods should not pull in class ancestors");
        
        assertEquals(Set.of(method), fragment.sources().join());
        assertEquals(Set.of(file), fragment.files().join());
    }
}
