package io.github.jbellis.brokk;

import io.github.jbellis.brokk.util.StackTrace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StackTraceTest {

    @Test
    public void testStackTraceClassic() {
        var stackTraceStr = """
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
        var stackTraceStr = """
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
        var stackTraceStr = """
        ERROR [Native-Transport-Requests-1] 2025-02-15 07:28:44,261 QueryMessage.java:121 - Unexpected error during query
        java.lang.UnsupportedOperationException: Unable to authorize statement org.apache.cassandra.cql3.statements.DescribeStatement$4
                at io.stargate.db.cassandra.impl.StargateQueryHandler.authorizeByToken(StargateQueryHandler.java:320)
        ERROR
        """;

        var st = StackTrace.parse(stackTraceStr);
        assertEquals("UnsupportedOperationException", st.getExceptionType());
        assertEquals(1, st.getFrames().size());
        
        stackTraceStr = """
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
    public void testStackTraceElementMethodFullNameLambdaParsing() {
        var stackTraceElement = new StackTraceElement("io.github.jbellis.brokk.gui.GitCommitTab", "lambda$rollbackChangesWithUndo$19", "GitCommitTab.java", 565);
        var fullName = StackTrace.methodFullNameFromStacktraceElement(stackTraceElement);
        assertEquals("io.github.jbellis.brokk.gui.GitCommitTab.rollbackChangesWithUndo", fullName);
                
    }
}
