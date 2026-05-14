package ai.brokk.executor.routers;

import ai.brokk.executor.JobReservation;
import ai.brokk.executor.jobs.JobStatus;
import ai.brokk.executor.jobs.JobStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class JobLifecycleResponses {
    static final int RETRY_AFTER_MS = 1000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> STATUS_MAP_TYPE = new TypeReference<>() {};

    private JobLifecycleResponses() {}

    static Map<String, Object> readiness(JobStore jobStore, JobReservation reservation) throws IOException {
        var activeJobId = reservation.current();
        var response = new LinkedHashMap<String, Object>();
        response.put("readyForJobSubmission", activeJobId == null);
        response.put("activeJobId", null);
        response.put("activeJobState", activeJobState(jobStore, activeJobId));
        response.put("retryAfterMs", activeJobId == null ? 0 : RETRY_AFTER_MS);
        response.put("terminal", activeJobId == null);
        return response;
    }

    static Map<String, Object> status(JobStatus status, JobReservation reservation) {
        var response = OBJECT_MAPPER.convertValue(status, STATUS_MAP_TYPE);
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
        response.put("activeJobId", cancelRequested ? jobId : null);
        return response;
    }

    static JobInProgressError jobInProgress(JobStore jobStore, JobReservation reservation) throws IOException {
        return new JobInProgressError(
                "JOB_IN_PROGRESS", "A job is currently executing", busyDetails(jobStore, reservation));
    }

    private static JobInProgressDetails busyDetails(JobStore jobStore, JobReservation reservation) throws IOException {
        var activeJobId = reservation.current();
        return new JobInProgressDetails(null, activeJobState(jobStore, activeJobId), false, RETRY_AFTER_MS);
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

    record JobInProgressError(String code, String message, JobInProgressDetails details) {}

    record JobInProgressDetails(
            @Nullable String activeJobId,
            @Nullable String activeJobState,
            boolean readyForJobSubmission,
            int retryAfterMs) {}
}
