package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class JvmBasedAnalyzerTest {

    @Test
    public void testJavaAnalyzerIsJvmBased() {
        IProject project = new TestProject(Path.of(".").toAbsolutePath().normalize());
        JavaAnalyzer analyzer = new JavaAnalyzer(project);
        assertTrue(analyzer instanceof JvmBasedAnalyzer, "JavaAnalyzer should implement JvmBasedAnalyzer");
    }

    @Test
    public void testScalaAnalyzerIsJvmBased() {
        IProject project = new TestProject(Path.of(".").toAbsolutePath().normalize());
        ScalaAnalyzer analyzer = new ScalaAnalyzer(project);
        assertTrue(analyzer instanceof JvmBasedAnalyzer, "ScalaAnalyzer should implement JvmBasedAnalyzer");
    }
}
