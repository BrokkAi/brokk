package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.ctl.InstanceRecord;
import ai.brokk.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for minimal project/session CLI commands.
 */
public class CtlCommandsTest {
    @TempDir
    Path tempDir;

    @Test
    public void projectsOpen_requiresPathArgument() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"projects", "open", "--instance", "id-a", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(2, rc, "Missing --path should result in user error");
    }

    @Test
    public void projectsOpen_returnsUnsupportedFeatureEnvelope() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"projects", "open", "--path", "/proj/a", "--instance", "id-a", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(0, rc);

        String out = bout.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> env = Json.fromJson(out, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertNotNull(env.get("results"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> results = (java.util.List<Map<String, Object>>) env.get("results");
        assertEquals(1, results.size());
        Map<String, Object> r = results.get(0);
        assertEquals("id-a", r.get("instanceId"));
        assertEquals("unsupported", r.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errObj = (Map<String, Object>) r.get("error");
        assertEquals("PROTOCOL_UNSUPPORTED_FEATURE", errObj.get("code"));
    }

    @Test
    public void sessionsSwitch_requiresSessionArg() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"sessions", "switch", "--instance", "id-a", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(2, rc, "Missing --session should be a user error");
    }

    @Test
    public void sessionsActive_returnsUnsupportedFeatureEnvelope() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"sessions", "active", "--instance", "id-a", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(0, rc);

        String out = bout.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> env = Json.fromJson(out, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertNotNull(env.get("results"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> results = (java.util.List<Map<String, Object>>) env.get("results");
        assertEquals(1, results.size());
        Map<String, Object> r = results.get(0);
        assertEquals("id-a", r.get("instanceId"));
        assertEquals("unsupported", r.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errObj = (Map<String, Object>) r.get("error");
        assertEquals("PROTOCOL_UNSUPPORTED_FEATURE", errObj.get("code"));
    }
}
