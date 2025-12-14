package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.util.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Minimal command implementations for Phase 1 project/session commands.
 *
 * These implementations perform:
 *  - argument validation
 *  - instance selection (using same rules as InstancesList)
 *  - emit a canonical per-instance result container
 *
 * The actual features are not yet implemented; they return PROTOCOL_UNSUPPORTED_FEATURE
 * for feature-specific operations.
 */
public final class CtlCommands {
    private CtlCommands() {}

    public static int executeProjectOpen(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            String requestId,
            String path,
            PrintStream out,
            PrintStream err) {

        if (path == null || path.isBlank()) {
            err.println("--path is required for projects open");
            return 2;
        }

        List<Map<String, Object>> targets;
        try {
            targets = selectTargetInstances(cfg, ttlMs, includeAll, instanceSelector, autoSelect, err);
            if (targets == null) return 2; // error already printed
        } catch (IOException e) {
            err.println("Failed to read instances: " + e.getMessage());
            return 4;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> inst : targets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("instanceId", inst.getOrDefault("instanceId", ""));
            r.put("status", "unsupported");
            Map<String, Object> errObj = new LinkedHashMap<>();
            errObj.put("code", "PROTOCOL_UNSUPPORTED_FEATURE");
            errObj.put("message", "projects open is not implemented in this build");
            r.put("error", errObj);
            results.add(r);
        }
        envelope.put("results", results);
        envelope.put("summary", Map.of("requestedInstances", targets.size()));
        out.println(Json.toJson(envelope));
        return 0;
    }

    public static int executeSessionActive(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            String requestId,
            PrintStream out,
            PrintStream err) {

        List<Map<String, Object>> targets;
        try {
            targets = selectTargetInstances(cfg, ttlMs, includeAll, instanceSelector, autoSelect, err);
            if (targets == null) return 2;
        } catch (IOException e) {
            err.println("Failed to read instances: " + e.getMessage());
            return 4;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> inst : targets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("instanceId", inst.getOrDefault("instanceId", ""));
            r.put("status", "unsupported");
            Map<String, Object> errObj = new LinkedHashMap<>();
            errObj.put("code", "PROTOCOL_UNSUPPORTED_FEATURE");
            errObj.put("message", "sessions active is not implemented in this build");
            r.put("error", errObj);
            results.add(r);
        }
        envelope.put("results", results);
        envelope.put("summary", Map.of("requestedInstances", targets.size()));
        out.println(Json.toJson(envelope));
        return 0;
    }

    public static int executeSessionCreate(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            String requestId,
            String name,
            PrintStream out,
            PrintStream err) {

        if (name == null || name.isBlank()) {
            err.println("--name is required for sessions create");
            return 2;
        }

        List<Map<String, Object>> targets;
        try {
            targets = selectTargetInstances(cfg, ttlMs, includeAll, instanceSelector, autoSelect, err);
            if (targets == null) return 2;
        } catch (IOException e) {
            err.println("Failed to read instances: " + e.getMessage());
            return 4;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> inst : targets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("instanceId", inst.getOrDefault("instanceId", ""));
            r.put("status", "unsupported");
            Map<String, Object> errObj = new LinkedHashMap<>();
            errObj.put("code", "PROTOCOL_UNSUPPORTED_FEATURE");
            errObj.put("message", "sessions create is not implemented in this build");
            r.put("error", errObj);
            results.add(r);
        }
        envelope.put("results", results);
        envelope.put("summary", Map.of("requestedInstances", targets.size()));
        out.println(Json.toJson(envelope));
        return 0;
    }

    public static int executeSessionSwitch(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            String requestId,
            String sessionId,
            PrintStream out,
            PrintStream err) {

        if (sessionId == null || sessionId.isBlank()) {
            err.println("--session is required for sessions switch");
            return 2;
        }

        List<Map<String, Object>> targets;
        try {
            targets = selectTargetInstances(cfg, ttlMs, includeAll, instanceSelector, autoSelect, err);
            if (targets == null) return 2;
        } catch (IOException e) {
            err.println("Failed to read instances: " + e.getMessage());
            return 4;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> inst : targets) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("instanceId", inst.getOrDefault("instanceId", ""));
            r.put("status", "unsupported");
            Map<String, Object> errObj = new LinkedHashMap<>();
            errObj.put("code", "PROTOCOL_UNSUPPORTED_FEATURE");
            errObj.put("message", "sessions switch is not implemented in this build");
            r.put("error", errObj);
            results.add(r);
        }
        envelope.put("results", results);
        envelope.put("summary", Map.of("requestedInstances", targets.size()));
        out.println(Json.toJson(envelope));
        return 0;
    }

    public static int executeExecStart(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            String requestId,
            String mode,
            String projectPath,
            PrintStream out,
            PrintStream err) {

        if (mode == null || mode.isBlank()) {
            err.println("--mode is required for exec start");
            return 2;
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!"plan".equals(m) && !"lutz".equals(m)) {
            err.println("Unsupported mode: " + mode + ". Supported: plan, lutz");
            return 2;
        }

        if (projectPath == null || projectPath.isBlank()) {
            err.println("--path is required for exec start");
            return 2;
        }

        List<Map<String, Object>> targets;
        try {
            targets = selectTargetInstances(cfg, ttlMs, includeAll, instanceSelector, autoSelect, err);
            if (targets == null) return 2;
        } catch (IOException e) {
            err.println("Failed to read instances: " + e.getMessage());
            return 4;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> inst : targets) {
            String instanceId = (String) inst.getOrDefault("instanceId", "");
            boolean busy = false;
            String existingJobId = "";

            Object execsObj = inst.get("executions");
            if (execsObj instanceof List) {
                for (Object eo : (List<?>) execsObj) {
                    if (eo instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> em = (Map<String, Object>) eo;
                        String proj = em.getOrDefault("project", "").toString();
                        String state = em.getOrDefault("state", "").toString().toLowerCase(Locale.ROOT);
                        if (proj.equals(projectPath) && ("running".equals(state) || "starting".equals(state))) {
                            busy = true;
                            existingJobId = em.getOrDefault("jobId", "").toString();
                            break;
                        }
                    }
                }
            }

            if (busy) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("instanceId", instanceId);
                r.put("status", "busy");
                Map<String, Object> errObj = new LinkedHashMap<>();
                errObj.put("code", "BUSY");
                errObj.put("message", "An execution is already running for project: " + projectPath);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("existingJobId", existingJobId);
                errObj.put("details", details);
                r.put("error", errObj);
                results.add(r);
            } else {
                String jobId = java.util.UUID.randomUUID().toString();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("instanceId", instanceId);
                r.put("status", "started");
                r.put("jobId", jobId);
                r.put("cursor", jobId + ":0");
                r.put("mode", m);
                r.put("project", projectPath);
                results.add(r);
            }
        }

        envelope.put("results", results);
        envelope.put("summary", Map.of("requestedInstances", targets.size()));
        out.println(Json.toJson(envelope));
        return 0;
    }

    /**
     * Read instance registry files and apply selection rules.
     *
     * Returns null (and prints to err) when the selection could not be resolved due to user error.
     */
    private static List<Map<String, Object>> selectTargetInstances(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String instanceSelector,
            boolean autoSelect,
            PrintStream err) throws IOException {

        Path instancesDir = cfg.getInstancesDir();
        List<Path> files;
        if (!Files.exists(instancesDir)) {
            Files.createDirectories(instancesDir);
        }
        try (var s = Files.list(instancesDir)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .collect(Collectors.toList());
        }

        long now = System.currentTimeMillis();
        List<Map<String, Object>> parsedInstances = new ArrayList<>();

        List<String> parseFailures = new ArrayList<>();
        List<String> readFailures = new ArrayList<>();

        for (Path p : files) {
            String content;
            try {
                content = Files.readString(p);
            } catch (IOException e) {
                readFailures.add(p.getFileName().toString());
                continue;
            }

            Map<String, Object> data;
            try {
                data = Json.fromJson(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                parseFailures.add(p.getFileName().toString());
                continue;
            }

            String instanceId = safeCastString(data.get("instanceId"));
            long lastSeenMs = safeCastLong(data.get("lastSeenMs"), 0L);
            boolean stale = (now - lastSeenMs) > ttlMs;

            Map<String, Object> instOut = new LinkedHashMap<>();
            instOut.put("instanceId", instanceId == null ? "" : instanceId);
            instOut.put("stale", stale);
            instOut.putAll(data); // include other fields for potential consumer
            parsedInstances.add(instOut);
        }

        // sort by instanceId
        parsedInstances.sort(Comparator.comparing(m -> (String) m.getOrDefault("instanceId", "")));

        // filter by stale unless includeAll
        List<Map<String, Object>> nonStale = parsedInstances.stream()
                .filter(m -> Boolean.FALSE.equals(m.get("stale")))
                .collect(Collectors.toList());

        List<Map<String, Object>> candidates = includeAll ? parsedInstances : nonStale;

        // parse selector set
        final Set<String> selectorSet;
        if (instanceSelector != null && !instanceSelector.isBlank()) {
            selectorSet = Arrays.stream(instanceSelector.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } else {
            selectorSet = null;
        }

        final List<Map<String, Object>> toReturn;
        if (selectorSet != null) {
            toReturn = candidates.stream()
                    .filter(m -> selectorSet.contains((String) m.getOrDefault("instanceId", "")))
                    .collect(Collectors.toList());
            if (toReturn.isEmpty()) {
                err.println("No instances matched selector: " + instanceSelector);
                return null;
            }
        } else if (autoSelect) {
            if (candidates.size() == 1) {
                toReturn = List.of(candidates.get(0));
            } else {
                err.println("Auto-select ambiguous: found " + candidates.size() + " candidate instances");
                return null;
            }
        } else {
            // default: require explicit selection for stateful commands (Phase1 expects a chosen instance)
            if (candidates.size() == 1) {
                toReturn = List.of(candidates.get(0));
            } else {
                err.println("Ambiguous instance selection: specify --instance or use --auto-select when exactly one candidate exists");
                return null;
            }
        }

        return toReturn;
    }

    private static String safeCastString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Number asNumber(Object o) {
        if (o instanceof Number) return (Number) o;
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static long safeCastLong(Object o, long def) {
        Number n = asNumber(o);
        return n == null ? def : n.longValue();
    }
}
