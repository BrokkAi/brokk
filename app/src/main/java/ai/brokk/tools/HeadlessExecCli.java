package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.executor.HeadlessExecutorMain;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * CLI tool for testing the headless executor end-to-end.
 * Starts a local executor, creates a session, submits a job, and streams results.
 *
 * Usage: java HeadlessExecCli [options] <prompt>
 * Options:
 *   --mode MODE              Execution mode: ASK, CODE, ARCHITECT, or LUTZ (default: ARCHITECT)
 *   --planner-model MODEL    Planner model name (required)
 *   --code-model MODEL       Code model name (optional)
 *   --token TOKEN            Auth token (default: random UUID)
 *   --auto-commit            Enable auto-commit
 *   --auto-compress          Enable auto-compress
 */
public class HeadlessExecCli {
    private static final Logger logger = LogManager.getLogger(HeadlessExecCli.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final MediaType JSON;
    private static final int READY_POLL_TIMEOUT_MS = 30000;
    private static final int READY_POLL_INTERVAL_MS = 500;
    private static final int JOB_POLL_INTERVAL_MS = 1000;

    private String mode = "ARCHITECT";
    private String plannerModel = "claude-opus-4-5";
    private String codeModel = "claude-sonnet-4-5";
    private String scanModel = "";
    private String authToken = "";
    private boolean autoCommit = false;
    private boolean autoCompress = false;
    private String prompt = "";

    private HeadlessExecutorMain executor;
    private Path tempWorkspace;
    private OkHttpClient httpClient;

    public HeadlessExecCli() {
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        if (mediaType == null) {
            throw new IllegalStateException("Failed to parse JSON media type");
        }
        JSON = mediaType;
        if (authToken.isBlank()) {
            authToken = UUID.randomUUID().toString();
        }
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // Create temporary workspace
        try {
            tempWorkspace = Files.createTempDirectory("brokk-headless-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp workspace", e);
        }
        logger.info("Created temp workspace: {}", tempWorkspace);
        // Start executor in-process on random port
        var execId = UUID.randomUUID();
        var project = new MainProject(tempWorkspace);
        var contextManager = new ContextManager(project);
        try {
            executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", authToken, contextManager);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize HeadlessExecutorMain", e);
        }
    }

    private int run() {
        try {
            executor.start();
            int port = executor.getPort();
            var baseUrl = "http://127.0.0.1:" + port;
            logger.info("Executor started on port {}", port);

            // Create session first
            var sessionId = createSession(baseUrl);
            if (sessionId == null) {
                System.err.println("ERROR: Failed to create session");
                return 1;
            }
            logger.info("Created session: {}", sessionId);

            // Poll /health/ready until ready
            if (!pollHealthReady(baseUrl)) {
                System.err.println("ERROR: Executor failed to become ready");
                return 1;
            }

            // Submit job
            var jobId = submitJob(baseUrl, sessionId);
            if (jobId == null) {
                System.err.println("ERROR: Failed to submit job");
                return 1;
            }
            logger.info("Submitted job: {}", jobId);

            // Stream events until job completes
            int exitCode = streamJobEvents(baseUrl, jobId);
            return exitCode;

        } catch (Exception e) {
            logger.error("Fatal error", e);
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        } finally {
            // Cleanup
            cleanup();
        }
    }

    /**
     * POST /v1/sessions to create a session.
     */
    @Nullable
    private String createSession(String baseUrl) throws IOException {
        var body = mapper.createObjectNode();
        body.put("name", "CLI Session " + System.currentTimeMillis());

        var request = new Request.Builder()
                .url(baseUrl + "/v1/sessions")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + authToken)
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (response.code() != 201) {
                logger.error("Failed to create session: {} {}", response.code(), response.body());
                return null;
            }
            var responseBody = response.body();
            if (responseBody == null) {
                logger.error("Empty response body from /v1/sessions");
                return null;
            }
            var json = mapper.readTree(responseBody.string());
            return json.get("sessionId").asText();
        }
    }

    /**
     * Poll /health/ready until executor is ready or timeout.
     */
    private boolean pollHealthReady(String baseUrl) {
        long deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                var request = new Request.Builder()
                        .url(baseUrl + "/health/ready")
                        .get()
                        .build();
                try (var response = httpClient.newCall(request).execute()) {
                    if (response.code() == 200) {
                        logger.info("/health/ready returned 200");
                        return true;
                    }
                }
            } catch (IOException e) {
                logger.debug("Health check failed, retrying...", e);
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * POST /v1/jobs to submit a job.
     */
    @Nullable
    private String submitJob(String baseUrl, String sessionId) throws IOException {
        var jobSpec = mapper.createObjectNode();
        jobSpec.put("sessionId", sessionId);
        jobSpec.put("taskInput", prompt);
        jobSpec.put("autoCommit", autoCommit);
        jobSpec.put("autoCompress", autoCompress);
        jobSpec.put("plannerModel", plannerModel);
        if (!scanModel.isBlank()) {
            jobSpec.put("scanModel", scanModel);
        }
        if (!codeModel.isBlank()) {
            jobSpec.put("codeModel", codeModel);
        }
        if (mode.isBlank()) {
            mode = "ARCHITECT";
        } else {
            mode = mode.toUpperCase(Locale.ROOT);
        }
        var tags = mapper.createObjectNode();
        tags.put("mode", mode);
        jobSpec.set("tags", tags);

        var request = new Request.Builder()
                .url(baseUrl + "/v1/jobs")
                .post(RequestBody.create(mapper.writeValueAsString(jobSpec), JSON))
                .header("Authorization", "Bearer " + authToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (response.code() != 201 && response.code() != 200) {
                logger.error("Failed to submit job: {} {}", response.code(), response.body());
                return null;
            }
            var responseBody = response.body();
            if (responseBody == null) {
                logger.error("Empty response body from /v1/jobs");
                return null;
            }
            var json = mapper.readTree(responseBody.string());
            return json.get("jobId").asText();
        }
    }

    /**
     * Stream job events and monitor job status until terminal state.
     */
    private int streamJobEvents(String baseUrl, String jobId) {
        long afterSeq = -1;
        long pollDeadline = System.currentTimeMillis() + 3600000; // 1 hour

        while (System.currentTimeMillis() < pollDeadline) {
            try {
                // Get current job status
                var statusRequest = new Request.Builder()
                        .url(baseUrl + "/v1/jobs/" + jobId)
                        .get()
                        .header("Authorization", "Bearer " + authToken)
                        .build();

                String jobState = null;
                try (var statusResponse = httpClient.newCall(statusRequest).execute()) {
                    if (statusResponse.code() == 200 && statusResponse.body() != null) {
                        var statusJson = mapper.readTree(statusResponse.body().string());
                        jobState = statusJson.get("state").asText();
                    }
                }

                // Fetch and stream events
                var eventsUrl = baseUrl + "/v1/jobs/" + jobId + "/events?after=" + afterSeq + "&limit=100";
                var eventsRequest = new Request.Builder()
                        .url(eventsUrl)
                        .get()
                        .header("Authorization", "Bearer " + authToken)
                        .build();

                try (var eventsResponse = httpClient.newCall(eventsRequest).execute()) {
                    if (eventsResponse.code() == 200 && eventsResponse.body() != null) {
                        var eventsJson = mapper.readTree(eventsResponse.body().string());
                        var events = eventsJson.get("events");

                        if (events != null && events.isArray()) {
                            for (var event : events) {
                                var data = event.get("data");
                                if (data != null) {
                                    System.out.println(data.asText());
                                    System.out.flush();
                                }
                                var seq = event.get("seq");
                                if (seq != null) {
                                    afterSeq = seq.asLong();
                                }
                            }
                        }
                    }
                }

                // Check if terminal state
                if (jobState != null) {
                    if ("COMPLETED".equals(jobState)) {
                        logger.info("Job completed successfully");
                        return 0;
                    } else if ("FAILED".equals(jobState)) {
                        logger.error("Job failed");
                        System.err.println("ERROR: Job failed");
                        return 1;
                    } else if ("CANCELLED".equals(jobState)) {
                        logger.info("Job cancelled");
                        System.err.println("Job cancelled");
                        return 2;
                    }
                }

                // Sleep before next poll
                Thread.sleep(JOB_POLL_INTERVAL_MS);

            } catch (IOException e) {
                logger.error("Error streaming events", e);
                System.err.println("ERROR: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Interrupted while streaming events");
                return 2;
            }
        }

        System.err.println("ERROR: Job streaming timeout");
        return 1;
    }

    /**
     * Cleanup: stop executor and delete temp workspace.
     */
    private void cleanup() {
        try {
            executor.stop(0);
            logger.info("Executor stopped");
        } catch (Exception e) {
            logger.warn("Error stopping executor", e);
        }

        try {
            recursiveDelete(tempWorkspace);
            logger.info("Deleted temp workspace: {}", tempWorkspace);
        } catch (IOException e) {
            logger.warn("Error deleting temp workspace", e);
        }

        httpClient.dispatcher().executorService().shutdown();
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private static void recursiveDelete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        recursiveDelete(p);
                    } catch (IOException e) {
                        logger.warn("Error deleting {}", p, e);
                    }
                });
            }
        }
        Files.delete(path);
    }

    public static void main(String[] args) {
        int exitCode = runCli(args);
        System.exit(exitCode);
    }

    /**
     * Testable entry point for HeadlessExecCli. Runs the CLI and returns an exit code.
     * Does not call System.exit().
     */
    static int runCli(String[] args) {
        try {
            if (args.length == 0 || containsHelpFlag(args)) {
                printUsage();
                return 0;
            }

            var cli = new HeadlessExecCli();
            if (!cli.parseArgs(args)) {
                return 1;
            }
            return cli.run();
        } catch (Exception e) {
            System.err.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static boolean containsHelpFlag(String[] args) {
        for (var arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage: java HeadlessExecCli [options] <prompt>");
        System.out.println();
        System.out.println("Options:");
        System.out.println(
                "  --mode MODE              Execution mode: ASK, CODE, ARCHITECT, LUTZ, or SEARCH (default: ARCHITECT)");
        System.out.println("  --planner-model MODEL    Planner model name (required)");
        System.out.println("  --scan-model MODEL       Scan model name (optional; used by SEARCH mode)");
        System.out.println("  --code-model MODEL       Code model name (optional)");
        System.out.println("  --token TOKEN            Auth token (default: random UUID)");
        System.out.println("  --auto-commit            Enable auto-commit of changes");
        System.out.println("  --auto-compress          Enable auto-compress of context");
        System.out.println("  --help                   Show this help message");
        System.out.println();
        System.out.println("Note: In SEARCH mode, --code-model is ignored (SearchAgent is read-only and does not generate code).");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java HeadlessExecCli --planner-model gpt-5 --mode SEARCH --scan-model gpt-5-mini 'Describe the project layout'");
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                if (arg.contains("=")) {
                    // Form: --key=value
                    var parts = arg.substring(2).split("=", 2);
                    var key = parts[0];
                    var value = parts.length > 1 ? parts[1] : "";
                    if (!parseOption(key, value)) {
                        return false;
                    }
                } else {
                    // Form: --key value
                    var key = arg.substring(2);
                    if ("auto-commit".equals(key) || "auto-compress".equals(key)) {
                        // Boolean flags
                        parseOption(key, "true");
                    } else {
                        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                            System.err.println("ERROR: Option --" + key + " requires a value");
                            return false;
                        }
                        var value = args[++i];
                        if (!parseOption(key, value)) {
                            return false;
                        }
                    }
                }
            } else {
                // Positional argument (prompt)
                if (prompt.isBlank()) {
                    prompt = arg;
                } else {
                    System.err.println("ERROR: Multiple prompts provided");
                    return false;
                }
            }
        }

        // Validate required arguments
        if (plannerModel.isBlank()) {
            System.err.println("ERROR: --planner-model is required");
            return false;
        }
        if (prompt.isBlank()) {
            System.err.println("ERROR: <prompt> positional argument is required");
            return false;
        }
        return true;
    }

    private boolean parseOption(String key, String value) {
        switch (key) {
            case "mode" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --mode requires a value");
                    return false;
                }
                mode = Ascii.toUpperCase(value);
                if (!mode.matches("^(ASK|CODE|ARCHITECT|LUTZ|SEARCH)$")) {
                    System.err.println("ERROR: Invalid mode: " + value + ". Must be ASK, CODE, ARCHITECT, LUTZ, or SEARCH");
                    return false;
                }
            }
            case "planner-model" -> plannerModel = value;
            case "scan-model" -> scanModel = value;
            case "code-model" -> codeModel = value;
            case "token" -> authToken = value;
            case "auto-commit" -> autoCommit = true;
            case "auto-compress" -> autoCompress = true;
            default -> {
                System.err.println("ERROR: Unknown option: --" + key);
                return false;
            }
        }
        return true;
    }
}
