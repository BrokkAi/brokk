package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.cli.HeadlessConsole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BlitzForgeTest {

    private static IContextManager stubCm() {
        // Use interface defaults; no special behavior needed for this minimal test
        return new IContextManager() {};
    }

    @Test
    void basicParallelAggregation() throws Exception {
        Path root = Files.createTempDirectory("bf-core");
        var f1 = new ProjectFile(root, "A.txt");
        var f2 = new ProjectFile(root, "B.txt");
        Files.writeString(f1.absPath(), "hello A");
        Files.writeString(f2.absPath(), "hello B");

        BlitzForge.RunConfig cfg = new BlitzForge.RunConfig(
                "Do thing",
                null,
                (Supplier<String>) () -> "",
                (Supplier<String>) () -> "",
                "",
                BlitzForge.ParallelOutputMode.ALL);

        class StubListener implements BlitzForge.Listener {
            final AtomicInteger starts = new AtomicInteger();
            final AtomicInteger progresses = new AtomicInteger();
            TaskResult done;

            @Override
            public void onStart(int total) {
                starts.set(total);
            }

            @Override
            public IConsoleIO getConsoleIO(ProjectFile file) {
                return new HeadlessConsole();
            }

            @Override
            public void onFileResult(
                    ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {
                progresses.incrementAndGet();
            }

            @Override
            public void onFileComplete(TaskResult result) {
                done = result;
            }
        }

        var listener = new StubListener();
        var engine = new BlitzForge(stubCm(), null, cfg, listener);

        var result = engine.executeParallel(
                List.of(f1, f2), file -> new BlitzForge.FileResult(file, true, null, "OK " + file.getFileName()));

        assertEquals(2, listener.starts.get(), "onStart should receive total file count");
        assertEquals(2, listener.progresses.get(), "Should have processed both files");
        assertNotNull(listener.done, "onDone should provide a TaskResult");
        assertEquals(TaskResult.StopReason.SUCCESS, listener.done.stopDetails().reason());
        // changed files set contains both inputs
        Set<ProjectFile> changed = listener.done.changedFiles();
        assertEquals(Set.of(f1, f2), changed);
    }
}
