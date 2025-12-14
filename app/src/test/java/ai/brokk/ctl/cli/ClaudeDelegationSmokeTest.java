package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.ctl.CtlKeyManager;
import ai.brokk.ctl.InstanceRecord;
import ai.brokk.ctl.http.CtlHttpServer;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for a Claude Code delegation-style workflow.
 *
 * The test is hermetic: it creates a fake instance registry file in a temp
 * config dir, starts the in-process control HTTP server (for coverage of startup),
 * and drives the CLI-side command implementations. The "executor" and "planner"
 * are simulated by writing the expected execution/history entries into the
 * on-disk instance record.
 */
public class ClaudeDelegationSmokeTest {

    @Test
    public void smokeDelegationFlow(@TempDir Path tempDir) throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Files.createDirectories(cfg.getInstancesDir());

        long now = Instant.now().toEpochMilli();
        String instanceId = "claude-smoke-1";
        List<String> initialProjects = List.of();
        InstanceRecord rec = new InstanceRecord(
                instanceId,
                Integer.valueOf(4242),
                "127.0.0.1:0",
                initialProjects,
                "0.1.0-test",
                now,
                now
        );

        Path instFile = cfg.getInstancesDir().resolve(instanceId + ".json");
        Files.writeString(instFile, rec.toJson());

        // Ensure key exists and start control HTTP server (ephemeral port)
        CtlKeyManager keyManager = new CtlKeyManager(cfg);
        String key = keyManager.loadOrCreateKey();
        assertNotNull(key);
        assertFalse(key.isBlank());

        CtlHttpServer server = new CtlHttpServer(rec, keyManager, "127.0.0.1", 0);
        try {
            server.start();
            int port = server.getPort();
            assertTrue(port > 0, "server bound to ephemeral port");

            // DISCOVER: use InstancesList to enumerate instances
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(outBuf);
            PrintStream err = new PrintStream(errBuf);

            int rc = InstancesList.execute(cfg, 10_000L, false, null, false, "req-1", out, err);
            assertEquals(0, rc, "instances list should succeed");
            String discoveredJson = outBuf.toString();
            Map<String, Object> envelope = Json.fromJson(discoveredJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Object> instances = (List<Object>) envelope.get("instances");
            assertNotNull(instances);
            assertEquals(1, instances.size());
            @SuppressWarnings("unchecked")
            Map<String,Object> inst0 = (Map<String,Object>) instances.get(0);
            assertEquals(instanceId, inst0.get("instanceId"));

            // OPEN PROJECT: simulate opening a project by updating the registry file's projects[]
            String projectPath = "/path/to/my/project";
            Map<String,Object> registry = Json.fromJson(Files.readString(instFile), new TypeReference<>() {});
            registry.put("projects", List.of(projectPath));
            registry.put("lastSeenMs", System.currentTimeMillis());
            Files.writeString(instFile, Json.toJson(registry));

            // CREATE / SWITCH SESSION: simulate by adding a 'sessions' array (clients may ignore it,
            // but we add it to exercise mutation and future consumers)
            @SuppressWarnings("unchecked")
            Map<String,Object> regAfterProj = Json.fromJson(Files.readString(instFile), new TypeReference<>() {});
            List<Map<String,Object>> sessions = new ArrayList<>();
            Map<String,Object> s1 = new LinkedHashMap<>();
            s1.put("sessionId", "sess-1");
            s1.put("name", "Smoke Session");
            s1.put("createdAtMs", System.currentTimeMillis());
            sessions.add(s1);
            regAfterProj.put("sessions", sessions);
            regAfterProj.put("lastSeenMs", System.currentTimeMillis());
            Files.writeString(instFile, Json.toJson(regAfterProj));

            // START PLAN: invoke exec start which will return a jobId (the command doesn't persist it)
            outBuf.reset();
            errBuf.reset();
            int rcStart = CtlCommands.executeExecStart(cfg, 10_000L, false, instanceId, false, "req-2", "plan", projectPath, out, err);
            assertEquals(0, rcStart, "exec start should return 0");
            String startJson = outBuf.toString();
            Map<String,Object> startEnvelope = Json.fromJson(startJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> startResults = (List<Map<String,Object>>) startEnvelope.get("results");
            assertNotNull(startResults);
            assertFalse(startResults.isEmpty());
            Map<String,Object> first = startResults.get(0);
            assertEquals(instanceId, first.get("instanceId"));
            assertEquals("started", first.get("status"));
            String jobId = safeCastString(first.get("jobId"));
            assertNotNull(jobId);
            assertFalse(jobId.isBlank());

            // Simulate the instance now having a running execution and producing history events.
            Map<String,Object> current = Json.fromJson(Files.readString(instFile), new TypeReference<>() {});
            List<Map<String,Object>> executions = new ArrayList<>();
            Map<String,Object> execEntry = new LinkedHashMap<>();
            execEntry.put("jobId", jobId);
            execEntry.put("project", projectPath);
            execEntry.put("state", "running");
            execEntry.put("mode", "plan");
            execEntry.put("cancellable", true);
            execEntry.put("startedAtMs", System.currentTimeMillis());
            executions.add(execEntry);
            current.put("executions", executions);

            List<Map<String,Object>> history = new ArrayList<>();
            Map<String,Object> ev1 = new LinkedHashMap<>();
            ev1.put("sequence", 1L);
            ev1.put("createdAtMs", System.currentTimeMillis());
            ev1.put("type", "PLAN_STEP");
            ev1.put("text", "Planning started");
            ev1.put("payload", Map.of("phase", "start"));
            history.add(ev1);

            Map<String,Object> ev2 = new LinkedHashMap<>();
            ev2.put("sequence", 2L);
            ev2.put("createdAtMs", System.currentTimeMillis());
            ev2.put("type", "PLAN_STEP");
            ev2.put("text", "Planning completed");
            ev2.put("payload", Map.of("phase", "done"));
            history.add(ev2);

            current.put("history", history);
            current.put("lastSeenMs", System.currentTimeMillis());
            Files.writeString(instFile, Json.toJson(current));

            // POLL STATE GET: should report running
            outBuf.reset();
            errBuf.reset();
            int rcState = CtlCommands.executeStateGet(cfg, 10_000L, false, instanceId, false, "req-3", projectPath, out, err);
            assertEquals(0, rcState, "state get should return 0");
            String stateJson = outBuf.toString();
            Map<String,Object> stateEnvelope = Json.fromJson(stateJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> stateResults = (List<Map<String,Object>>) stateEnvelope.get("results");
            assertNotNull(stateResults);
            assertEquals(1, stateResults.size());
            Map<String,Object> st0 = stateResults.get(0);
            assertEquals(instanceId, st0.get("instanceId"));
            assertEquals("ok", st0.get("status"));
            @SuppressWarnings("unchecked")
            Map<String,Object> executionState = (Map<String,Object>) st0.get("executionState");
            assertNotNull(executionState);
            assertEquals("running", executionState.get("state"));
            assertEquals(jobId, executionState.get("jobId"));

            // FETCH HISTORY GET: should return our two events
            outBuf.reset();
            errBuf.reset();
            int rcHist = CtlCommands.executeHistoryGet(cfg, 10_000L, false, instanceId, false, "req-4", null, 10, false, projectPath, out, err);
            assertEquals(0, rcHist, "history get should return 0");
            String histJson = outBuf.toString();
            Map<String,Object> histEnvelope = Json.fromJson(histJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> events = (List<Map<String,Object>>) histEnvelope.get("events");
            assertNotNull(events);
            assertEquals(2, events.size());
            assertEquals(1L, ((Number) events.get(0).get("sequence")).longValue());
            assertEquals(2L, ((Number) events.get(1).get("sequence")).longValue());

        } finally {
            server.stop(0);
        }
    }

    private static String safeCastString(Object o) {
        return o == null ? null : o.toString();
    }
}
