package ai.brokk.tools.diagnostics;

import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Loads job-level diagnostics from a JobStore directory and optional Brokk debug logs.
 *
 * Produces a list of JobTimeline objects (one per job directory found under {storeRoot}/jobs).
 *
 * This loader is intentionally conservative and tolerant:
 * - Missing or malformed pieces do not throw; they result in null fields or fallbacks.
 * - debug.log parsing is best-effort and will not fail the overall load if lines don't match.
 */
public final class JobDiagnosticsLoader {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern JOB_DIR_NAME = Pattern.compile(".+"); // accept any directory name as job id

    private JobDiagnosticsLoader() {}

    /**
     * Load job timelines from an optional JobStore root and optional brokk log directory.
     *
     * @param jobStoreRoot Path to the JobStore root that contains a "jobs" directory. May be null to indicate no JobStore is configured.
     * @param brokkLogDir  Path to Brokk logs directory (may be null), used for best-effort enrichment.
     * @return list of JobTimeline, empty list if none found or on errors.
     */
    public static List<JobTimeline> load(@Nullable Path jobStoreRoot, @Nullable Path brokkLogDir) {
        if (jobStoreRoot == null) {
            return List.of();
        }
        Path jobsDir = jobStoreRoot.resolve("jobs");
        if (!Files.exists(jobsDir) || !Files.isDirectory(jobsDir)) {
            return List.of();
        }

        // read debug logs (best-effort) to permit later enrichment (not required for current coarse phases)
        List<String> debugLines = readDebugLogs(brokkLogDir);

        try {
            List<Path> jobDirs;
            try (var ds = Files.list(jobsDir)) {
                jobDirs = ds.filter(Files::isDirectory).collect(Collectors.toList());
            }

            var results = new ArrayList<JobTimeline>();
            for (Path jobDir : jobDirs) {
                try {
                    var jt = loadSingleJob(jobDir, debugLines);
                    if (jt != null) results.add(jt);
                } catch (Exception e) {
                    // tolerate per-job failures
                }
            }
            return results;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> readDebugLogs(@Nullable Path brokkLogDir) {
        if (brokkLogDir == null) {
            var home = System.getProperty("user.home");
            if (home == null) return List.of();
            brokkLogDir = Paths.get(home, ".brokk");
        }
        if (!Files.exists(brokkLogDir) || !Files.isDirectory(brokkLogDir)) {
            return List.of();
        }

        try {
            // match debug.log and rotated variants debug.log*
            try (var stream = Files.list(brokkLogDir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith("debug.log"))
                        .sorted()
                        .flatMap(p -> {
                            try {
                                return Files.readAllLines(p, StandardCharsets.UTF_8).stream();
                            } catch (IOException e) {
                                return StreamEmpty();
                            }
                        })
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    // helper to provide an empty Stream when an exception occurs (avoids adding new dependencies)
    private static java.util.stream.Stream<String> StreamEmpty() {
        return java.util.stream.Stream.empty();
    }

    @Nullable
    private static JobTimeline loadSingleJob(Path jobDir, List<String> debugLines) {
        String jobId = jobDir.getFileName().toString();

        // Load meta.json
        Path meta = jobDir.resolve("meta.json");
        if (!Files.exists(meta)) {
            return null;
        }

        JobSpec spec = null;
        try {
            spec = MAPPER.readValue(meta.toFile(), JobSpec.class);
        } catch (IOException e) {
            // tolerate and skip job
            return null;
        }

        // Load status.json (optional)
        JobStatus status = null;
        Path statusFile = jobDir.resolve("status.json");
        if (Files.exists(statusFile)) {
            try {
                status = MAPPER.readValue(statusFile.toFile(), JobStatus.class);
            } catch (IOException ignored) {
            }
        }

        // Load events.jsonl
        List<JobEvent> events = new ArrayList<>();
        Path eventsFile = jobDir.resolve("events.jsonl");
        if (Files.exists(eventsFile)) {
            try {
                var lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line == null || line.isBlank()) continue;
                    try {
                        JobEvent ev = MAPPER.readValue(line, JobEvent.class);
                        events.add(ev);
                    } catch (IOException ignored) {
                        // skip malformed event lines
                    }
                }
                events.sort(Comparator.comparingLong(JobEvent::seq));
            } catch (IOException ignored) {
            }
        }

        // Build phases from events (simple heuristic)
        List<PhaseTimeline> phases = buildPhasesFromEvents(jobId, events, status);

        // Fallback: if no explicit phases found, create a single UNKNOWN phase that spans status window if available
        if (phases.isEmpty()) {
            Long start = null;
            Long end = null;
            if (status != null) {
                start = status.startTime() > 0 ? status.startTime() : null;
                end = status.endTime() > 0 ? status.endTime() : null;
            }
            PhaseTimeline unknown = new PhaseTimeline(
                    jobId + "-unknown",
                    "UNKNOWN",
                    "Unknown Phase",
                    start,
                    end,
                    (start != null && end != null) ? (end - start) : null,
                    List.of(),
                    Map.of());
            phases = List.of(unknown);
        }

        // Mode: use JobRunner.parseMode (public)
        // JobRunner.Mode is not publicly accessible across packages. Avoid calling methods on the
        // returned enum value directly (which causes an inaccessible-type compile error).
        // Instead treat the result as Object and call toString() on that Object at runtime.
        String modeStr = null;
        try {
            Object modeObj = JobRunner.parseMode(spec);
            modeStr = modeObj == null ? null : modeObj.toString();
        } catch (Exception ignored) {
        }

        // Model selection: prefer plannerModel, then codeModel, then scanModel
        String modelName = null;
        try {
            if (spec.plannerModel() != null && !spec.plannerModel().isBlank()) modelName = spec.plannerModel();
            else if (spec.codeModel() != null && !spec.codeModel().isBlank()) modelName = spec.codeModel();
            else if (spec.scanModel() != null && !spec.scanModel().isBlank()) modelName = spec.scanModel();
        } catch (Exception ignored) {
        }

        JobTimeline.ModelConfig modelConfig =
                modelName == null ? null : new JobTimeline.ModelConfig(modelName, null, null);

        JobTimeline.ReasoningOverrides reasoning = new JobTimeline.ReasoningOverrides(
                spec.reasoningLevel(), spec.reasoningLevelCode(), spec.temperature(), spec.temperatureCode());

        Long jobStart = null;
        Long jobEnd = null;
        Long duration = null;
        if (status != null) {
            jobStart = status.startTime() > 0 ? status.startTime() : null;
            jobEnd = status.endTime() > 0 ? status.endTime() : null;
            if (jobStart != null && jobEnd != null) duration = jobEnd - jobStart;
        }

        Map<String, Object> aggregates = new HashMap<>();
        // include lastSeq if available in status metadata
        if (status != null && status.metadata() != null && status.metadata().containsKey("lastSeq")) {
            aggregates.put("lastSeq", status.metadata().get("lastSeq"));
        }

        JobTimeline jt = new JobTimeline(
                jobId,
                modeStr,
                spec.tags(),
                jobStart,
                jobEnd,
                duration,
                modelConfig,
                reasoning,
                phases,
                List.of(), // calls not yet attached
                aggregates.isEmpty() ? Map.of() : aggregates);

        return jt;
    }

    /**
     * Build coarse phases using events as anchors.
     *
     * Heuristics:
     * - NOTIFICATION messages mentioning "Brokk Context Engine" and "analyzing" -> SCAN start.
     * - NOTIFICATION messages mentioning "complete — contextual insights added to Workspace" -> SCAN end.
     * - NOTIFICATION messages mentioning "PR review" or "Fetching PR refs" -> REVIEW start.
     * - COMMAND_RESULT events with stage in {tests, lint, verification, review_fix, fix_trigger} -> EXECUTION anchors.
     *
     * The implementation creates simple PhaseTimeline entries by pairing start and end markers when possible.
     */
    private static List<PhaseTimeline> buildPhasesFromEvents(
            String jobId, List<JobEvent> events, @Nullable JobStatus status) {
        var builders = new ArrayList<PhaseBuilder>();
        var execBuilders = new LinkedHashMap<String, PhaseBuilder>(); // keyed by stage for execution grouping
        PhaseBuilder openScan = null;
        PhaseBuilder openReview = null;
        AtomicInteger idSeq = new AtomicInteger(1);

        for (JobEvent ev : events) {
            if (ev == null) continue;
            String type = ev.type();
            long ts = ev.timestamp();

            if ("NOTIFICATION".equalsIgnoreCase(type)) {
                // data may be String or object; handle common shapes
                Object data = ev.data();
                String msg = data == null ? "" : data.toString();

                String lower = msg.toLowerCase(Locale.ROOT);

                if (lower.contains("brokk context engine") && lower.contains("analyzing")) {
                    // open scan
                    if (openScan == null) {
                        openScan = new PhaseBuilder(jobId + "-scan-" + idSeq.getAndIncrement(), "SCAN", "Pre-scan", ts);
                    }
                    continue;
                }

                if (lower.contains("complete") && lower.contains("contextual insights added")) {
                    if (openScan != null) {
                        openScan.endTime = ts;
                        builders.add(openScan);
                        openScan = null;
                    } else {
                        // create a short scan phase anchored at this timestamp
                        var pb = new PhaseBuilder(jobId + "-scan-" + idSeq.getAndIncrement(), "SCAN", "Pre-scan", ts);
                        pb.endTime = ts;
                        builders.add(pb);
                    }
                    continue;
                }

                if (lower.contains("pr review")
                        || lower.contains("fetching pr refs")
                        || lower.contains("fetching pr refs from")) {
                    if (openReview == null) {
                        openReview = new PhaseBuilder(
                                jobId + "-review-" + idSeq.getAndIncrement(), "REVIEW", "PR Review", ts);
                    }
                    continue;
                }

                // some notifications explicitly mark review complete
                if (lower.contains("review complete")
                        || lower.contains("posted pr review")
                        || lower.contains("created pull request")) {
                    if (openReview != null) {
                        openReview.endTime = ts;
                        builders.add(openReview);
                        openReview = null;
                    } else {
                        var pb = new PhaseBuilder(
                                jobId + "-review-" + idSeq.getAndIncrement(), "REVIEW", "PR Review", ts);
                        pb.endTime = ts;
                        builders.add(pb);
                    }
                    continue;
                }

                // generic notifications that include "ISSUE_WRITER" or "ISSUE" hints
                if (msg.contains("ISSUE_WRITER")
                        || msg.toLowerCase().contains("issue_writer")
                        || lower.contains("issue")) {
                    // create an informational phase (best-effort)
                    var pb = new PhaseBuilder(jobId + "-issue-" + idSeq.getAndIncrement(), "ISSUE", "Issue Work", ts);
                    // do not auto-close; leave as single-point phase
                    pb.endTime = ts;
                    builders.add(pb);
                    continue;
                }
            } else if ("COMMAND_RESULT".equalsIgnoreCase(type)) {
                Object data = ev.data();
                String stage = null;
                try {
                    if (data instanceof Map<?, ?> map) {
                        Object s = map.get("stage");
                        if (s != null) stage = s.toString();
                    }
                } catch (Exception ignored) {
                }

                if (stage != null) {
                    String lowerStage = stage.toLowerCase(Locale.ROOT);
                    // stages that correspond to execution/verification
                    if (Set.of("tests", "lint", "verification", "review_fix", "fix_trigger")
                            .contains(lowerStage)) {
                        // group by stage name
                        PhaseBuilder pb = execBuilders.get(lowerStage);
                        if (pb == null) {
                            pb = new PhaseBuilder(jobId + "-exec-" + idSeq.getAndIncrement(), "EXECUTION", stage, ts);
                            execBuilders.put(lowerStage, pb);
                        }
                        // expand the end time to latest timestamp seen
                        pb.endTime = ts;
                    } else {
                        // other stage: add small anchored phase
                        var pb = new PhaseBuilder(
                                jobId + "-" + stage + "-" + idSeq.getAndIncrement(),
                                stage.toUpperCase(Locale.ROOT),
                                stage,
                                ts);
                        pb.endTime = ts;
                        builders.add(pb);
                    }
                }
            }
        }

        // finalize any open scan/review
        if (openScan != null) {
            builders.add(openScan);
        }
        if (openReview != null) {
            builders.add(openReview);
        }
        // add execution builders
        builders.addAll(execBuilders.values());

        // convert builders to PhaseTimeline
        var out = new ArrayList<PhaseTimeline>();
        for (PhaseBuilder b : builders) {
            Long dur = null;
            if (b.endTime != null) {
                dur = b.endTime - b.startTime;
            }
            PhaseTimeline pt =
                    new PhaseTimeline(b.phaseId, b.type, b.label, b.startTime, b.endTime, dur, List.of(), Map.of());
            out.add(pt);
        }

        // Sort phases by startTime when available
        out.sort(Comparator.comparing(p -> Optional.ofNullable(p.startTime()).orElse(Long.MAX_VALUE)));

        return out;
    }

    // small mutable helper used while building phases
    private static final class PhaseBuilder {
        final String phaseId;
        final String type;
        final String label;
        final long startTime;

        @Nullable
        Long endTime;

        PhaseBuilder(String phaseId, String type, String label, long startTime) {
            this.phaseId = phaseId;
            this.type = type;
            this.label = label;
            this.startTime = startTime;
            this.endTime = null;
        }
    }
}
