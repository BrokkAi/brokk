package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import org.junit.jupiter.api.Test;

public class UsageFragmentDecodeTest {

    @Test
    public void decodeFrozen_extractsSourcesAndFiles() throws Exception {
        String aSrc =
                """
                package p1;
                public class A {
                    public void m1() {}
                }
                """;

        String bSrc =
                """
                package p2;
                public class B {
                    protected int m2() { return 0; }
                }
                """;

        try (var project = InlineTestProjectCreator.code(aSrc, "src/main/java/p1/A.java")
                .addFileContents(bSrc, "src/main/java/p2/B.java")
                .build()) {
            var root = project.getRoot();
            var analyzer = Languages.JAVA.createAnalyzer(project);
            var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

            var frozen =
                    """
                    <methods class="p1.A" file="src/main/java/p1/A.java">
                    public void m1() {}
                    </methods>
                    <methods class="p2.B" file="src/main/java/p2/B.java">
                    protected int m2() { return 0; }
                    </methods>
                    """;

            var frag = new ContextFragment.UsageFragment("42", cm, "p1.A.m1", true, frozen);

            var files = frag.files().join();
            var sources = frag.sources().join();

            assertFalse(files.isEmpty(), "files should be parsed from frozen text");
            assertTrue(
                    files.stream().anyMatch(f -> f.toString().contains("src/main/java/p1/A.java")),
                    "expected project file for A.java");
            assertTrue(
                    files.stream().anyMatch(f -> f.toString().contains("src/main/java/p2/B.java")),
                    "expected project file for B.java");

            assertFalse(sources.isEmpty(), "sources should contain resolved code units");
            assertTrue(
                    sources.stream()
                            .anyMatch(cu ->
                                    cu.fqName().equals("p1.A.m1") || cu.fqName().equals("p1.A")),
                    "expected method or class CodeUnit A to be resolved");
            assertTrue(
                    sources.stream()
                            .anyMatch(cu ->
                                    cu.fqName().equals("p2.B.m2") || cu.fqName().equals("p2.B")),
                    "expected method or class CodeUnit B to be resolved");
        }
    }
}
