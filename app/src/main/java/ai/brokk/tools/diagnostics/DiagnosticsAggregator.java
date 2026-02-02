package ai.brokk.tools.diagnostics;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates CallTimeline objects (from LLM history) with JobTimeline/PhaseTimeline objects
 * (from JobStore/events) into a DiagnosticsTimeline suitable for serialization and inspection.
 *
 * Heuristics:
 * - Prefer explicit call.jobId when present and matching a known jobId.
 * - Otherwise, match calls to jobs by timestamp falling within job.startTime..job.endTime.
 *   If multiple jobs match, the call is left unassigned and routed to the "unknown" job grouping.
 * - Within a job, prefer explicit phases (phase start/end) to assign calls. If phases are absent,
 *   fall back to lightweight heuristics based on job.mode and model name:
 *     * ASK: calls whose model name equals job.modelConfig.name -> PLANNING; others -> SCAN
 *     * Otherwise: calls that do not match job.modelConfig are grouped into "OTHER"
 *
 * Metrics:
 * - Call durationMs is ensured when start/end are present.
 * - Phase metrics: totalCalls, totalDurationMs, avgReasoningDepth (avg tool count).
 * - Job aggregates sum phase metrics and compute overall avgReasoningDepth across all calls.
 */
public final class DiagnosticsAggregator {

    private static final String UNKNOWN_JOB_ID = "unknown";
    private static final String UNKNOWN_JOB_MODE = "UNKNOWN";

    private DiagnosticsAggregator() {}

    /**
     * Aggregate calls and jobs into a DiagnosticsTimeline. Creates a single synthetic session
     * containing all jobs (including an "unknown" job for unassigned calls).
     *
     * @param calls list of CallTimeline parsed from LLM history
     * @param jobs  list of JobTimeline parsed from JobStore/events
     * @return DiagnosticsTimeline containing aggregated jobs with phases and calls assigned
     */
    public static DiagnosticsTimeline aggregate(List<CallTimeline> calls, List<JobTimeline> jobs) {

        // Ensure per-call duration is computed when possible and build mapping by callId
        Map<String, CallTimeline> normalizedCalls = new LinkedHashMap<>();
        for (CallTimeline c : calls) {
            if (c == null) continue;
            Long start = c.startTime();
            Long end = c.endTime();
            Long dur = c.durationMs();
            if (dur == null && start != null && end != null) {
                dur = end - start;
            }
            CallTimeline normalized = new CallTimeline(
                    c.callId(),
                    c.jobId(),
                    c.sessionId(),
                    c.phaseId(),
                    start,
                    end,
                    dur,
                    c.model(),
                    c.modelRole(),
                    c.promptRaw(),
                    c.promptTruncated(),
                    c.promptTokenCount(),
                    c.completionRaw(),
                    c.completionTruncated(),
                    c.completionTokenCount(),
                    c.reasoningContent(),
                    c.thoughtSignature(),
                    c.tools(),
                    c.attemptIndex(),
                    c.status());
            normalizedCalls.put(normalized.callId(), normalized);
        }

        // Index jobs by id for quick lookup
        Map<String, JobTimeline> jobById =
                jobs.stream().collect(Collectors.toMap(JobTimeline::jobId, j -> j, (a, b) -> a, LinkedHashMap::new));

        // Prepare assignment: callId -> assigned jobId (or null for unknown)
        Map<String, String> callToJob = new HashMap<>();

        // First pass: use explicit call.jobId when it matches a known job
        for (CallTimeline c : normalizedCalls.values()) {
            String explicitJob = c.jobId();
            if (explicitJob != null && jobById.containsKey(explicitJob)) {
                callToJob.put(c.callId(), explicitJob);
            }
        }

        // Second pass: timestamp-based for those still unassigned
        for (CallTimeline c : normalizedCalls.values()) {
            if (callToJob.containsKey(c.callId())) continue;
            Long t = c.startTime();
            if (t == null) continue; // can't match by time
            List<String> matches = new ArrayList<>();
            for (var entry : jobById.entrySet()) {
                var jt = entry.getValue();
                Long js = jt.startTime();
                Long je = jt.endTime();
                if (js != null && je != null) {
                    if (t >= js && t <= je) matches.add(jt.jobId());
                }
            }
            if (matches.size() == 1) {
                callToJob.put(c.callId(), matches.get(0));
            } else {
                // ambiguous or none -> leave unassigned for now
            }
        }

        // Build per-job call lists
        Map<String, List<CallTimeline>> callsByJob = new LinkedHashMap<>();
        for (var j : jobById.keySet()) callsByJob.put(j, new ArrayList<>());

        List<CallTimeline> unassigned = new ArrayList<>();
        for (CallTimeline c : normalizedCalls.values()) {
            String assigned = callToJob.get(c.callId());
            if (assigned != null && callsByJob.containsKey(assigned)) {
                callsByJob.get(assigned).add(c);
            } else {
                unassigned.add(c);
            }
        }

        // Any remaining unassigned calls go to special unknown job
        // We'll create an "unknown" job entry to hold them
        JobTimeline unknownJob = new JobTimeline(
                UNKNOWN_JOB_ID,
                UNKNOWN_JOB_MODE,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of());

        // Build aggregated JobTimelines with phase assignment and metrics
        List<JobTimeline> outJobs = new ArrayList<>();

        for (var entry : jobById.entrySet()) {
            String jobId = entry.getKey();
            JobTimeline src = entry.getValue();
            List<CallTimeline> jobCalls = callsByJob.getOrDefault(jobId, List.of());

            // Assign calls to phases
            List<PhaseTimeline> finalPhases = assignPhasesForJob(src, jobCalls);

            // Build calls with jobId and phaseId populated (phaseId may be null)
            Map<String, String> callToPhase = new HashMap<>();
            for (PhaseTimeline p : finalPhases) {
                for (String cid : p.callIds()) {
                    callToPhase.put(cid, p.phaseId());
                }
            }

            List<CallTimeline> finalCalls = jobCalls.stream()
                    .map(c -> {
                        String pid = callToPhase.get(c.callId());
                        return new CallTimeline(
                                c.callId(),
                                jobId,
                                c.sessionId(),
                                pid,
                                c.startTime(),
                                c.endTime(),
                                c.durationMs(),
                                c.model(),
                                c.modelRole(),
                                c.promptRaw(),
                                c.promptTruncated(),
                                c.promptTokenCount(),
                                c.completionRaw(),
                                c.completionTruncated(),
                                c.completionTokenCount(),
                                c.reasoningContent(),
                                c.thoughtSignature(),
                                c.tools(),
                                c.attemptIndex(),
                                c.status());
                    })
                    .collect(Collectors.toList());

            // Compute job aggregates from phases/calls
            Map<String, Object> aggregates = computeJobAggregates(finalPhases, finalCalls);

            JobTimeline aggregated = new JobTimeline(
                    jobId,
                    src.mode(),
                    src.tags(),
                    src.startTime(),
                    src.endTime(),
                    src.durationMs(),
                    src.modelConfig(),
                    src.reasoningOverrides(),
                    finalPhases,
                    finalCalls,
                    aggregates);

            outJobs.add(aggregated);
        }

        // Build unknown job if there are unassigned calls; also compute heuristics for them
        if (!unassigned.isEmpty()) {
            List<PhaseTimeline> phases = assignPhasesForJob(unknownJob, unassigned);
            Map<String, String> callToPhase = new HashMap<>();
            for (PhaseTimeline p : phases) {
                for (String cid : p.callIds()) callToPhase.put(cid, p.phaseId());
            }
            List<CallTimeline> finalCalls = unassigned.stream()
                    .map(c -> new CallTimeline(
                            c.callId(),
                            UNKNOWN_JOB_ID,
                            c.sessionId(),
                            callToPhase.get(c.callId()),
                            c.startTime(),
                            c.endTime(),
                            c.durationMs(),
                            c.model(),
                            c.modelRole(),
                            c.promptRaw(),
                            c.promptTruncated(),
                            c.promptTokenCount(),
                            c.completionRaw(),
                            c.completionTruncated(),
                            c.completionTokenCount(),
                            c.reasoningContent(),
                            c.thoughtSignature(),
                            c.tools(),
                            c.attemptIndex(),
                            c.status()))
                    .collect(Collectors.toList());

            Map<String, Object> aggregates = computeJobAggregates(phases, finalCalls);

            JobTimeline aggregatedUnknown = new JobTimeline(
                    UNKNOWN_JOB_ID,
                    UNKNOWN_JOB_MODE,
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    phases,
                    finalCalls,
                    aggregates);
            outJobs.add(aggregatedUnknown);
        }

        // Synthetic single session grouping
        SessionTimeline session = new SessionTimeline(null, "aggregated", List.copyOf(outJobs));
        DiagnosticsTimeline dt = new DiagnosticsTimeline(List.of(session));
        return dt;
    }

    private static Map<String, Object> computePhaseMetrics(List<CallTimeline> calls) {
        int totalCalls = calls.size();
        long totalDuration = 0L;
        int totalToolCalls = 0;
        for (CallTimeline c : calls) {
            if (c.durationMs() != null) {
                totalDuration += c.durationMs();
            }
            if (c.tools() != null) totalToolCalls += c.tools().size();
        }
        double avgReasoningDepth = totalCalls == 0 ? 0.0 : ((double) totalToolCalls) / totalCalls;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalCalls", totalCalls);
        metrics.put("totalDurationMs", totalDuration);
        metrics.put("avgReasoningDepth", avgReasoningDepth);
        return metrics;
    }

    private static Map<String, Object> computeJobAggregates(List<PhaseTimeline> phases, List<CallTimeline> calls) {
        int totalCalls = 0;
        long totalDuration = 0L;
        int countToolCalls = 0;
        for (CallTimeline c : calls) {
            totalCalls++;
            if (c.durationMs() != null) totalDuration += c.durationMs();
            if (c.tools() != null) countToolCalls += c.tools().size();
        }
        double avgReasoningDepth = totalCalls == 0 ? 0.0 : ((double) countToolCalls) / totalCalls;

        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("totalCalls", totalCalls);
        agg.put("totalDurationMs", totalDuration);
        agg.put("avgReasoningDepth", avgReasoningDepth);

        // also include per-phase summaries if available
        if (!phases.isEmpty()) {
            Map<String, Map<String, Object>> phaseSummaries = new LinkedHashMap<>();
            for (PhaseTimeline p : phases) {
                phaseSummaries.put(p.phaseId(), p.metrics() == null ? Map.of() : p.metrics());
            }
            agg.put("phases", phaseSummaries);
        }

        return agg;
    }

    private static List<PhaseTimeline> assignPhasesForJob(JobTimeline job, List<CallTimeline> jobCalls) {
        List<PhaseTimeline> explicit = job.phases();

        // If explicit phases exist and have time windows, use them to bucket calls
        boolean hasExplicitTimeWindows = explicit.stream().anyMatch(p -> p.startTime() != null && p.endTime() != null);
        if (!explicit.isEmpty() && hasExplicitTimeWindows) {
            // map calls into explicit phases
            List<PhaseTimeline> result = new ArrayList<>();
            for (PhaseTimeline p : explicit) {
                List<String> callIds = new ArrayList<>();
                for (CallTimeline c : jobCalls) {
                    Long st = c.startTime();
                    if (st == null) continue;
                    if (p.startTime() != null && p.endTime() != null && st >= p.startTime() && st <= p.endTime()) {
                        callIds.add(c.callId());
                    }
                }
                Map<String, Object> metrics = computePhaseMetrics(jobCalls.stream()
                        .filter(cc -> callIds.contains(cc.callId()))
                        .collect(Collectors.toList()));
                PhaseTimeline np = new PhaseTimeline(
                        p.phaseId(),
                        p.type(),
                        p.label(),
                        p.startTime(),
                        p.endTime(),
                        p.durationMs(),
                        List.copyOf(callIds),
                        metrics);
                result.add(np);
            }
            return result;
        }

        // Otherwise build heuristic phases
        String mode = job.mode() == null ? "" : job.mode().toUpperCase(Locale.ROOT);
        List<PhaseTimeline> built = new ArrayList<>();

        if ("ASK".equals(mode)) {
            // For ASK: calls whose model.name equals job.modelConfig.name => PLANNING, others => SCAN
            String planner =
                    job.modelConfig() == null ? null : job.modelConfig().name();
            List<CallTimeline> scanCalls = new ArrayList<>();
            List<CallTimeline> planningCalls = new ArrayList<>();
            for (CallTimeline c : jobCalls) {
                String mname = c.model() == null ? null : c.model().name();
                if (planner != null && Objects.equals(planner, mname)) {
                    planningCalls.add(c);
                } else {
                    scanCalls.add(c);
                }
            }
            int seq = 1;
            if (!scanCalls.isEmpty()) {
                Long start = scanCalls.stream()
                        .map(CallTimeline::startTime)
                        .filter(Objects::nonNull)
                        .min(Long::compare)
                        .orElse(null);
                Long end = scanCalls.stream()
                        .map(CallTimeline::endTime)
                        .filter(Objects::nonNull)
                        .max(Long::compare)
                        .orElse(null);
                List<String> ids = scanCalls.stream().map(CallTimeline::callId).collect(Collectors.toList());
                Map<String, Object> metrics = computePhaseMetrics(scanCalls);
                PhaseTimeline p = new PhaseTimeline(
                        job.jobId() + "-scan-" + seq++,
                        "SCAN",
                        "Pre-scan",
                        start,
                        end,
                        (start != null && end != null) ? (end - start) : null,
                        ids,
                        metrics);
                built.add(p);
            }
            if (!planningCalls.isEmpty()) {
                Long start = planningCalls.stream()
                        .map(CallTimeline::startTime)
                        .filter(Objects::nonNull)
                        .min(Long::compare)
                        .orElse(null);
                Long end = planningCalls.stream()
                        .map(CallTimeline::endTime)
                        .filter(Objects::nonNull)
                        .max(Long::compare)
                        .orElse(null);
                List<String> ids =
                        planningCalls.stream().map(CallTimeline::callId).collect(Collectors.toList());
                Map<String, Object> metrics = computePhaseMetrics(planningCalls);
                PhaseTimeline p = new PhaseTimeline(
                        job.jobId() + "-plan-" + seq++,
                        "PLANNING",
                        "Planning/Analysis",
                        start,
                        end,
                        (start != null && end != null) ? (end - start) : null,
                        ids,
                        metrics);
                built.add(p);
            }
            if (built.isEmpty()) {
                // unknown fallback
                PhaseTimeline p = new PhaseTimeline(
                        job.jobId() + "-unknown",
                        "UNKNOWN",
                        "Unknown Phase",
                        job.startTime(),
                        job.endTime(),
                        (job.startTime() != null && job.endTime() != null) ? job.endTime() - job.startTime() : null,
                        jobCalls.stream().map(CallTimeline::callId).collect(Collectors.toList()),
                        computePhaseMetrics(jobCalls));
                built = List.of(p);
            }
            return built;
        }

        // Generic fallback: single phase "OTHER" or preserve explicit phase types without timing
        if (!explicit.isEmpty()) {
            // convert explicit phases to include call lists (no timing)
            List<PhaseTimeline> result = new ArrayList<>();
            for (PhaseTimeline p : explicit) {
                List<String> ids = new ArrayList<>();
                // best-effort: match by rough ordering -> if phase has no times, assign nothing
                result.add(new PhaseTimeline(
                        p.phaseId(),
                        p.type(),
                        p.label(),
                        p.startTime(),
                        p.endTime(),
                        p.durationMs(),
                        List.copyOf(ids),
                        Map.of()));
            }
            if (result.isEmpty()) {
                PhaseTimeline p = new PhaseTimeline(
                        job.jobId() + "-unknown",
                        "UNKNOWN",
                        "Unknown Phase",
                        job.startTime(),
                        job.endTime(),
                        (job.startTime() != null && job.endTime() != null) ? job.endTime() - job.startTime() : null,
                        jobCalls.stream().map(CallTimeline::callId).collect(Collectors.toList()),
                        computePhaseMetrics(jobCalls));
                return List.of(p);
            }
            return result;
        }

        // No explicit phases and no special heuristics matched -> single UNKNOWN phase
        PhaseTimeline p = new PhaseTimeline(
                job.jobId() + "-unknown",
                "UNKNOWN",
                "Unknown Phase",
                job.startTime(),
                job.endTime(),
                (job.startTime() != null && job.endTime() != null) ? job.endTime() - job.startTime() : null,
                jobCalls.stream().map(CallTimeline::callId).collect(Collectors.toList()),
                computePhaseMetrics(jobCalls));
        return List.of(p);
    }
}
