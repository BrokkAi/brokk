package ai.brokk.analyzer.update;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CSharpAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.*;

class CSharpAnalyzerUpdateTest {

    private TestProject project;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() throws IOException {
        var rootDir = UpdateTestUtil.newTempDir();
        UpdateTestUtil.writeFile(
                rootDir,
                "A.cs",
                """
                namespace TestNs {
                  public class A {
                    public int Method1() { return 1; }
                  }
                }
                """);
        project = UpdateTestUtil.newTestProject(rootDir, Languages.C_SHARP);
        analyzer = new CSharpAnalyzer(project);
    }

    @AfterEach
    void tearDown() {
        if (project != null) project.close();
    }

    @Test
    void explicitUpdate() throws IOException {
        assertTrue(analyzer.getDefinition("TestNs.A.Method1").isPresent());
        assertTrue(analyzer.getDefinition("TestNs.A.Method2").isEmpty());

        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.cs",
                """
                namespace TestNs {
                  public class A {
                    public int Method1() { return 1; }
                    public int Method2() { return 2; }
                  }
                }
                """);

        var file = analyzer.getFileFor("TestNs.A").orElseThrow();
        analyzer = analyzer.update(Set.of(file));

        assertTrue(analyzer.getDefinition("TestNs.A.Method2").isPresent());
    }

    @Test
    void autoDetect() throws IOException {
        UpdateTestUtil.writeFile(
                project.getRoot(),
                "A.cs",
                """
                namespace TestNs {
                  public class A {
                    public int Method1() { return 1; }
                    public int Method3() { return 3; }
                  }
                }
                """);
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("TestNs.A.Method3").isPresent());

        var file = analyzer.getFileFor("TestNs.A").orElseThrow();
        Files.deleteIfExists(file.absPath());
        analyzer = analyzer.update();
        assertTrue(analyzer.getDefinition("TestNs.A").isEmpty());
    }
}
