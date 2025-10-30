package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class JavaAnalyzerUpdateTest {

    private TestProject project;
    private IAnalyzer analyzer;

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
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        analyzer = analyzer.update(Set.of(maybeFile.get()));

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
        analyzer = analyzer.update(Set.of());
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
        analyzer = analyzer.update(); // no-arg detection
        assertTrue(analyzer.getDefinition("A.method4").isPresent());

        // delete file – analyzer should drop symbols
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        Files.deleteIfExists(maybeFile.get().absPath());

        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("A").isEmpty());
    }
}
