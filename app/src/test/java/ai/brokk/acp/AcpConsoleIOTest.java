package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.tools.ToolExecutionResult;
import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpConsoleIOTest {

    @Test
    void toolCallEmitsInProgressUpdate() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        var request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("readFile")
                .arguments("{}")
                .build();

        io.beforeToolCall(request, false);
        io.toolCallInProgress(request);
        io.afterToolOutput(ToolExecutionResult.success(request, "hello"));

        var calls = ctx.updates.stream()
                .filter(AcpSchema.ToolCall.class::isInstance)
                .map(AcpSchema.ToolCall.class::cast)
                .toList();
        assertEquals(1, calls.size());
        assertEquals(AcpSchema.ToolCallStatus.PENDING, calls.getFirst().status());

        var updates = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .toList();
        assertEquals(2, updates.size());
        assertEquals(AcpSchema.ToolCallStatus.IN_PROGRESS, updates.get(0).status());
        assertEquals("tool-1", updates.get(0).toolCallId());
        assertEquals(AcpSchema.ToolKind.READ, updates.get(0).kind());
        assertEquals(AcpSchema.ToolCallStatus.COMPLETED, updates.get(1).status());
    }

    @Test
    void editToolEmitsDiffContentBlock(@TempDir Path tempDir) {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        var pf = new ProjectFile(tempDir, Path.of("foo.txt"));
        var request = ToolExecutionRequest.builder()
                .id("edit-1")
                .name("replaceLines")
                .arguments("{}")
                .build();

        io.beforeToolCall(request, true); // destructive => EDIT kind
        io.toolCallInProgress(request);
        io.afterFileEdits(Map.of(pf, "before\n"), Map.of(pf, "after\n"));
        io.afterToolOutput(ToolExecutionResult.success(request, "ok"));

        var diffUpdate = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.content().stream().anyMatch(AcpSchema.ToolCallDiff.class::isInstance))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a tool_call_update with a diff content block"));

        var diff = (AcpSchema.ToolCallDiff)
                diffUpdate.content().stream()
                        .filter(AcpSchema.ToolCallDiff.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertEquals(pf.absPath().toString(), diff.path());
        assertEquals("before\n", diff.oldText());
        assertEquals("after\n", diff.newText());
        assertEquals("edit-1", diffUpdate.toolCallId());
        assertEquals(AcpSchema.ToolKind.EDIT, diffUpdate.kind());
        assertEquals(AcpSchema.ToolCallStatus.IN_PROGRESS, diffUpdate.status());
    }

    @Test
    void afterFileEditsIsNoOpOutsideAnEditTool(@TempDir Path tempDir) {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var pf = new ProjectFile(tempDir, Path.of("foo.txt"));

        io.afterFileEdits(Map.of(pf, "x"), Map.of(pf, "y"));

        assertTrue(ctx.updates.isEmpty());
    }

    @Test
    void toolCallInProgressIsNoOpWhenRequestIdIsNull() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        var request =
                ToolExecutionRequest.builder().name("readFile").arguments("{}").build();

        io.toolCallInProgress(request);

        assertTrue(ctx.updates.isEmpty());
    }

    private static final class RecordingPromptContext implements AcpPromptContext {
        final List<AcpSchema.SessionUpdate> updates = new ArrayList<>();

        @Override
        public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
            updates.add(update);
        }

        @Override
        public String getSessionId() {
            return "test-session";
        }

        @Override
        public @Nullable NegotiatedCapabilities getClientCapabilities() {
            return null;
        }

        @Override
        public void sendMessage(String text) {}

        @Override
        public void sendThought(String text) {}

        @Override
        public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readFile(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> tryReadFile(String path) {
            return Optional.empty();
        }

        @Override
        public void writeFile(String path, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean askPermission(String action, String toolName) {
            return true;
        }

        @Override
        public @Nullable String askChoice(String question, String... options) {
            return options.length > 0 ? options[0] : null;
        }

        @Override
        public CommandResult execute(String... commandAndArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandResult execute(Command command) {
            throw new UnsupportedOperationException();
        }
    }
}
