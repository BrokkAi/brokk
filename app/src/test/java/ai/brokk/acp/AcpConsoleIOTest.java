package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.LlmOutputMeta;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.tools.ToolExecutionResult;
import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
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

        var diff = (AcpSchema.ToolCallDiff) diffUpdate.content().stream()
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

    @Test
    void titleIsHumanizedForUnknownTool() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("t1")
                .name("getFileContents")
                .arguments("{\"filename\":\"app/Foo.java\"}")
                .build();

        io.beforeToolCall(request, false);

        var call = (AcpSchema.ToolCall) ctx.updates.getFirst();
        assertEquals("Read app/Foo.java", call.title());
    }

    @Test
    void titleUsesArgsForSearchTool() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("s1")
                .name("findFilesContaining")
                .arguments("{\"pattern\":\"parseRequest\"}")
                .build();

        io.beforeToolCall(request, false);

        var call = (AcpSchema.ToolCall) ctx.updates.getFirst();
        assertEquals("Search files for 'parseRequest'", call.title());
    }

    @Test
    void titleHumanizesNovelToolName() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("n1")
                .name("computeCyclomaticComplexity")
                .arguments("{}")
                .build();

        io.beforeToolCall(request, false);

        var call = (AcpSchema.ToolCall) ctx.updates.getFirst();
        assertEquals("Compute cyclomatic complexity", call.title());
    }

    @Test
    void terminalAnswerToolEmitsEmptyContentAndCleanTitle() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("a1")
                .name("answer")
                .arguments("{}")
                .build();

        io.beforeToolCall(request, false);
        io.afterToolOutput(ToolExecutionResult.success(request, "the long final answer text"));

        var completed = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.status() == AcpSchema.ToolCallStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        assertEquals("Final answer", completed.title());
        assertTrue(completed.content().isEmpty(), "terminal-answer tools must not duplicate the answer in content");
        assertNull(completed.rawOutput(), "rawOutput must be null to avoid double-rendering");
    }

    @Test
    void mcpShapedResultIsUnwrapped() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("m1")
                .name("callMcpTool")
                .arguments("{\"toolName\":\"foo\"}")
                .build();
        var mcpJson = "{\"content\":[{\"type\":\"text\",\"text\":\"hello from mcp\"}]}";

        io.beforeToolCall(request, false);
        io.afterToolOutput(ToolExecutionResult.success(request, mcpJson));

        var completed = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.status() == AcpSchema.ToolCallStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        var block = (AcpSchema.ToolCallContentBlock) completed.content().getFirst();
        var text = ((AcpSchema.TextContent) block.content()).text();
        assertEquals("hello from mcp", text);
    }

    @Test
    void searchResultArrayRendersAsBulletList() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("se1")
                .name("findFilenames")
                .arguments("{\"pattern\":\"*.java\"}")
                .build();
        var jsonArray = "[\"a/Foo.java\",\"b/Bar.java\"]";

        io.beforeToolCall(request, false);
        io.afterToolOutput(ToolExecutionResult.success(request, jsonArray));

        var completed = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.status() == AcpSchema.ToolCallStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        var block = (AcpSchema.ToolCallContentBlock) completed.content().getFirst();
        var text = ((AcpSchema.TextContent) block.content()).text();
        assertTrue(text.contains("- a/Foo.java"), "expected bullet for first hit, got: " + text);
        assertTrue(text.contains("- b/Bar.java"), "expected bullet for second hit, got: " + text);
        assertFalse(text.contains("[\""), "raw JSON brackets must not survive rendering");
    }

    @Test
    void genericJsonResultRendersAsFencedCodeBlock() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("g1")
                .name("createOrReplaceTaskList")
                .arguments("{}")
                .build();
        var jsonObj = "{\"task\":\"do thing\",\"done\":false}";

        io.beforeToolCall(request, false);
        io.afterToolOutput(ToolExecutionResult.success(request, jsonObj));

        var completed = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.status() == AcpSchema.ToolCallStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        var block = (AcpSchema.ToolCallContentBlock) completed.content().getFirst();
        var text = ((AcpSchema.TextContent) block.content()).text();
        assertTrue(text.startsWith("```json"), "expected fenced code block, got: " + text);
        assertTrue(text.endsWith("```"), "expected closing fence, got: " + text);
    }

    @Test
    void rawOutputIsNullOnCompletedUpdate() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);
        var request = ToolExecutionRequest.builder()
                .id("r1")
                .name("getFileContents")
                .arguments("{\"filename\":\"foo\"}")
                .build();

        io.beforeToolCall(request, false);
        io.afterToolOutput(ToolExecutionResult.success(request, "file contents"));

        var completed = ctx.updates.stream()
                .filter(AcpSchema.ToolCallUpdateNotification.class::isInstance)
                .map(AcpSchema.ToolCallUpdateNotification.class::cast)
                .filter(u -> u.status() == AcpSchema.ToolCallStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        assertNull(completed.rawOutput(), "rawOutput should not duplicate content");
    }

    @Test
    void customLlmOutputEmitsSyntheticToolCallNotChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        io.llmOutput("**Code Agent** engaged:\nedit Foo.java", ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());

        assertTrue(ctx.messages.isEmpty(), "CUSTOM banners must not flow through agent_message_chunk");
        var call = ctx.updates.stream()
                .filter(AcpSchema.ToolCall.class::isInstance)
                .map(AcpSchema.ToolCall.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("Code Agent", call.title());
        assertEquals(AcpSchema.ToolKind.THINK, call.kind());
        assertEquals(AcpSchema.ToolCallStatus.COMPLETED, call.status());
        var block = (AcpSchema.ToolCallContentBlock) call.content().getFirst();
        var text = ((AcpSchema.TextContent) block.content()).text();
        assertTrue(text.contains("edit Foo.java"), "body should carry the banner content");
    }

    @Test
    void showNotificationEmitsCompactToolCall() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        io.showNotification(ai.brokk.IConsoleIO.NotificationRole.INFO, "build started");

        assertTrue(ctx.messages.isEmpty(), "notifications must not flow through agent_message_chunk");
        var call = ctx.updates.stream()
                .filter(AcpSchema.ToolCall.class::isInstance)
                .map(AcpSchema.ToolCall.class::cast)
                .findFirst()
                .orElseThrow();
        // Title carries the message; body stays empty so the IDE renders a single-line entry.
        assertEquals("build started", call.title());
        assertEquals(AcpSchema.ToolCallStatus.COMPLETED, call.status());
        assertTrue(call.content().isEmpty(), "compact notifications must have empty content");
    }

    @Test
    void aiTokensStillFlowAsChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        io.llmOutput("hello world", ChatMessageType.AI, LlmOutputMeta.DEFAULT);

        assertEquals(List.of("hello world"), ctx.messages);
        assertTrue(ctx.updates.isEmpty(), "AI tokens must still go through agent_message_chunk");
    }

    @Test
    void aiBoldBannerShapedTokenFlowsAsChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        // After removing the AI-stream banner-detection heuristic, banner-shaped text in the
        // AI prose stream is treated as plain prose. Agents that want a synthetic tool_call
        // must call showStatusBanner directly.
        var token = "\n**Brokk Context Engine** analyzing repository context\n";
        io.llmOutput(token, ChatMessageType.AI, LlmOutputMeta.newMessage());

        assertEquals(List.of(token), ctx.messages);
        assertTrue(ctx.updates.isEmpty(), "AI prose must not be auto-promoted to a synthetic tool_call");
    }

    @Test
    void aiBacktickBannerShapedTokenFlowsAsChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        var explanation =
                "`Adding context to workspace`\n```yaml\nfragmentCount: 2\nfragments:\n  - foo\n  - bar\n```\n";
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);

        assertEquals(List.of(explanation), ctx.messages);
        assertTrue(ctx.updates.isEmpty(), "Backtick-shaped AI prose must not be auto-promoted");
    }

    @Test
    void aiPartialBoldTokenStreamsAsChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        // A streaming token that is just an opening `**` (no closing marker, no body) must NOT
        // trip banner detection — it's mid-stream LLM output that needs to keep flowing as chat.
        // A complete `**Bold**` with no body after also stays as chat (mid-sentence emphasis).
        io.llmOutput("**", ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        io.llmOutput("**Bold**", ChatMessageType.AI, LlmOutputMeta.DEFAULT);

        assertEquals(List.of("**", "**Bold**"), ctx.messages);
        assertTrue(ctx.updates.isEmpty());
    }

    @Test
    void showStatusBannerEmitsTitleOnlyCard() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        io.showStatusBanner(
                "Adding context to workspace",
                java.util.Map.of("fragmentCount", 2, "fragments", List.of("foo", "bar")));

        assertTrue(ctx.messages.isEmpty(), "showStatusBanner must not flow to chat");
        var call = ctx.updates.stream()
                .filter(AcpSchema.ToolCall.class::isInstance)
                .map(AcpSchema.ToolCall.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("Adding context to workspace", call.title());
        assertEquals(AcpSchema.ToolKind.THINK, call.kind());
        assertTrue(call.content().isEmpty(), "title-only card carries no body");
    }

    @Test
    void aiAnswerHeaderStillFlowsAsChat() {
        var ctx = new RecordingPromptContext();
        var io = new AcpConsoleIO(ctx);

        // Final-answer text from terminal-answer tools is `# Answer\n\n…` — it does NOT match
        // the banner pattern, so it must continue flowing through chat.
        var answer = "# Answer\n\nThe project produces output via the GUI panels.";
        io.llmOutput(answer, ChatMessageType.AI, LlmOutputMeta.newMessage());

        assertEquals(List.of(answer), ctx.messages);
        assertTrue(ctx.updates.isEmpty(), "Final-answer text must stay as chat, not be wrapped");
    }

    private static final class RecordingPromptContext implements AcpPromptContext {
        final List<AcpSchema.SessionUpdate> updates = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<String> thoughts = new ArrayList<>();

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
        public void sendMessage(String text) {
            messages.add(text);
        }

        @Override
        public void sendThought(String text) {
            thoughts.add(text);
        }

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
