package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import ai.brokk.watchservice.NoopWatchService;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerWrapperCorruptionTest {

    @TempDir
    Path tempDir;

    @Test
    void testFullRebuildWhenTargetedRepairFails() throws Exception {
        TestProject project = new TestProject(tempDir, Languages.JAVA);
        ProjectFile fileA = new ProjectFile(tempDir, "A.java");
        fileA.write("public class A {}");

        AtomicInteger createCalled = new AtomicInteger(0);
        AtomicInteger saveCalled = new AtomicInteger(0);
        AtomicBoolean updateCalled = new AtomicBoolean(false);
        AtomicReference<Language> customJavaRef = new AtomicReference<>();

        IAnalyzer rebuiltAnalyzer = new TestAnalyzer() {
            @Override
            public Set<ProjectFile> getAnalyzedFiles() {
                return Set.of(fileA);
            }

            @Override
            public Set<Language> languages() {
                return Set.of(customJavaRef.get());
            }

            @Override
            public Optional<IAnalyzer> subAnalyzer(Language language) {
                return language == customJavaRef.get() ? Optional.of(this) : Optional.empty();
            }

            @Override
            public String toString() {
                return "RebuiltAnalyzer";
            }
        };

        IAnalyzer corruptAnalyzer = new TestAnalyzer() {
            @Override
            public Set<ProjectFile> getAnalyzedFiles() {
                // Return empty set to trigger mismatch (missing fileA)
                return Set.of();
            }

            @Override
            public Set<Language> languages() {
                return Set.of(customJavaRef.get());
            }

            @Override
            public Optional<IAnalyzer> subAnalyzer(Language language) {
                return language == customJavaRef.get() ? Optional.of(this) : Optional.empty();
            }

            @Override
            public IAnalyzer update(Set<ProjectFile> changedFiles) {
                updateCalled.set(true);
                // Return ourselves, still corrupt (missing fileA)
                return this;
            }
        };

        Language customJava = new Language() {
            @Override
            public String name() {
                return "Java";
            }

            @Override
            public String internalName() {
                return "java";
            }

            @Override
            public Set<String> getExtensions() {
                return Set.of(".java");
            }

            @Override
            public IAnalyzer loadAnalyzer(IProject p, IAnalyzer.ProgressListener listener) {
                return corruptAnalyzer;
            }

            @Override
            public IAnalyzer createAnalyzer(IProject p, IAnalyzer.ProgressListener listener) {
                createCalled.incrementAndGet();
                return rebuiltAnalyzer;
            }

            @Override
            public void saveAnalyzer(IAnalyzer analyzer, IProject p) {
                saveCalled.incrementAndGet();
            }
        };
        customJavaRef.set(customJava);

        // Override the project's language handle to use our test instrumentation
        TestProject instrumentedProject = new TestProject(tempDir, Languages.JAVA) {
            @Override
            public Language getLanguageHandle() {
                return customJava;
            }

            @Override
            public Set<Language> getAnalyzerLanguages() {
                return Set.of(customJava);
            }

            @Override
            public Set<ProjectFile> getAnalyzableFiles(Language lang) {
                return lang == customJava ? Set.of(fileA) : Set.of();
            }
        };

        AnalyzerListener listener = new AnalyzerListener() {
            @Override
            public void onBlocked() {}

            @Override
            public void beforeEachBuild() {}

            @Override
            public void afterEachBuild(boolean externalRequest) {}

            @Override
            public void onProgress(int completed, int total, String description) {}
        };

        try (AnalyzerWrapper wrapper = new AnalyzerWrapper(instrumentedProject, listener, new NoopWatchService())) {
            IAnalyzer finalAnalyzer = wrapper.get();

            // Give async persistence a moment to start before we perform assertions
            Thread.sleep(200);

            // 1. Assert update was called (targeted repair attempt)
            assertTrue(updateCalled.get(), "Targeted repair (update) should have been attempted");

            // 2. Assert createAnalyzer was called (full rebuild fallback)
            assertEquals(1, createCalled.get(), "Full rebuild should have been triggered after failed repair");

            // 3. Assert the final analyzer is the rebuilt one
            assertEquals(rebuiltAnalyzer, finalAnalyzer);

            // 4. Assert persistence:
            // After createAnalyzer(), persistAnalyzerState is called.
            // Since persistence is asynchronous (via LoggingFuture.runAsync), we wait for it.
            long timeout = System.currentTimeMillis() + 5000;
            while (saveCalled.get() < 1 && System.currentTimeMillis() < timeout) {
                Thread.sleep(50);
            }

            assertTrue(
                    saveCalled.get() >= 1,
                    "Persistence should have been called for the rebuilt analyzer. Actual calls: " + saveCalled.get());
        }
    }
}
