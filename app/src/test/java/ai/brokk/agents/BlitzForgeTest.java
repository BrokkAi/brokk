package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.IProject;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.context.Context;
import ai.brokk.testutil.TestProject;
import ai.brokk.testutil.TestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BlitzForgeTest {

    private static IContextManager stubCm() {
        // Use interface defaults; no special behavior needed for this minimal test
        return new IContextManager() {
            @Override
            public Context topContext() {
                // Return an empty Context for test purposes
                return new Context(this, null);
            }
        };
    }

    private static IProject stubProject() throws Exception {
        var root = Files.createTempDirectory("bftest-");
        return new TestProject(root);
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
                new Service.UnavailableStreamingModel(),
                (Supplier<String>) () -> "",
                (Supplier<String>) () -> "",
                "",
                BlitzForge.ParallelOutputMode.ALL);

        class StubListener implements BlitzForge.Listener {
            final AtomicInteger starts = new AtomicInteger();
            final Map<ProjectFile, Boolean> filesEdited = new ConcurrentHashMap<>();
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
                filesEdited.put(file, edited);
            }

            @Override
            public void onComplete(TaskResult result) {
                done = result;
            }
        }

        var listener = new StubListener();
        var service = new TestService(stubProject());
        var engine = new BlitzForge(stubCm(), service, cfg, listener);
        engine.executeParallel(
                List.of(f1, f2), file -> new BlitzForge.FileResult(file, true, null, "OK " + file.getFileName()));

        assertEquals(2, listener.starts.get(), "onStart should receive total file count");
        assertEquals(2, listener.filesEdited.size(), "Should have processed both files");
        assertNotNull(listener.done, "onDone should provide a TaskResult");
        assertEquals(TaskResult.StopReason.SUCCESS, listener.done.stopDetails().reason());
        // changed files set contains both inputs
        assertEquals(Set.of(f1, f2), listener.filesEdited.keySet());
    }
}
