package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
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

    @Test
    void scheduleRefreshDebouncesAcrossThreads(@TempDir Path tempDir) throws Exception {
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
            var treeRef = new AtomicReference<ProjectTree>();
            SwingUtilities.invokeAndWait(() -> treeRef.set(new TestableProjectTree(project, contextManager, host)));
            var tree = treeRef.get();

            var initialLoadComplete = new AtomicBoolean(false);
            for (int i = 0; i < 200; i++) {
                SwingUtilities.invokeAndWait(() -> {
                    var model = (DefaultTreeModel) tree.getModel();
                    var root = (DefaultMutableTreeNode) model.getRoot();

                    boolean placeholderOnly = root.getChildCount() == 1
                            && root.getFirstChild() instanceof DefaultMutableTreeNode dmtn
                            && "Loading...".equals(dmtn.getUserObject());

                    initialLoadComplete.set(!placeholderOnly);
                });
                if (initialLoadComplete.get()) {
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(initialLoadComplete.get());

            var refreshStartCount = new AtomicInteger(0);
            var lastWasPlaceholderOnly = new AtomicBoolean(false);

            SwingUtilities.invokeAndWait(() -> {
                var model = (DefaultTreeModel) tree.getModel();
                var root = (DefaultMutableTreeNode) model.getRoot();

                boolean placeholderOnly = root.getChildCount() == 1
                        && root.getFirstChild() instanceof DefaultMutableTreeNode dmtn
                        && "Loading...".equals(dmtn.getUserObject());
                lastWasPlaceholderOnly.set(placeholderOnly);

                TreeModelListener listener = e -> {
                    var currentRoot = (DefaultMutableTreeNode) model.getRoot();
                    boolean nowPlaceholderOnly = currentRoot.getChildCount() == 1
                            && currentRoot.getFirstChild() instanceof DefaultMutableTreeNode dmtn2
                            && "Loading...".equals(dmtn2.getUserObject());

                    boolean previous = lastWasPlaceholderOnly.getAndSet(nowPlaceholderOnly);
                    if (nowPlaceholderOnly && !previous) {
                        refreshStartCount.incrementAndGet();
                    }
                };

                model.addTreeModelListener(listener);
            });

            CompletableFuture.runAsync(() -> {
                        for (int i = 0; i < 50; i++) {
                            tree.scheduleRefresh();
                        }
                    })
                    .join();

            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> {});

            assertEquals(1, refreshStartCount.get());
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

    private static final class TestableProjectTree extends ProjectTree {
        private TestableProjectTree(TestProject project, ContextManager contextManager, ProjectTreeHost host) {
            super(project, contextManager, host);
        }

        @Override
        public boolean isShowing() {
            return true;
        }
    }
}
