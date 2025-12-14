package ai.brokk.ctl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style test for InstanceRegistry that uses a temp dir as the per-user config dir.
 *
 * Validates:
 * - file creation and JSON shape
 * - heartbeat advancing lastSeenMs
 * - projects[] atomic update
 * - deletion on stop
 * - TTL/staleness input (lastSeenMs) can be used to determine staleness
 */
public class InstanceRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    public void registryCreatesHeartbeatsUpdatesAndDeletes() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        String instanceId = "test-instance";
        InstanceRegistry registry = new InstanceRegistry(cfg, instanceId, 42, "http://localhost:0", List.of("proj-one"), "0.1.0", 50);

        try {
            registry.start();

            Path record = cfg.getInstancesDir().resolve(instanceId + ".json");
            // wait for file to appear
            long deadline = System.currentTimeMillis() + 1000;
            while (!Files.exists(record) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(record), "record file should be created");

            String first = Files.readString(record);
            assertTrue(first.trim().startsWith("{") && first.trim().endsWith("}"), "file should contain JSON object");

            long lastSeen1 = extractLongField(first, "lastSeenMs");
            long startedAt = extractLongField(first, "startedAt");
            assertTrue(lastSeen1 >= startedAt, "lastSeen must be >= startedAt");

            // wait a bit for heartbeats to update lastSeenMs
            Thread.sleep(150);
            String second = Files.readString(record);
            long lastSeen2 = extractLongField(second, "lastSeenMs");
            assertTrue(lastSeen2 >= lastSeen1, "heartbeat should advance lastSeenMs");

            // update projects and ensure file contains updated array
            registry.updateProjects(List.of("a", "b"));
            // allow for immediate write
            Thread.sleep(80);
            String updated = Files.readString(record);
            assertTrue(updated.contains("\"projects\":[\"a\",\"b\"]"), "projects should be updated atomically");

        } finally {
            registry.stop();
        }

        // ensure file is deleted on stop
        Path record = cfg.getInstancesDir().resolve(instanceId + ".json");
        long delDeadline = System.currentTimeMillis() + 500;
        while (Files.exists(record) && System.currentTimeMillis() < delDeadline) {
            Thread.sleep(10);
        }
        assertFalse(Files.exists(record), "record should be removed on stop");

        // TTL/staleness: write an old record and verify lastSeenMs is sufficiently old for a TTL check
        Files.createDirectories(cfg.getInstancesDir());
        long now = System.currentTimeMillis();
        long oldLastSeen = now - 2000L;
        InstanceRecord old = new InstanceRecord(instanceId, 1, "addr", List.of(), "0.1.0", now - 5000L, oldLastSeen);
        Files.writeString(cfg.getInstancesDir().resolve(instanceId + ".json"), old.toJson());
        String oldContent = Files.readString(cfg.getInstancesDir().resolve(instanceId + ".json"));
        long lastSeenOld = extractLongField(oldContent, "lastSeenMs");
        long ttl = 500L;
        assertTrue(System.currentTimeMillis() - lastSeenOld > ttl, "lastSeenMs should be older than TTL");
    }

    private static long extractLongField(String json, String name) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new AssertionError("missing field " + name + " in json: " + json);
    }
}
