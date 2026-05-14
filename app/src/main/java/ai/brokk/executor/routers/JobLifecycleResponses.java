package ai.brokk.executor.routers;

import ai.brokk.executor.JobReservation;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobStatus;
import ai.brokk.executor.jobs.JobStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class JobLifecycleResponses {
    static final int RETRY_AFTER_MS = 1000;

    private JobLifecycleResponses() {}

    static Map<String, Object> readiness(JobStore jobStore, JobReservation reservation) throws IOException {
        var activeJobId = reservation.current();
        var response = new LinkedHashMap<String, Object>();
        response.put("readyForJobSubmission", activeJobId == null);
        response.put("activeJobId", activeJobId);
        response.put("activeJobState", activeJobState(jobStore, activeJobId));
        response.put("retryAfterMs", activeJobId == null ? 0 : RETRY_AFTER_MS);
        response.put("terminal", activeJobId == null);
        return response;
    }

    static Map<String, Object> status(JobStatus status, JobReservation reservation) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jobId", status.jobId());
        response.put("state", status.state());
        response.put("startTime", status.startTime());
        response.put("endTime", status.endTime());
        response.put("progressPercent", status.progressPercent());
        response.put("result", status.result());
        response.put("error", status.error());
        response.put("metadata", status.metadata());
        response.put("terminal", status.terminal());
        response.put("executorReady", executorReady(reservation));
        return response;
    }

    static Map<String, Object> cancelResponse(
            String jobId, @Nullable JobStatus status, boolean cancelRequested, JobReservation reservation) {
        var state = status != null ? status.state() : "UNKNOWN";
        var response = new LinkedHashMap<String, Object>();
        response.put("jobId", jobId);
        response.put("cancelRequested", cancelRequested);
        response.put("state", state);
        response.put("terminal", status == null || JobStatus.isTerminalState(state));
        response.put("executorReady", executorReady(reservation));
        response.put("activeJobId", reservation.current());
        return response;
    }

    static ErrorPayload jobInProgress(JobStore jobStore, JobReservation reservation) throws IOException {
        return ErrorPayload.of("JOB_IN_PROGRESS", "A job is currently executing", busyDetails(jobStore, reservation));
    }

    private static Map<String, Object> busyDetails(JobStore jobStore, JobReservation reservation) throws IOException {
        var activeJobId = reservation.current();
        var details = new LinkedHashMap<String, Object>();
        details.put("activeJobId", activeJobId);
        details.put("activeJobState", activeJobState(jobStore, activeJobId));
        details.put("readyForJobSubmission", false);
        details.put("retryAfterMs", RETRY_AFTER_MS);
        return details;
    }

    private static @Nullable String activeJobState(JobStore jobStore, @Nullable String activeJobId) throws IOException {
        if (activeJobId == null) {
            return null;
        }
        var status = jobStore.loadStatus(activeJobId);
        return status != null ? status.state() : "UNKNOWN";
    }

    private static boolean executorReady(JobReservation reservation) {
        return reservation.current() == null;
    }
}
