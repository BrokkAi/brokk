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
 * Tests for instances list CLI.
 */
public class InstancesListTest {
    @TempDir
    Path tempDir;

    @Test
    public void excludesStaleByDefaultAndIncludesWhenAllFlag() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();

        // fresh
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        // stale
        InstanceRecord b = new InstanceRecord("id-b", 11, "addr-b", List.of("/proj/b"), "0.1.0", now - 5000L, now - 3000L);
        // fresh
        InstanceRecord c = new InstanceRecord("id-c", null, "addr-c", List.of(), "0.1.0", now - 1000L, now - 100L);

        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());
        java.nio.file.Files.writeString(instancesDir.resolve("id-b.json"), b.toJson());
        java.nio.file.Files.writeString(instancesDir.resolve("id-c.json"), c.toJson());

        // default TTL 1000ms -> stale b excluded
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"instances", "list", "--ttl", "1000", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(0, rc);
        String out = bout.toString().trim();
        assertFalse(out.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = Json.fromJson(out, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertNotNull(envelope.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) envelope.get("summary");
        assertEquals(3, ((Number) summary.get("totalInstances")).intValue());
        assertEquals(2, ((Number) summary.get("returnedInstances")).intValue());
        assertEquals(1, ((Number) summary.get("staleInstances")).intValue());
        assertEquals(1000, ((Number) summary.get("ttlMs")).intValue());

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> instances = (java.util.List<Map<String, Object>>) envelope.get("instances");
        assertNotNull(instances);
        for (Map<String, Object> inst : instances) {
            assertNotEquals("id-b", inst.get("instanceId"));
            assertTrue(inst.containsKey("stale"));
            assertEquals(false, inst.get("stale"));
            assertEquals("ok", inst.get("status"));
        }

        // Now include --all and expect id-b present and marked stale
        ByteArrayOutputStream bout2 = new ByteArrayOutputStream();
        int rc2 = BrokkCtlMain.run(new String[] {"instances", "list", "--all", "--ttl", "1000", "--config-dir", tempDir.toString()}, new PrintStream(bout2), System.err);
        assertEquals(0, rc2);
        String out2 = bout2.toString().trim();
        assertFalse(out2.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> env2 = Json.fromJson(out2, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> allInstances = (java.util.List<Map<String, Object>>) env2.get("instances");
        assertEquals(3, allInstances.size(), "Expected all instances when --all specified");

        boolean foundB = false;
        for (Map<String, Object> inst : allInstances) {
            if ("id-b".equals(inst.get("instanceId"))) {
                foundB = true;
                assertEquals(true, inst.get("stale"));
                assertEquals("stale", inst.get("status"));
            }
        }
        assertTrue(foundB, "stale instance id-b should be present with --all");
    }

    @Test
    public void selectionAndAutoSelectBehaviors() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();

        // fresh
        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        // stale
        InstanceRecord b = new InstanceRecord("id-b", 11, "addr-b", List.of("/proj/b"), "0.1.0", now - 5000L, now - 3000L);
        // fresh
        InstanceRecord c = new InstanceRecord("id-c", null, "addr-c", List.of(), "0.1.0", now - 1000L, now - 100L);

        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());
        java.nio.file.Files.writeString(instancesDir.resolve("id-b.json"), b.toJson());
        java.nio.file.Files.writeString(instancesDir.resolve("id-c.json"), c.toJson());

        // explicit selector should return only id-c
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"instances", "list", "--instance", "id-c", "--ttl", "1000", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);
        assertEquals(0, rc);
        String out = bout.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = Json.fromJson(out, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> instances = (java.util.List<Map<String, Object>>) envelope.get("instances");
        assertEquals(1, instances.size());
        assertEquals("id-c", instances.get(0).get("instanceId"));

        // auto-select should succeed when exactly one non-stale candidate exists
        // make id-a stale so only id-c remains non-stale
        InstanceRecord aStale = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 10000L, now - 9000L);
        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), aStale.toJson());

        ByteArrayOutputStream bout2 = new ByteArrayOutputStream();
        int rc2 = BrokkCtlMain.run(new String[] {"instances", "list", "--auto-select", "--ttl", "1000", "--config-dir", tempDir.toString()}, new PrintStream(bout2), System.err);
        assertEquals(0, rc2);
        String out2 = bout2.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> env2 = Json.fromJson(out2, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> instances2 = (java.util.List<Map<String, Object>>) env2.get("instances");
        assertEquals(1, instances2.size());
        assertEquals("id-c", instances2.get(0).get("instanceId"));
    }

    @Test
    public void mixedResultAggregationProducesPartialExit() throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instancesDir = cfg.ensureInstancesDirExists();

        long now = System.currentTimeMillis();

        InstanceRecord a = new InstanceRecord("id-a", 10, "addr-a", List.of("/proj/a"), "0.1.0", now - 500L, now - 200L);
        InstanceRecord b = new InstanceRecord("id-b", 11, "addr-b", List.of("/proj/b"), "0.1.0", now - 500L, now - 200L);
        InstanceRecord c = new InstanceRecord("id-c", null, "addr-c", List.of(), "0.1.0", now - 500L, now - 200L);

        java.nio.file.Files.writeString(instancesDir.resolve("id-a.json"), a.toJson());
        java.nio.file.Files.writeString(instancesDir.resolve("id-b.json"), "<<< invalid json >>>");
        java.nio.file.Files.writeString(instancesDir.resolve("id-c.json"), c.toJson());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int rc = BrokkCtlMain.run(new String[] {"instances", "list", "--all", "--ttl", "1000", "--config-dir", tempDir.toString()}, new PrintStream(bout), System.err);

        // one file failed to parse -> partial success
        assertEquals(3, rc);

        String out = bout.toString().trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = Json.fromJson(out, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) envelope.get("summary");
        assertEquals(3, ((Number) summary.get("totalInstances")).intValue());
        assertEquals(2, ((Number) summary.get("returnedInstances")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> skipped = (Map<String, Object>) envelope.get("skippedFiles");
        assertNotNull(skipped, "Expected skippedFiles details when some files fail");
        @SuppressWarnings("unchecked")
        java.util.List<String> parseFails = (java.util.List<String>) skipped.get("parseFailures");
        assertEquals(1, parseFails.size());
        assertTrue(parseFails.get(0).contains("id-b.json"));
    }
}
