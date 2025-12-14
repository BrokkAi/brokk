package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.util.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements the `instances list` command.
 *
 * Behavior:
 *  - Reads *.json files from the instances directory (Cfg.getInstancesDir()).
 *  - totalInstances counts all .json files seen (even if parsing fails).
 *  - A file that cannot be parsed is skipped (but counted in totalInstances).
 *  - An instance is considered stale iff (now - lastSeenMs) > ttlMs.
 *  - If includeAll == false, stale instances are excluded from "instances" array.
 *  - If includeAll == true, stale instances are included and marked with "status":"stale".
 *
 * Output: single JSON envelope printed to the provided PrintStream.
 */
public final class InstancesList {
    private InstancesList() {}

    @SuppressWarnings("unchecked")
    public static int execute(
            CtlConfigPaths cfg,
            long ttlMs,
            boolean includeAll,
            String requestId,
            PrintStream out,
            PrintStream err) {

        long start = System.currentTimeMillis();

        Path instancesDir = cfg.getInstancesDir();
        List<Path> files;
        try {
            if (!Files.exists(instancesDir)) {
                Files.createDirectories(instancesDir);
            }
            try (var s = Files.list(instancesDir)) {
                files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            err.println("Failed to list instances directory: " + e.getMessage());
            return 2;
        }

        int totalInstances = files.size();
        List<Map<String, Object>> parsedInstances = new ArrayList<>();
        int staleCount = 0;
        long now = System.currentTimeMillis();

        for (Path p : files) {
            String content;
            try {
                content = Files.readString(p);
            } catch (IOException e) {
                // Cannot read, count in total but skip
                continue;
            }

            Map<String, Object> data;
            try {
                data = Json.fromJson(content, Map.class);
            } catch (Exception e) {
                // Parse failure: count in totalInstances but skip
                continue;
            }

            // Extract fields defensively
            String instanceId = safeCastString(data.get("instanceId"));
            Number pidNum = asNumber(data.get("pid"));
            Integer pid = pidNum == null ? null : pidNum.intValue();
            String listenAddr = safeCastString(data.get("listenAddr"));
            Object projectsObj = data.get("projects");
            List<String> projects;
            if (projectsObj instanceof List) {
                projects = ((List<?>) projectsObj).stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.toList());
            } else {
                projects = List.of();
            }
            String brokkctlVersion = safeCastString(data.get("brokkctlVersion"));
            long startedAt = safeCastLong(data.get("startedAt"), 0L);
            long lastSeenMs = safeCastLong(data.get("lastSeenMs"), 0L);

            boolean stale = (now - lastSeenMs) > ttlMs;
            if (stale) staleCount++;

            Map<String, Object> instOut = new LinkedHashMap<>();
            instOut.put("instanceId", instanceId == null ? "" : instanceId);
            instOut.put("pid", pid);
            instOut.put("listenAddr", listenAddr == null ? "" : listenAddr);
            instOut.put("projects", projects);
            instOut.put("brokkctlVersion", brokkctlVersion == null ? "" : brokkctlVersion);
            instOut.put("startedAt", startedAt);
            instOut.put("lastSeenMs", lastSeenMs);
            instOut.put("stale", stale);
            instOut.put("status", stale ? "stale" : "ok");

            parsedInstances.add(instOut);
        }

        // Sort deterministically by instanceId ascending
        parsedInstances.sort(Comparator.comparing(m -> (String) m.getOrDefault("instanceId", "")));

        List<Map<String, Object>> toReturn;
        if (includeAll) {
            toReturn = parsedInstances;
        } else {
            toReturn = parsedInstances.stream()
                    .filter(m -> Boolean.FALSE.equals(m.get("stale")))
                    .collect(Collectors.toList());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalInstances", totalInstances);
        summary.put("returnedInstances", toReturn.size());
        summary.put("staleInstances", staleCount);
        summary.put("ttlMs", ttlMs);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId == null ? "(auto)" : requestId);
        envelope.put("summary", summary);
        envelope.put("instances", toReturn);
        envelope.put("elapsedMs", System.currentTimeMillis() - start);

        out.println(Json.toJson(envelope));
        return 0;
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
