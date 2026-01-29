package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LlmHistoryParser using synthetic llm-history files.
 */
public class LlmHistoryParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TASK_DIR_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    @Test
    void testParseSingleCall(@TempDir Path tempDir) throws Exception {
        // Create .brokk/llm-history/<taskDir>
        String taskTs = LocalDateTime.of(2023, 1, 2, 15, 4, 5).format(TASK_DIR_TS_FMT);
        String taskDirName = taskTs + " ARCHITECT test-task";
        Path historyDir = tempDir.resolve(".brokk").resolve("llm-history").resolve(taskDirName);
        Files.createDirectories(historyDir);

        // Request file: time prefix "14-03.27" seq "001"
        String reqName = "14-03.27 001-request.json";
        String requestJson = """
                {
                  "model": "gpt-5",
                  "messages": [
                    { "role": "system", "content": "system prompt" },
                    { "role": "user", "content": "Install instructions: do X" },
                    { "role": "user", "content": "Please implement foo()" }
                  ]
                }
                """;
        Files.writeString(historyDir.resolve(reqName), requestJson, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        // Log file with sections
        String logName = "14-03.29 001-Response.log";
        String logContent = """
                # Request to gpt-5:
                
                some representation
                
                ## reasoningContent
                This is the chain-of-thought reasoning captured.
                
                ## text
                The answer produced by the model.
                
                ## toolExecutionRequests
                [
                  { "name": "echo", "arguments": { "arg": "val" } }
                ]
                
                ## metadata
                {}
                """;
        Files.writeString(historyDir.resolve(logName), logContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        // Run parser
        List<JobTimeline> jobs = LlmHistoryParser.parse(tempDir);
        assertNotNull(jobs);
        assertEquals(1, jobs.size());

        JobTimeline job = jobs.get(0);
        assertEquals(taskDirName, job.jobId());

        // instructionsPanelText should be present in aggregates (first non-system user message)
        assertTrue(job.aggregates().containsKey("instructionsPanelText"));
        assertEquals("Install instructions: do X", job.aggregates().get("instructionsPanelText"));

        // Calls
        List<CallTimeline> calls = job.calls();
        assertEquals(1, calls.size());
        CallTimeline call = calls.get(0);

        assertNotNull(call.model());
        assertEquals("gpt-5", call.model().name());

        // Prompt contains the messages we wrote
        assertNotNull(call.promptRaw());
        assertTrue(call.promptRaw().contains("Install instructions: do X"));
        assertTrue(call.promptRaw().contains("Please implement foo()"));

        // Completion text extracted from log
        assertEquals("The answer produced by the model.", call.completionRaw());

        // Reasoning content extracted
        assertEquals("This is the chain-of-thought reasoning captured.", call.reasoningContent());

        // Tool calls parsed
        assertEquals(1, call.tools().size());
        ToolCallTimeline t = call.tools().get(0);
        assertEquals("echo", t.toolName());
        assertTrue(t.inputSummary().contains("\"arg\":\"val\"") || t.inputSummary().contains("arg"));

        // Timestamps: start should be derived from task date + request time "14-03.27"
        assertNotNull(call.startTime());
        assertNotNull(call.endTime());
        assertTrue(call.durationMs() != null && call.durationMs() > 0);
    }

    @Test
    void testMalformedMissingSectionsDontThrow(@TempDir Path tempDir) throws IOException {
        String taskTs = LocalDateTime.of(2023, 6, 7, 10, 11, 12).format(TASK_DIR_TS_FMT);
        String taskDirName = taskTs + " other test";
        Path hist = tempDir.resolve(".brokk").resolve("llm-history").resolve(taskDirName);
        Files.createDirectories(hist);

        // Create a request with odd shape but valid JSON
        String reqName = "09-05.10 002-request.json";
        String reqJson = "{ \"messages\": [ { \"role\": \"user\", \"content\": \"Only a short prompt\" } ] }";
        Files.writeString(hist.resolve(reqName), reqJson, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        // Create a log without expected sections or with malformed tool JSON
        String logName = "09-05.12 002-Err.log";
        String badLog = """
                # Request to some-model:
                
                ## text
                partial output only
                """;
        Files.writeString(hist.resolve(logName), badLog, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        List<JobTimeline> jobs = LlmHistoryParser.parse(tempDir);
        assertEquals(1, jobs.size());
        JobTimeline job = jobs.get(0);
        List<CallTimeline> calls = job.calls();
        assertEquals(1, calls.size());
        CallTimeline c = calls.get(0);

        // Even with missing sections, we shouldn't throw; fields may be null
        assertEquals("partial output only", c.completionRaw());
        // Reasoning is missing -> null
        assertNull(c.reasoningContent());
        // Tools missing -> empty list
        assertNotNull(c.tools());
        assertTrue(c.tools().isEmpty() || c.tools().size() == 0);
    }
}
