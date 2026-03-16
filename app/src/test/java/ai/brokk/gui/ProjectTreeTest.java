package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.ChatMessageType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectTreeTest {

    @Test
    void canInstantiateOnEdtAndScheduleRefresh(@TempDir Path tempDir) throws Exception {
        var srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "class Main {}");

        var project = new TestProject(tempDir);

        Set<ProjectFile> trackedFiles;
        try (var walk = Files.walk(tempDir)) {
            trackedFiles = walk.filter(Files::isRegularFile)
                    .map(p -> new ProjectFile(tempDir, tempDir.relativize(p)))
                    .collect(Collectors.toSet());
        }

        Set<Path> trackedRelPaths =
                trackedFiles.stream().map(ProjectFile::getRelPath).collect(Collectors.toSet());

        project.withRepo(new IGitRepo() {
            @Override
            public Set<ProjectFile> getTrackedFiles() {
                return trackedFiles;
            }

            @Override
            public boolean isTracked(Path relativePath) {
                return trackedRelPaths.contains(relativePath);
            }

            @Override
            public void add(Collection<ProjectFile> files) {
                // no-op for test
            }

            @Override
            public void add(ProjectFile file) {
                // no-op for test
            }

            @Override
            public void remove(ProjectFile file) {
                // no-op for test
            }
        });

        var host = new TestProjectTreeHost();

        var contextManager = new ContextManager(project);
        contextManager.createHeadless(true, host);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var tree = new ProjectTree(project, contextManager, host);
                tree.scheduleRefresh();
            });
        } finally {
            contextManager.close();
        }
    }

    private static final class TestProjectTreeHost implements ProjectTreeHost {
        @Override
        public void toolError(String msg, String title) {
            // no-op for unit test
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
            // no-op for unit test
        }
    }
}
