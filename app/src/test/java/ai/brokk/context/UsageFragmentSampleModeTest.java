package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UsageFragmentSampleModeTest {

    private IProject project;
    private Path projectRoot;
    private TestContextManager cm;
    private TestAnalyzer analyzer;

    @BeforeEach
    void setup() throws Exception {
        // Create a minimal project for testing fragment construction (not async computation)
        project = InlineTestProjectCreator.code("class A {}\n", "src/main/java/A.java")
                .build();
        projectRoot = project.getRoot();

        ProjectFile pf = new ProjectFile(projectRoot, "src/main/java/A.java");
        CodeUnit targetClass = CodeUnit.cls(pf, "com.example", "Target");

        analyzer = new TestAnalyzer(List.of(targetClass), Map.of("com.example.Target", List.of(targetClass)));
        cm = new TestContextManager(projectRoot, new NoOpConsoleIO(), analyzer);
    }

    @AfterEach
    void teardown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void sampleModeConstructorSetsMode() {
        var fragment = new ContextFragments.UsageFragment(
                cm, "com.example.Target.doSomething", true, ContextFragments.UsageMode.SAMPLE);

        assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
        assertEquals("com.example.Target.doSomething", fragment.targetIdentifier());
        assertTrue(fragment.includeTestFiles());
    }

    @Test
    void sampleModeWithSnapshotTextConstructor() {
        // Test constructor that takes snapshotText and mode
        var fragment = new ContextFragments.UsageFragment(
                cm, "com.example.Target.doSomething", true, "precomputed text", ContextFragments.UsageMode.SAMPLE);

        assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
        assertEquals("precomputed text", fragment.text().join());
    }

    @Test
    void sampleModeReprIncludesMode() {
        var sampleFragment =
                new ContextFragments.UsageFragment(cm, "com.example.Target", true, ContextFragments.UsageMode.SAMPLE);
        var fullFragment =
                new ContextFragments.UsageFragment(cm, "com.example.Target", true, ContextFragments.UsageMode.FULL);

        assertTrue(
                sampleFragment.repr().contains("mode=SAMPLE"),
                "SAMPLE mode should be in repr: " + sampleFragment.repr());
        assertFalse(
                fullFragment.repr().contains("mode="),
                "FULL mode should not show mode in repr: " + fullFragment.repr());
    }

    @Test
    void fullModeIsDefault() {
        var fragment = new ContextFragments.UsageFragment(cm, "com.example.Target", true);
        assertEquals(ContextFragments.UsageMode.FULL, fragment.mode(), "Default mode should be FULL");
    }

    @Test
    void sampleModeAccessor() {
        var fragment =
                new ContextFragments.UsageFragment(cm, "com.example.Target", true, ContextFragments.UsageMode.SAMPLE);
        assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
    }

    @Test
    void idBasedConstructorWithMode() {
        var fragment = new ContextFragments.UsageFragment(
                "test-id", cm, "com.example.Target", true, null, ContextFragments.UsageMode.SAMPLE);
        assertEquals("test-id", fragment.id());
        assertEquals(ContextFragments.UsageMode.SAMPLE, fragment.mode());
    }
}
