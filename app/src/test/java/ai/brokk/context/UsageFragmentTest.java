package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class UsageFragmentTest {

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
                    # Usages of p1.A.m1

                    Call sites (2):
                    - `p1.A.m1` (src/main/java/p1/A.java:1)
                    - `p2.B.m2` (src/main/java/p2/B.java:1)

                    <methods class="p1.A" file="src/main/java/p1/A.java">
                    public void m1() {}
                    </methods>
                    <methods class="p2.B" file="src/main/java/p2/B.java">
                    protected int m2() { return 0; }
                    </methods>
                    """;

            var frag = new ContextFragments.UsageFragment("42", cm, "p1.A.m1", true, frozen);

            assertEquals(ContextFragments.UsageMode.FULL, frag.mode(), "should infer FULL mode from frozen snapshot");

            var files = frag.referencedFiles().join();
            var sources = frag.sources().join();

            assertFalse(files.isEmpty(), "files should be parsed from frozen text");
            assertTrue(
                    files.stream().anyMatch(f -> f.toString().replace('\\', '/').contains("src/main/java/p1/A.java")),
                    "expected project file for A.java");
            assertTrue(
                    files.stream().anyMatch(f -> f.toString().replace('\\', '/').contains("src/main/java/p2/B.java")),
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

    @Test
    public void unresolvableClassIsIgnoredButOthersParsed() throws Exception {
        String aSrc =
                """
                package p1;
                public class A { public void m1() {} }
                """;

        try (var project =
                InlineTestProjectCreator.code(aSrc, "src/main/java/p1/A.java").build()) {
            var root = project.getRoot();
            var analyzer = Languages.JAVA.createAnalyzer(project);
            var cm = new TestContextManager(root, new TestConsoleIO(), analyzer);

            String frozen =
                    """
                    # Usages of p1.A.m1

                    Call sites (2):
                    - `p1.A.m1` (src/main/java/p1/A.java:1)
                    - `p3.C.m3` (unknown)

                    public void m1() {}
                    """;

            var frag = new ContextFragments.UsageFragment("100", cm, "p1.A.m1", true, frozen);

            assertEquals(ContextFragments.UsageMode.FULL, frag.mode(), "should infer FULL mode from frozen snapshot");

            var files = frag.referencedFiles().join();
            var sources = frag.sources().join();

            assertTrue(
                    files.stream().anyMatch(f -> f.toString().replace('\\', '/').contains("src/main/java/p1/A.java")),
                    "expected project file for A.java");
            assertTrue(
                    sources.stream()
                            .anyMatch(cu ->
                                    cu.fqName().equals("p1.A") || cu.fqName().equals("p1.A.m1")),
                    "expected A or A.m1 to be resolved");
            assertTrue(
                    sources.stream().noneMatch(cu -> cu.fqName().startsWith("p3.C")),
                    "unresolved class p3.C should not appear in sources");
            assertTrue(
                    files.stream()
                            .noneMatch(f -> f.toString().contains("/p3/")
                                    || f.toString().contains("\\p3\\")),
                    "should not have any file path for p3");
        }
    }

    @Test
    public void decodeFrozen_infersSampleMode_andExtractsSourcesAndFiles() throws Exception {
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
                    # Usages of p1.A.m1

                    Call sites (2):
                    - `p1.A.m1` (A.java:1)
                    - `p2.B.m2` (B.java:1)

                    Examples:

                    <methods class="p1.A" file="src/main/java/p1/A.java">
                    public void m1() {}
                    </methods>
                    """;

            var frag = new ContextFragments.UsageFragment("sample-1", cm, "p1.A.m1", true, frozen);

            assertEquals(
                    ContextFragments.UsageMode.SAMPLE, frag.mode(), "should infer SAMPLE mode from frozen snapshot");

            var files = frag.referencedFiles().join();
            var sources = frag.sources().join();

            assertTrue(
                    files.stream().anyMatch(f -> f.toString().replace('\\', '/').contains("src/main/java/p1/A.java")),
                    "expected project file for A.java");
            assertTrue(
                    files.stream().anyMatch(f -> f.toString().replace('\\', '/').contains("src/main/java/p2/B.java")),
                    "expected project file for B.java");

            assertTrue(
                    sources.stream()
                            .anyMatch(cu ->
                                    cu.fqName().equals("p1.A") || cu.fqName().equals("p1.A.m1")),
                    "expected A or A.m1 to be resolved");
            assertTrue(
                    sources.stream()
                            .anyMatch(cu ->
                                    cu.fqName().equals("p2.B") || cu.fqName().equals("p2.B.m2")),
                    "expected B or B.m2 to be resolved");
        }
    }

    @Test
    void sampleModeConstructorSetsMode() throws Exception {
        try (var project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build()) {
            Path root = project.getRoot();
            ProjectFile pf = new ProjectFile(root, "src/main/java/A.java");
            CodeUnit targetClass = CodeUnit.cls(pf, "com.example", "Target");
            var analyzer = new TestAnalyzer(List.of(targetClass), Map.of("com.example.Target", List.of(targetClass)));
            var cm = new TestContextManager(root, new NoOpConsoleIO(), analyzer);

            var fragment = new ContextFragments.UsageFragment(
                    cm, "com.example.Target.doSomething", true, ContextFragments.UsageMode.SAMPLE);

            assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
            assertEquals("com.example.Target.doSomething", fragment.targetIdentifier());
            assertTrue(fragment.includeTestFiles());
        }
    }

    @Test
    void sampleModeWithSnapshotTextConstructor() throws Exception {
        try (var project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build()) {
            Path root = project.getRoot();
            var analyzer = new TestAnalyzer(List.of(), Map.of());
            var cm = new TestContextManager(root, new NoOpConsoleIO(), analyzer);

            var fragment = new ContextFragments.UsageFragment(
                    cm, "com.example.Target.doSomething", true, "precomputed text", ContextFragments.UsageMode.SAMPLE);

            assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
            assertEquals("precomputed text", fragment.text().join());
        }
    }

    @Test
    void sampleModeReprIncludesMode() throws Exception {
        try (var project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build()) {
            Path root = project.getRoot();
            var analyzer = new TestAnalyzer(List.of(), Map.of());
            var cm = new TestContextManager(root, new NoOpConsoleIO(), analyzer);

            var sampleFragment = new ContextFragments.UsageFragment(
                    cm, "com.example.Target", true, ContextFragments.UsageMode.SAMPLE);
            var fullFragment =
                    new ContextFragments.UsageFragment(cm, "com.example.Target", true, ContextFragments.UsageMode.FULL);

            assertTrue(
                    sampleFragment.repr().contains("mode=SAMPLE"),
                    "SAMPLE mode should be in repr: " + sampleFragment.repr());
            assertFalse(
                    fullFragment.repr().contains("mode="),
                    "FULL mode should not show mode in repr: " + fullFragment.repr());
        }
    }

    @Test
    void fullModeIsDefault() throws Exception {
        try (var project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build()) {
            Path root = project.getRoot();
            var analyzer = new TestAnalyzer(List.of(), Map.of());
            var cm = new TestContextManager(root, new NoOpConsoleIO(), analyzer);

            var fragment = new ContextFragments.UsageFragment(cm, "com.example.Target", true);
            assertEquals(ContextFragments.UsageMode.FULL, fragment.mode(), "Default mode should be FULL");
        }
    }

    @Test
    void idBasedConstructorWithMode() throws Exception {
        try (var project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build()) {
            Path root = project.getRoot();
            var analyzer = new TestAnalyzer(List.of(), Map.of());
            var cm = new TestContextManager(root, new NoOpConsoleIO(), analyzer);

            var fragment = new ContextFragments.UsageFragment(
                    "test-id", cm, "com.example.Target", true, null, ContextFragments.UsageMode.SAMPLE);
            assertEquals("test-id", fragment.id());
            assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
        }
    }

    @Test
    void fullMode_excludesUsagesFromDefiningOwner_andIncludesAllExternalCallSites() throws Exception {
        String target =
                """
                package p;
                public class Target {
                    public static void foo() {}
                    public void internalCall() { foo(); }
                }
                """;

        String ext1 =
                """
                package p;
                public class ExternalShort1 {
                    public void short1() { Target.foo(); }
                }
                """;

        String ext2 =
                """
                package p;
                public class ExternalShort2 {
                    public void short2() { Target.foo(); }
                }
                """;

        String ext3 =
                """
                package p;
                public class ExternalShort3 {
                    public void short3() { Target.foo(); }
                }
                """;

        String extLong =
                """
                package p;
                public class ExternalLong {
                    public void veryLongCallSiteExample() {
                        int x = 1;
                        if (x > 0) {
                            Target.foo();
                        }
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(target, "src/main/java/p/Target.java")
                .addFileContents(ext1, "src/main/java/p/ExternalShort1.java")
                .addFileContents(ext2, "src/main/java/p/ExternalShort2.java")
                .addFileContents(ext3, "src/main/java/p/ExternalShort3.java")
                .addFileContents(extLong, "src/main/java/p/ExternalLong.java")
                .build()) {
            var analyzer = Languages.JAVA.createAnalyzer(project);
            var cm = new TestContextManager(project.getRoot(), new TestConsoleIO(), analyzer);

            var fragment =
                    new ContextFragments.UsageFragment(cm, "p.Target.foo", true, ContextFragments.UsageMode.FULL);
            String text = fragment.text().join();

            assertFalse(text.contains("internalCall"), "internal call site should be excluded");
            assertTrue(text.contains("short1"), "should include ExternalShort1.short1 source");
            assertTrue(text.contains("short2"), "should include ExternalShort2.short2 source");
            assertTrue(text.contains("short3"), "should include ExternalShort3.short3 source");
            assertTrue(text.contains("veryLongCallSiteExample"), "should include ExternalLong.veryLongCallSiteExample");
        }
    }

    @Test
    void sampleMode_listsAllCallSites_andShowsOnlyThreeShortestSources() throws Exception {
        String target =
                """
                package p;
                public class Target {
                    public static void foo() {}
                    public void internalCall() { foo(); }
                }
                """;

        String ext1 =
                """
                package p;
                public class ExternalShort1 {
                    public void short1() { Target.foo(); }
                }
                """;

        String ext2 =
                """
                package p;
                public class ExternalShort2 {
                    public void short2() { Target.foo(); }
                }
                """;

        String ext3 =
                """
                package p;
                public class ExternalShort3 {
                    public void short3() { Target.foo(); }
                }
                """;

        String extLong =
                """
                package p;
                public class ExternalLong {
                    public void veryLongCallSiteExample() {
                        int x = 1;
                        if (x > 0) {
                            Target.foo();
                        }
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(target, "src/main/java/p/Target.java")
                .addFileContents(ext1, "src/main/java/p/ExternalShort1.java")
                .addFileContents(ext2, "src/main/java/p/ExternalShort2.java")
                .addFileContents(ext3, "src/main/java/p/ExternalShort3.java")
                .addFileContents(extLong, "src/main/java/p/ExternalLong.java")
                .build()) {
            var analyzer = Languages.JAVA.createAnalyzer(project);
            var cm = new TestContextManager(project.getRoot(), new TestConsoleIO(), analyzer);

            var fragment =
                    new ContextFragments.UsageFragment(cm, "p.Target.foo", true, ContextFragments.UsageMode.SAMPLE);
            String text = fragment.text().join();

            assertFalse(text.contains("internalCall"), "internal call site should be excluded");

            assertTrue(text.contains("Call sites ("), "should include call site list header");
            assertTrue(text.contains("p.ExternalShort1.short1"), "should list ExternalShort1.short1 call site");
            assertTrue(text.contains("p.ExternalShort2.short2"), "should list ExternalShort2.short2 call site");
            assertTrue(text.contains("p.ExternalShort3.short3"), "should list ExternalShort3.short3 call site");
            assertTrue(text.contains("p.ExternalLong.veryLongCallSiteExample"), "should list long call site");

            int idx = text.indexOf("Examples:");
            assertTrue(idx >= 0, "should include Examples section");
            String after = text.substring(idx);
            assertFalse(after.contains("veryLongCallSiteExample"), "long call site should not be in examples section");
        }
    }

    @Test
    void typescriptClassUsageFragment_findsConstructorParameterPropertyTypeUsageThroughBarrel() throws Exception {
        String service = """
                export class LayoutService {}
                """;
        String index =
                """
                import { LayoutService } from "./layout.service";
                export { LayoutService };
                """;
        String consumer =
                """
                import { LayoutService } from "../services";

                export class HeaderComponent {
                    constructor(private layoutService: LayoutService) {}
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "services/layout.service.ts")
                .addFileContents(index, "services/index.ts")
                .addFileContents(consumer, "feature/header.component.ts")
                .build()) {
            var analyzer = Languages.TYPESCRIPT.createAnalyzer(project);
            var cm = new TestContextManager(project.getRoot(), new TestConsoleIO(), analyzer);
            var target = analyzer.searchDefinitions("LayoutService").stream()
                    .filter(CodeUnit::isClass)
                    .findFirst()
                    .orElseThrow();

            var fragment = new ContextFragments.UsageFragment(cm, target.fqName(), true);
            String text = fragment.text().join();

            assertFalse(text.contains("No relevant usages found"), "should resolve TypeScript usage fragment");
            assertTrue(text.contains("feature/header.component.ts"), "should list header component file");
            assertTrue(text.contains("HeaderComponent.constructor"), "should include constructor call site");
            assertEquals(
                    target.source().getSyntaxStyle(), fragment.syntaxStyle().join(), "should use TS syntax style");
            assertTrue(
                    fragment.sourceFiles().join().stream()
                            .noneMatch(pf -> pf.getRelPath().toString().equals("_unknown_")),
                    "usage fragment should not synthesize _unknown_ source files");
        }
    }
}
