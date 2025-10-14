package io.github.jbellis.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class JavaAnalyzerUpdateTest {

    private TestProject project;
    private JavaAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        // initial Java source
        UpdateTestUtil.writeFile(
                rootDir,
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
        }
        """);

        project = UpdateTestUtil.newTestProject(rootDir, Languages.JAVA);
        analyzer = new JavaAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdateWithProvidedSet() throws IOException {
        // verify initial state
        assertTrue(analyzer.getDefinition("A.method1").isPresent());
        assertTrue(analyzer.getDefinition("A.method2").isEmpty());

        // mutate source – add method2
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
          public int method2() { return 2; }
        }
        """);

        // before update the analyzer still returns old view
        assertTrue(analyzer.getDefinition("A.method2").isEmpty());

        // update ONLY this file
        var maybeFile = analyzer.getFileFor("A");
        assertTrue(maybeFile.isPresent());
        analyzer.update(Set.of(maybeFile.get()));

        // method2 should now be visible
        assertTrue(analyzer.getDefinition("A.method2").isPresent());

        // change again but don't include file in explicit set
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
          public int method2() { return 2; }
          public int method3() { return 3; }
        }
        """);
        // call update with empty set – no change expected
        analyzer.update(Set.of());
        assertTrue(analyzer.getDefinition("A.method3").isEmpty());
    }

    @Test
    void automaticUpdateDetection() throws IOException {
        // add new method then rely on hash detection
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
          public int method4() { return 4; }
        }
        """);
        analyzer.update(); // no-arg detection
        assertTrue(analyzer.getDefinition("A.method4").isPresent());

        // delete file – analyzer should drop symbols
        var maybeFile = analyzer.getFileFor("A");
        assertTrue(maybeFile.isPresent());
        java.nio.file.Files.deleteIfExists(maybeFile.get().absPath());

        analyzer.update();
        assertTrue(analyzer.getDefinition("A").isEmpty());
    }

    @Test
    void concurrentReadsDuringUpdate() throws Exception {
        // Ensure initial symbol exists
        assertTrue(analyzer.getDefinition("A").isPresent());
        assertTrue(analyzer.getDefinition("A.method1").isPresent());

        var maybeFile = analyzer.getFileFor("A");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        var stop = new AtomicBoolean(false);
        var anomalies = new AtomicInteger(0);
        var symbolNotFound = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(5);

        // Updater task
        exec.submit(() -> {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            int i = 0;
            try {
                while (System.nanoTime() < end) {
                    i++;
                    String content = (i % 2 == 0)
                            ? """
                    public class A {
                      public int method1() { return 1; }
                      public int method2() { return 2; }
                    }
                    """
                            : """
                    public class A {
                      public int method1() { return 1; }
                    }
                    """;
                    UpdateTestUtil.writeFile(project.getRoot(), "A.java", content);
                    analyzer.update(Set.of(file));
                }
            } catch (Throwable t) {
                anomalies.incrementAndGet();
            } finally {
                stop.set(true);
            }
        });

        // Reader tasks
        Runnable reader = () -> {
            while (!stop.get()) {
                try {
                    // Class A should always be available; fetch source atomically via analyzer's read lock
                    var clsSrc = analyzer.getClassSource("A", true);
                    if (clsSrc.isEmpty() || clsSrc.get().isBlank()) {
                        anomalies.incrementAndGet();
                        return;
                    }

                    // method1 should always be available
                    var m1Sources = analyzer.getMethodSources("A.method1", true);
                    if (m1Sources.isEmpty() || m1Sources.stream().anyMatch(String::isBlank)) {
                        anomalies.incrementAndGet();
                        return;
                    }

                    // method2 may or may not exist depending on the current version; if present, contents must be
                    // non-blank
                    var m2Sources = analyzer.getMethodSources("A.method2", true);
                    if (!m2Sources.isEmpty() && m2Sources.stream().anyMatch(String::isBlank)) {
                        anomalies.incrementAndGet();
                        return;
                    }
                } catch (io.github.jbellis.brokk.analyzer.SymbolNotFoundException snfe) {
                    symbolNotFound.incrementAndGet();
                    return;
                } catch (Throwable t) {
                    anomalies.incrementAndGet();
                    return;
                }
            }
        };

        exec.submit(reader);
        exec.submit(reader);
        exec.submit(reader);
        exec.submit(reader);

        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, symbolNotFound.get(), "Observed SymbolNotFoundException during concurrent access");
        assertEquals(0, anomalies.get(), "Observed inconsistent snapshot results during concurrent access");
    }

    @Test
    void ephemeralSnapshotsNotRetainedAfterUpdate() throws Exception {
        // Initially, no AST snapshots should be retained.
        assertEquals(0, analyzer.cacheSize(), "No AST cache entries should be retained after construction");

        // Modify file and perform explicit update
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
          public int method2() { return 2; }
        }
        """);
        var maybeFile = analyzer.getFileFor("A");
        assertTrue(maybeFile.isPresent());
        analyzer.update(Set.of(maybeFile.get()));

        // After update the AST cache should still not retain parsed trees
        assertEquals(0, analyzer.cacheSize(), "No AST cache entries should be retained after update");

        // Trigger mtime-based detection
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.java",
                """
        public class A {
          public int method1() { return 1; }
        }
        """);
        analyzer.update();

        // Still no retained ASTs
        assertEquals(0, analyzer.cacheSize(), "No AST cache entries should be retained after automatic detection");
    }
}
