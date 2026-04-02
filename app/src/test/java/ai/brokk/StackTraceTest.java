package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.util.StackTrace;
import org.junit.jupiter.api.Test;

public class StackTraceTest {

    @Test
    public void testStackTraceClassic() {
        var stackTraceStr =
                """
        Exception in thread "main" java.lang.IllegalArgumentException: requirement failed
                at scala.Predef$.require(Predef.scala:324)
                at io.github.jbellis.brokk.RepoFile.<init>(RepoFile.scala:16)
                at io.github.jbellis.brokk.RepoFile.<init>(RepoFile.scala:13)
                at io.github.jbellis.brokk.Completions.expandPath(Completions.java:206)
        """;

        var st = StackTrace.parse(stackTraceStr);
        assertEquals("IllegalArgumentException", st.getExceptionType());
        assertEquals(4, st.getFrames().size());
    }

    @Test
    public void testStackTraceFrames() {
        var stackTraceStr =
                """
        java.lang.IllegalArgumentException: Cannot convert value [22, 3000000000, 5000000000] of type class java.util.ArrayList
            at org.apache.cassandra.utils.ByteBufferUtil.objectToBytes(ByteBufferUtil.java:577)
            at org.apache.cassandra.distributed.impl.Coordinator.lambda$executeWithPagingWithResult$2(Coordinator.java:142)
            at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
            at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
            at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
            at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
            at java.base/java.lang.Thread.run(Thread.java:829)
        """;

        var st = StackTrace.parse(stackTraceStr);
        assertEquals("IllegalArgumentException", st.getExceptionType());
        assertEquals(7, st.getFrames().size());
        assertEquals(2, st.getFrames("org.apache.cassandra").size());
        assertEquals(1, st.getFrames("org.apache.cassandra.distributed").size());
        assertEquals(3, st.getFrames("java.util.concurrent").size());
        assertEquals(1, st.getFrames("io.netty").size());
        assertEquals(1, st.getFrames("java.lang").size());
    }

    @Test
    public void testStackTraceWithLeadingTrailingNoise() {
        var stackTraceStr =
                """
        ERROR [Native-Transport-Requests-1] 2025-02-15 07:28:44,261 QueryMessage.java:121 - Unexpected error during query
        java.lang.UnsupportedOperationException: Unable to authorize statement org.apache.cassandra.cql3.statements.DescribeStatement$4
                at io.stargate.db.cassandra.impl.StargateQueryHandler.authorizeByToken(StargateQueryHandler.java:320)
        ERROR
        """;

        var st = StackTrace.parse(stackTraceStr);
        assertEquals("UnsupportedOperationException", st.getExceptionType());
        assertEquals(1, st.getFrames().size());

        stackTraceStr =
                """
        [error] Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke "java.io.ByteArrayOutputStream.toByteArray()" because "this.micBuffer" is null
        [error]         at io.github.jbellis.brokk.gui.VoiceInputButton.stopMicCaptureAndTranscribe(VoiceInputButton.java:175)
        [error]         at io.github.jbellis.brokk.gui.VoiceInputButton.lambda$new$0(VoiceInputButton.java:89)
        """;

        var st2 = StackTrace.parse(stackTraceStr);
        assertEquals("NullPointerException", st2.getExceptionType());
        assertEquals(2, st2.getFrames().size());
        assertEquals(2, st2.getFrames("io.github.jbellis.brokk.gui").size());
    }

    @Test
    public void testJacksonStackTrace() {
        var stackTraceStr =
                """
            2026-02-01 12:22:47,697 [brokk-analyzer-exec-brokk] DEBUG TreeSitterStateIO.load:381 - Incompatible analyzer state at /Users/jonathan/Projects/brokk/.brokk/java.bin.gzip details: com.fasterxml.jackson.databind.exc.MismatchedInputException: Cannot construct instance
             at [Source: (GZIPInputStream); byte offset: #28205931] (through reference chain: ai.brokk.analyzer.TreeSitterStateIO$AnalyzerStateDto["fileState"])
            com.fasterxml.jackson.databind.exc.MismatchedInputException: Cannot construct instance
             at [Source: (GZIPInputStream); byte offset: #28205931] (through reference chain: ai.brokk.analyzer.TreeSitterStateIO$AnalyzerStateDto["fileState"])
                at com.fasterxml.jackson.databind.exc.MismatchedInputException.from(MismatchedInputException.java:63)
                at ai.brokk.analyzer.TreeSitterStateIO.load(TreeSitterStateIO.java:369)
                at ai.brokk.analyzer.JavaLanguage.loadAnalyzer(JavaLanguage.java:53)
            """;

        var st = StackTrace.parse(stackTraceStr);
        java.util.Objects.requireNonNull(st, "Parsed StackTrace should not be null");
        assertEquals("MismatchedInputException", st.getExceptionType());
        // 3 standard frames, the Jackson metadata lines are ignored for getFrames() but preserved in original text
        assertEquals(3, st.getFrames().size());
        assertEquals(2, st.getFrames("ai.brokk.analyzer").size());

        // Verify the frames were correctly attributed to the packages/classes
        var analyzerFrames = st.getFrames("ai.brokk.analyzer");
        assertEquals(
                "ai.brokk.analyzer.TreeSitterStateIO", analyzerFrames.get(0).getClassName());
        assertEquals("ai.brokk.analyzer.JavaLanguage", analyzerFrames.get(1).getClassName());
    }

    @Test
    public void testStackTraceWithInnerClass() {
        var stackTraceStr =
                """
        [Error: dev.langchain4j.exception.LangChain4jException: closed
            at dev.langchain4j.internal.ExceptionMapper$DefaultExceptionMapper.mapException(ExceptionMapper.java:48)
            at dev.langchain4j.model.openai.OpenAiStreamingChatModel.lambda$doChat$4(OpenAiStreamingChatModel.java:123)
        """;

        var st = StackTrace.parse(stackTraceStr);
        // Do not assert exception type here since the example line includes a prefix with a colon
        assertEquals(2, st.getFrames().size());

        var first = st.getFrames().get(0);
        assertEquals("dev.langchain4j.internal.ExceptionMapper$DefaultExceptionMapper", first.getClassName());
        assertEquals("mapException", first.getMethodName());
        assertEquals("ExceptionMapper.java", first.getFileName());
        assertEquals(48, first.getLineNumber());

        // Ensure package filtering works even with '$' in the class name
        assertEquals(1, st.getFrames("dev.langchain4j.internal").size());
        assertEquals(1, st.getFrames("dev.langchain4j.internal.ExceptionMapper").size());
    }

    @Test
    public void testStackTraceLambdasAreNormalizedToEnclosingMethodName() {
        var stackTraceStr =
                """
        java.lang.RuntimeException: java.lang.IllegalArgumentException: image == null!
            at ai.brokk.concurrent.UserActionManager.lambda$submitLlmAction$2(UserActionManager.java:127)
            at ai.brokk.concurrent.LoggingExecutorService.lambda$wrap$7(LoggingExecutorService.java:113)
            at ai.brokk.concurrent.LoggingExecutorService.lambda$submit$2(LoggingExecutorService.java:58)
            at java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:572)
            at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
            at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
            at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
            at java.base/java.lang.Thread.run(Thread.java:1583)
        Caused by: java.lang.IllegalArgumentException: image == null!
            at java.desktop/javax.imageio.ImageTypeSpecifier.createFromRenderedImage(ImageTypeSpecifier.java:917)
            at java.desktop/javax.imageio.ImageIO.getWriter(ImageIO.java:1603)
            at java.desktop/javax.imageio.ImageIO.write(ImageIO.java:1591)
            at ai.brokk.util.ImageUtil.toL4JImage(ImageUtil.java:49)
            at ai.brokk.prompts.WorkspacePrompts.formatWithPolicy(WorkspacePrompts.java:464)
            at ai.brokk.prompts.WorkspacePrompts.getMessagesInAddedOrder(WorkspacePrompts.java:144)
            at ai.brokk.prompts.SearchPrompts.buildPruningPrompt(SearchPrompts.java:268)
            at ai.brokk.agents.SearchAgent.pruneContext(SearchAgent.java:490)
            at ai.brokk.agents.SearchAgent.executeInternal(SearchAgent.java:276)
            at ai.brokk.agents.SearchAgent.execute(SearchAgent.java:259)
            at ai.brokk.gui.InstructionsPanel.lambda$executeSearchInternal$34(InstructionsPanel.java:1948)
            at ai.brokk.gui.InstructionsPanel.lambda$submitAction$38(InstructionsPanel.java:2055)
            at ai.brokk.concurrent.UserActionManager.lambda$submitLlmAction$2(UserActionManager.java:124)
            ... 7 more
        """;

        var st = StackTrace.parse(stackTraceStr);
        java.util.Objects.requireNonNull(st, "Parsed StackTrace should not be null");

        var frames = st.getFrames();
        assertEquals("ai.brokk.concurrent.UserActionManager", frames.get(0).getClassName());
        assertEquals("submitLlmAction", frames.get(0).getMethodName());

        assertEquals("ai.brokk.concurrent.LoggingExecutorService", frames.get(1).getClassName());
        assertEquals("wrap", frames.get(1).getMethodName());

        assertEquals("ai.brokk.concurrent.LoggingExecutorService", frames.get(2).getClassName());
        assertEquals("submit", frames.get(2).getMethodName());

        var executeSearchInternal = frames.stream()
                .filter(f -> "ai.brokk.gui.InstructionsPanel".equals(f.getClassName()))
                .filter(f -> "executeSearchInternal".equals(f.getMethodName()))
                .findFirst()
                .orElse(null);
        java.util.Objects.requireNonNull(executeSearchInternal);

        var submitAction = frames.stream()
                .filter(f -> "ai.brokk.gui.InstructionsPanel".equals(f.getClassName()))
                .filter(f -> "submitAction".equals(f.getMethodName()))
                .findFirst()
                .orElse(null);
        java.util.Objects.requireNonNull(submitAction);
    }
}
