package ai.brokk.tools;

import ai.brokk.tools.diagnostics.CallTimeline;
import ai.brokk.tools.diagnostics.DiagnosticsAggregator;
import ai.brokk.tools.diagnostics.DiagnosticsTimeline;
import ai.brokk.tools.diagnostics.JobTimeline;
import ai.brokk.tools.diagnostics.LlmHistoryParser;
import ai.brokk.tools.diagnostics.JobDiagnosticsLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CLI entrypoint that builds a DiagnosticsTimeline from:
 *  - a project root's .brokk/llm-history (parsed by LlmHistoryParser)
 *  - a headless JobStore root (parsed by JobDiagnosticsLoader)
 *  - optional brokk log directory (used by JobDiagnosticsLoader)
 *
 * Usage:
 *   --project-root PATH    (required)
 *   --jobstore-root PATH   (required)
 *   --log-dir PATH         (optional, defaults to $HOME/.brokk)
 *   --output PATH          (optional, defaults to ./diagnostics-timeline.json, or stdout when "-")
 */
public final class DiagnosticsTimelineCli {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DiagnosticsTimelineCli() {}

    public static void main(String[] args) {
        int rc = runCli(args);
        System.exit(rc);
    }

    /**
     * Testable entrypoint that returns an exit code and does not call System.exit.
     */
    public static int runCli(String[] args) {
        try {
            var opts = parseArgs(args);
            if (opts == null) {
                printUsage(System.out);
                return 0;
            }

            Path projectRoot = Paths.get(opts.get("project-root"));
            Path jobstoreRoot = Paths.get(opts.get("jobstore-root"));
            @Nullable Path logDir = null;
            if (opts.containsKey("log-dir")) {
                logDir = Paths.get(opts.get("log-dir"));
            }

            String output = opts.getOrDefault("output", "diagnostics-timeline.json");

            // Parse LLM history (calls)
            List<CallTimeline> calls = List.of();
            try {
                List<JobTimeline> parsedJobs = LlmHistoryParser.parse(projectRoot);
                // flatten calls
                calls = parsedJobs.stream()
                        .filter(Objects::nonNull)
                        .flatMap(j -> j.calls() == null ? StreamEmpty() : j.calls().stream())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                System.err.println("WARN: Failed to parse LLM history from " + projectRoot + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }

            // Load JobStore jobs
            List<JobTimeline> jobs = List.of();
            try {
                jobs = JobDiagnosticsLoader.load(jobstoreRoot, logDir);
            } catch (Exception e) {
                System.err.println("WARN: Failed to load JobStore from " + jobstoreRoot + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }

            // Aggregate into diagnostics timeline (always produce something)
            DiagnosticsTimeline timeline;
            try {
                timeline = DiagnosticsAggregator.aggregate(calls, jobs);
            } catch (Exception e) {
                System.err.println("ERROR: Failed to aggregate diagnostics: " + e.getMessage());
                e.printStackTrace(System.err);
                // Fallback to best-effort: create a bare timeline with whatever we have
                timeline = new DiagnosticsTimeline(List.of());
            }

            // Serialize to requested output
            try {
                if ("-".equals(output)) {
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(System.out, timeline);
                    System.out.flush();
                } else {
                    Path outPath = Paths.get(output).toAbsolutePath();
                    Files.createDirectories(outPath.getParent() == null ? Path.of(".") : outPath.getParent());
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), timeline);
                    System.out.println("Wrote diagnostics timeline to: " + outPath);
                }
            } catch (IOException e) {
                System.err.println("ERROR: Failed to write output: " + e.getMessage());
                e.printStackTrace(System.err);
                return 2;
            }

            return 0;
        } catch (IllegalArgumentException iae) {
            System.err.println("ERROR: " + iae.getMessage());
            printUsage(System.err);
            return 1;
        } catch (Exception e) {
            System.err.println("FATAL: " + e.getMessage());
            e.printStackTrace(System.err);
            return 3;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        if (args == null) return null;
        var map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + a);
            }
            String key = a.substring(2);
            String val = null;
            if (key.contains("=")) {
                var parts = key.split("=", 2);
                key = parts[0];
                val = parts.length > 1 ? parts[1] : "";
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Option --" + key + " requires a value");
                }
                val = args[++i];
            }
            map.put(key, val);
        }

        // Allow env vars fallback
        if (!map.containsKey("project-root")) {
            String env = System.getenv("BROKK_PROJECT_ROOT");
            if (env != null && !env.isBlank()) map.put("project-root", env);
        }
        if (!map.containsKey("jobstore-root")) {
            String env = System.getenv("BROKK_JOBSTORE_ROOT");
            if (env != null && !env.isBlank()) map.put("jobstore-root", env);
        }
        if (!map.containsKey("log-dir")) {
            String env = System.getenv("BROKK_LOG_DIR");
            if (env != null && !env.isBlank()) map.put("log-dir", env);
        }

        // Validate required
        if (!map.containsKey("project-root") || map.get("project-root").isBlank()) {
            throw new IllegalArgumentException("--project-root is required");
        }
        if (!map.containsKey("jobstore-root") || map.get("jobstore-root").isBlank()) {
            throw new IllegalArgumentException("--jobstore-root is required");
        }

        // Set default log-dir to $HOME/.brokk when absent
        if (!map.containsKey("log-dir") || map.get("log-dir").isBlank()) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                map.put("log-dir", Paths.get(home, ".brokk").toString());
            }
        }

        // output default to ./diagnostics-timeline.json if not present
        if (!map.containsKey("output") || map.get("output").isBlank()) {
            map.put("output", "diagnostics-timeline.json");
        }

        return map;
    }

    private static void printUsage(PrintStream out) {
        out.println("DiagnosticsTimelineCli");
        out.println("  --project-root PATH    Path to project root containing .brokk/llm-history (required)");
        out.println("  --jobstore-root PATH   Path to headless JobStore root (contains jobs/ and idempotency/) (required)");
        out.println("  --log-dir PATH         Path to brokk log dir (default: $HOME/.brokk)");
        out.println("  --output PATH| -       Output file path (default: ./diagnostics-timeline.json). Use - for stdout.");
        out.println();
        out.println("Example:");
        out.println("  java ai.brokk.tools.DiagnosticsTimelineCli --project-root /path/to/repo --jobstore-root /var/lib/brokk/jobstore --output diagnostics.json");
    }

    // helper to provide an empty Stream when an object is null
    private static <T> java.util.stream.Stream<T> StreamEmpty() {
        return java.util.stream.Stream.empty();
    }
}
