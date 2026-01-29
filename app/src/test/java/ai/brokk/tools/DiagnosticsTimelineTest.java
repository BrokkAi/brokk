package ai.brokk.tools;

import ai.brokk.tools.diagnostics.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal unit test verifying Jackson serialization produces the intended sessions -> jobs -> phases -> calls -> tools
 * tree and that nullable fields are serialized as JSON null values rather than causing failures.
 */
public class DiagnosticsTimelineTest {

    @Test
    void testSerializeMinimalTimeline() throws Exception {
        // Build a minimal tool call (some nullable fields intentionally left null)
        ToolCallTimeline tool =
                new ToolCallTimeline(
                        "echo",               // toolName
                        "CLI",                // toolType
                        "call-1",             // requestedByCallId
                        null,                 // startTime
                        null,                 // endTime
                        null,                 // durationMs
                        true,                 // success
                        null,                 // errorMessage
                        "input-summary",      // inputSummary
                        null);                // outputSummary

        // Build a call with null timestamps and null token counts to assert null serialization
        CallTimeline call =
                new CallTimeline(
                        "call-1",               // callId
                        "job-1",                // jobId
                        "session-1",            // sessionId
                        "phase-1",              // phaseId
                        null,                   // startTime
                        null,                   // endTime
                        null,                   // durationMs
                        null,                   // model
                        "assistant",            // modelRole
                        "hello",                // promptRaw
                        false,                  // promptTruncated
                        null,                   // promptTokenCount (nullable)
                        "world",                // completionRaw
                        false,                  // completionTruncated
                        null,                   // completionTokenCount (nullable)
                        null,                   // reasoningContent
                        null,                   // thoughtSignature
                        List.of(tool),          // tools
                        1,                      // attemptIndex
                        "COMPLETED");           // status

        // One phase that references the call id
        PhaseTimeline phase = new PhaseTimeline("phase-1", "PLANNING", "Planning Phase", null, null, null, List.of("call-1"), Map.of());

        // Job with some nullable fields
        JobTimeline job = new JobTimeline(
                "job-1",                       // jobId
                "ARCHITECT",                   // mode
                Map.of("mode", "ARCHITECT"),   // tags
                null,                          // startTime
                null,                          // endTime
                null,                          // durationMs
                null,                          // modelConfig
                null,                          // reasoningOverrides
                List.of(phase),                // phases
                List.of(call),                 // calls
                Map.of());                     // aggregates

        // Session containing the job
        SessionTimeline session = new SessionTimeline("session-1", "My Session", List.of(job));

        DiagnosticsTimeline timeline = new DiagnosticsTimeline(List.of(session));

        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(timeline);
        assertNotNull(json);

        JsonNode root = mapper.readTree(json);
        // Top-level sessions key present
        assertTrue(root.has("sessions"));
        JsonNode sessions = root.get("sessions");
        assertTrue(sessions.isArray());
        assertEquals(1, sessions.size());

        JsonNode sess = sessions.get(0);
        // Session fields
        assertEquals("session-1", sess.get("sessionId").asText());
        assertEquals("My Session", sess.get("label").asText());

        // Jobs array present
        assertTrue(sess.has("jobs"));
        JsonNode jobs = sess.get("jobs");
        assertTrue(jobs.isArray());
        assertEquals(1, jobs.size());

        JsonNode jobNode = jobs.get(0);
        assertEquals("job-1", jobNode.get("jobId").asText());
        assertTrue(jobNode.has("phases"));
        assertTrue(jobNode.has("calls"));

        // Phase present and references call id
        JsonNode phases = jobNode.get("phases");
        assertEquals(1, phases.size());
        JsonNode phaseNode = phases.get(0);
        assertEquals("phase-1", phaseNode.get("phaseId").asText());
        assertTrue(phaseNode.has("callIds"));
        JsonNode callIds = phaseNode.get("callIds");
        assertEquals(1, callIds.size());
        assertEquals("call-1", callIds.get(0).asText());

        // Calls present
        JsonNode calls = jobNode.get("calls");
        assertEquals(1, calls.size());
        JsonNode callNode = calls.get(0);
        assertEquals("call-1", callNode.get("callId").asText());
        // startTime was set to null -> should serialize as JSON null (field present and isNull)
        assertTrue(callNode.has("startTime"));
        assertTrue(callNode.get("startTime").isNull());

        // promptTokenCount is nullable and null -> present and isNull
        assertTrue(callNode.has("promptTokenCount"));
        assertTrue(callNode.get("promptTokenCount").isNull());

        // Tools present under the call
        assertTrue(callNode.has("tools"));
        JsonNode toolsNode = callNode.get("tools");
        assertEquals(1, toolsNode.size());
        JsonNode toolNode = toolsNode.get(0);
        assertEquals("echo", toolNode.get("toolName").asText());
        assertEquals("CLI", toolNode.get("toolType").asText());

        // tool startTime was null -> serialized as JSON null
        assertTrue(toolNode.has("startTime"));
        assertTrue(toolNode.get("startTime").isNull());
    }
}
