package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.executor.HeadlessExecutorMain;
import ai.brokk.executor.HeadlessExecutorMain.TlsConfig;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.project.MainProject;
import ai.brokk.util.CloneOperationTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
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
    private static final String REPO_COMPONENT_ALLOWLIST_REGEX = "^[A-Za-z0-9_.-]+$";
    private final MediaType JSON;
    private static final int READY_POLL_TIMEOUT_MS = 30000;
    private static final int READY_POLL_INTERVAL_MS = 500;
    private static final int JOB_POLL_INTERVAL_MS = 1000;
    private static final long JOB_STREAM_TIMEOUT_MS = 3 * 60 * 60 * 1000L; // 3 hours

    private String mode = "ARCHITECT";
    private String plannerModel = "claude-opus-4-5";
    private String codeModel = "claude-sonnet-4-5";
    private String scanModel = "";
    private String authToken = "";
    private boolean autoCommit = false;
    private boolean autoCompress = false;
    private boolean preScan = false;
    private String reasoningLevel = "";
    private String reasoningLevelCode = "";
    private @Nullable Double temperature = null;
    private @Nullable Double temperatureCode = null;
    private String prompt = "";

    private String githubToken = "";
    private String repoOwner = "";
    private String repoName = "";
    private int issueNumber = 0;
    private int prNumber = 0;
    private @Nullable Integer maxIssueFixAttempts = null;
    private String buildSettings = "";
    private String issueDelivery = "";

    // New flag: when true and mode == ISSUE, instruct server to skip verification (tests/lint/review loops)
    private boolean skipVerification = false;

    private @Nullable HeadlessExecutorMain executor;

    private @Nullable Path tempWorkspaceRoot;
    private @Nullable Path workspaceRoot;
    private boolean createdTempWorkspace = false;

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
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private int run() {
        try {
            initializeExecutorAndWorkspaceIfNeeded();

            HeadlessExecutorMain executorLocal = requireInitializedExecutor();

            executorLocal.start();
            int port = executorLocal.getPort();
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
            cleanup();
        }
    }

    record WorkspaceSelection(Path root, boolean isTemporary) {
        WorkspaceSelection {
            root = root.toAbsolutePath().normalize();
        }
    }

    WorkspaceSelection chooseWorkspaceRootForMode() {
        return chooseWorkspaceRootForMode(mode, repoOwner, repoName);
    }

    static WorkspaceSelection chooseWorkspaceRootForMode(String mode, String repoOwner, String repoName) {
        String normalizedMode = mode.isBlank() ? "ARCHITECT" : mode.toUpperCase(Locale.ROOT);
        if ("ISSUE".equals(normalizedMode)
                || "ISSUE_DIAGNOSE".equals(normalizedMode)
                || "REVIEW".equals(normalizedMode)
                || "ISSUE_WRITER".equals(normalizedMode)) {
            String safeOwner = repoOwner.isBlank() ? "unknown-owner" : repoOwner.replaceAll("[^A-Za-z0-9._-]", "-");
            String safeRepo = repoName.isBlank() ? "unknown-repo" : repoName.replaceAll("[^A-Za-z0-9._-]", "-");
            String prefix = "brokk-headless-" + safeOwner + "-" + safeRepo + "-";

            Path tempRoot;
            try {
                tempRoot = Files.createTempDirectory(prefix).toAbsolutePath().normalize();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temp workspace", e);
            }
            return new WorkspaceSelection(tempRoot, true);
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return new WorkspaceSelection(cwd, false);
    }

    private static String buildGitHubHttpsCloneUrl(String owner, String repo) {
        return "https://github.com/" + owner + "/" + repo + ".git";
    }

    private void initializeExecutorAndWorkspaceIfNeeded() throws IOException, GitAPIException {
        if (executor != null) {
            return;
        }

        WorkspaceSelection selection = chooseWorkspaceRootForMode();
        if (selection.isTemporary()) {
            tempWorkspaceRoot = selection.root();
            createdTempWorkspace = true;

            String cloneUrl = buildGitHubHttpsCloneUrl(repoOwner, repoName);
            Supplier<String> tokenSupplier = () -> githubToken;

            Path cloneTarget =
                    tempWorkspaceRoot.resolve(repoName).toAbsolutePath().normalize();
            workspaceRoot = cloneTarget;

            logger.info("Using temp workspace root: {}", tempWorkspaceRoot);
            logger.info("Cloning {} into: {}", cloneUrl, cloneTarget);

            CloneOperationTracker.registerCloneOperation(tempWorkspaceRoot);
            try {
                Files.createDirectories(tempWorkspaceRoot);
                CloneOperationTracker.createInProgressMarker(tempWorkspaceRoot, cloneUrl, "");
                GitRepoFactory.cloneRepo(tokenSupplier, cloneUrl, cloneTarget, 0);
                CloneOperationTracker.createCompleteMarker(tempWorkspaceRoot, cloneUrl, "");
            } finally {
                CloneOperationTracker.unregisterCloneOperation(tempWorkspaceRoot);
            }
        } else {
            tempWorkspaceRoot = selection.root();
            workspaceRoot = tempWorkspaceRoot;
            createdTempWorkspace = false;
            logger.info("Using current working directory as workspace: {}", workspaceRoot);
        }

        var execId = UUID.randomUUID();
        var project = new MainProject(workspaceRoot);
        var contextManager = new ContextManager(project);
        var tlsConfig = new TlsConfig(false, null, null, null, false);
        executor = new HeadlessExecutorMain(execId, "127.0.0.1:0", authToken, contextManager, tlsConfig);
    }

    private HeadlessExecutorMain requireInitializedExecutor() {
        HeadlessExecutorMain ex = executor;
        if (ex == null) {
            throw new IllegalStateException("Executor not initialized");
        }
        return ex;
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

        if (!reasoningLevel.isBlank()) {
            jobSpec.put("reasoningLevel", reasoningLevel);
        }
        if (!reasoningLevelCode.isBlank()) {
            jobSpec.put("reasoningLevelCode", reasoningLevelCode);
        }
        if (temperature != null) {
            jobSpec.put("temperature", temperature.doubleValue());
        }
        if (temperatureCode != null) {
            jobSpec.put("temperatureCode", temperatureCode.doubleValue());
        }

        // Normalize mode early so we can correctly decide when to include scanModel / preScan
        if (mode.isBlank()) {
            mode = "ARCHITECT";
        } else {
            mode = mode.toUpperCase(Locale.ROOT);
        }

        // Include scanModel only when explicitly provided and relevant:
        // - SEARCH mode: use scanModel if provided
        // - ASK mode: include scanModel only when preScan is requested
        if (!scanModel.isBlank()) {
            if ("SEARCH".equals(mode) || ("ASK".equals(mode) && preScan)) {
                jobSpec.put("scanModel", scanModel);
            }
        }

        // Preserve existing behavior for codeModel (optional override)
        if (!codeModel.isBlank()) {
            jobSpec.put("codeModel", codeModel);
        }

        // Include preScan boolean only for ASK jobs when requested
        if ("ASK".equals(mode) && preScan) {
            jobSpec.put("preScan", true);
        }

        var tags = mapper.createObjectNode();
        tags.put("mode", mode);

        // Add Mode-specific tags and job spec fields
        if ("ISSUE".equals(mode) || "ISSUE_DIAGNOSE".equals(mode)) {
            tags.put("github_token", githubToken);
            tags.put("repo_owner", repoOwner);
            tags.put("repo_name", repoName);
            tags.put("issue_number", String.valueOf(issueNumber));

            // ISSUE-specific fields (not needed for ISSUE_DIAGNOSE which only analyzes)
            if ("ISSUE".equals(mode)) {
                if (!issueDelivery.isBlank()) {
                    tags.put("issue_delivery", issueDelivery);
                }
                if (maxIssueFixAttempts != null) {
                    jobSpec.put("maxIssueFixAttempts", maxIssueFixAttempts.intValue());
                }
                if (!buildSettings.isBlank()) {
                    // Parse buildSettings as JSON and add as object
                    try {
                        var buildSettingsJson = mapper.readTree(buildSettings);
                        jobSpec.set("buildSettings", buildSettingsJson);
                    } catch (Exception e) {
                        logger.warn("Failed to parse buildSettings as JSON, adding as string", e);
                        jobSpec.put("buildSettings", buildSettings);
                    }
                }
                // Persist the skipVerification choice explicitly so the server can map it to JobSpec.skipVerification
                if (skipVerification) {
                    jobSpec.put("skipVerification", true);
                }
            }
        } else if ("REVIEW".equals(mode)) {
            tags.put("github_token", githubToken);
            tags.put("repo_owner", repoOwner);
            tags.put("repo_name", repoName);
            tags.put("pr_number", String.valueOf(prNumber));
        } else if ("ISSUE_WRITER".equals(mode)) {
            tags.put("github_token", githubToken);
            tags.put("repo_owner", repoOwner);
            tags.put("repo_name", repoName);
        }
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
        long pollDeadline = System.currentTimeMillis() + JOB_STREAM_TIMEOUT_MS;

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
                                var seqNode = event.get("seq");
                                if (seqNode != null) {
                                    afterSeq = seqNode.asLong();
                                }

                                var typeNode = event.get("type");
                                var dataNode = event.get("data");

                                String type = typeNode != null && !typeNode.isNull() ? typeNode.asText() : "event";

                                String dataText = "";
                                if (dataNode == null || dataNode.isNull()) {
                                    dataText = "null";
                                } else if (dataNode.isTextual()) {
                                    dataText = dataNode.asText();
                                } else {
                                    try {
                                        dataText = mapper.writeValueAsString(dataNode);
                                    } catch (Exception e) {
                                        dataText = dataNode.toString();
                                    }
                                }

                                String seqPrefix =
                                        seqNode != null && !seqNode.isNull() ? ("[" + seqNode.asLong() + "] ") : "";
                                String line;
                                if (dataNode != null && dataNode.isTextual()) {
                                    line = seqPrefix + type + ": " + dataText;
                                } else {
                                    line = seqPrefix + type + " " + dataText;
                                }

                                if (!line.isBlank()) {
                                    System.out.println(line);
                                    System.out.flush();
                                } else {
                                    try {
                                        System.out.println(seqPrefix + type + " " + event.toString());
                                    } catch (Exception e) {
                                        System.out.println(seqPrefix + type);
                                    }
                                    System.out.flush();
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
            if (executor != null) {
                executor.stop(0);
                logger.info("Executor stopped");
            }
        } catch (Exception e) {
            logger.warn("Error stopping executor", e);
        }

        try {
            if (createdTempWorkspace && tempWorkspaceRoot != null) {
                recursiveDelete(tempWorkspaceRoot);
                logger.info("Deleted temp workspace root: {}", tempWorkspaceRoot);
            }
        } catch (IOException e) {
            logger.warn("Error deleting temp workspace root", e);
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
        System.out.println("Usage: java HeadlessExecCli [options] [prompt]");
        System.out.println(
                "  Note: [prompt] is optional in ISSUE/ISSUE_DIAGNOSE/REVIEW mode, and required in all other modes.");
        System.out.println();
        System.out.println("Options:");
        System.out.println(
                "  --mode MODE              Execution mode: ASK, CODE, ARCHITECT, LUTZ, SEARCH, REVIEW, ISSUE, ISSUE_DIAGNOSE, or ISSUE_WRITER (default: ARCHITECT)");
        System.out.println("  --planner-model MODEL    Planner model name (required)");
        System.out.println(
                "  --scan-model MODEL       Scan model name (optional; used by SEARCH mode; used by ASK only when --pre-scan is enabled)");
        System.out.println("  --code-model MODEL       Code model name (optional)");
        System.out.println(
                "  --pre-scan               Enable repository prescan before ASK (uses --scan-model if provided)");
        System.out.println("  --reasoning-level LEVEL  Job-level reasoning level override (optional)");
        System.out.println("  --reasoning-level-code LEVEL  Job-level code reasoning level override (optional)");
        System.out.println("  --temperature VALUE      Job-level temperature override (optional)");
        System.out.println("  --temperature-code VALUE Job-level code temperature override (optional)");
        System.out.println("  --token TOKEN            Auth token (default: random UUID)");
        System.out.println("  --auto-commit            Enable auto-commit of changes");
        System.out.println("  --auto-compress          Enable auto-compress of context");
        System.out.println("  --help                   Show this help message");
        System.out.println();
        System.out.println(
                "ISSUE/REVIEW/ISSUE_WRITER Mode Options (required when --mode ISSUE, REVIEW, or ISSUE_WRITER):");
        System.out.println("  --github-token TOKEN     GitHub API token");
        System.out.println("  --repo-owner OWNER       Repository owner (user or organization)");
        System.out.println("  --repo-name REPO         Repository name");
        System.out.println("  --issue-number NUMBER    GitHub issue number (required for ISSUE mode)");
        System.out.println("  --pr-number NUMBER       GitHub PR number (required for REVIEW mode)");
        System.out.println();
        System.out.println("ISSUE Mode Options (optional):");
        System.out.println(
                "  --max-issue-fix-attempts NUMBER  Max final verification attempts (tests/lint loop) (default: 20)");
        System.out.println("  --build-settings JSON    Build settings as JSON object");
        System.out.println("  --issue-delivery MODE    Delivery mode ('none' to skip PR creation)");
        System.out.println(
                "  --skip-verification      Skip verification and final test/lint/review loops for ISSUE mode (quick mode)");
        System.out.println();
        System.out.println(
                "Note: In SEARCH mode, --code-model is ignored (SearchAgent is read-only and does not generate code).");
        System.out.println();
        System.out.println("Example:");
        System.out.println(
                "  java HeadlessExecCli --planner-model gpt-5 --mode SEARCH --scan-model gpt-5-mini --reasoning-level medium --reasoning-level-code medium --temperature 0.2 --temperature-code 0.2 'Describe the project layout'");
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
                    if ("auto-commit".equals(key)
                            || "auto-compress".equals(key)
                            || "pre-scan".equals(key)
                            || "skip-verification".equals(key)) {
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

        String normalizedMode = mode.isBlank() ? "ARCHITECT" : mode.toUpperCase(Locale.ROOT);
        boolean promptRequired = !("ISSUE".equals(normalizedMode)
                || "ISSUE_DIAGNOSE".equals(normalizedMode)
                || "REVIEW".equals(normalizedMode));
        if (promptRequired && prompt.isBlank()) {
            System.err.println("ERROR: <prompt> positional argument is required");
            return false;
        }

        // Validate ISSUE and ISSUE_DIAGNOSE mode required fields
        if ("ISSUE".equals(mode) || "ISSUE_DIAGNOSE".equals(mode)) {
            if (githubToken.isBlank()) {
                System.err.println("ERROR: --github-token is required for " + mode + " mode");
                return false;
            }
            if (repoOwner.isBlank()) {
                System.err.println("ERROR: --repo-owner is required for " + mode + " mode");
                return false;
            }
            if (!repoOwner.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-owner '" + repoOwner + "'. Repo owner must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
            if (repoName.isBlank()) {
                System.err.println("ERROR: --repo-name is required for " + mode + " mode");
                return false;
            }
            if (!repoName.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-name '" + repoName + "'. Repo name must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
            if (issueNumber <= 0) {
                System.err.println("ERROR: --issue-number is required for " + mode + " mode");
                return false;
            }
        }

        // Validate REVIEW mode required fields
        if ("REVIEW".equals(mode)) {
            if (githubToken.isBlank()) {
                System.err.println("ERROR: --github-token is required for REVIEW mode");
                return false;
            }
            if (repoOwner.isBlank()) {
                System.err.println("ERROR: --repo-owner is required for REVIEW mode");
                return false;
            }
            if (!repoOwner.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-owner '" + repoOwner + "'. Repo owner must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
            if (repoName.isBlank()) {
                System.err.println("ERROR: --repo-name is required for REVIEW mode");
                return false;
            }
            if (!repoName.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-name '" + repoName + "'. Repo name must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
            if (prNumber <= 0) {
                System.err.println("ERROR: --pr-number is required for REVIEW mode");
                return false;
            }
        }

        // Validate ISSUE_WRITER mode required fields
        if ("ISSUE_WRITER".equals(mode)) {
            if (githubToken.isBlank()) {
                System.err.println("ERROR: --github-token is required for ISSUE_WRITER mode");
                return false;
            }
            if (repoOwner.isBlank()) {
                System.err.println("ERROR: --repo-owner is required for ISSUE_WRITER mode");
                return false;
            }
            if (!repoOwner.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-owner '" + repoOwner + "'. Repo owner must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
            if (repoName.isBlank()) {
                System.err.println("ERROR: --repo-name is required for ISSUE_WRITER mode");
                return false;
            }
            if (!repoName.matches(REPO_COMPONENT_ALLOWLIST_REGEX)) {
                System.err.println("ERROR: Invalid --repo-name '" + repoName + "'. Repo name must match "
                        + REPO_COMPONENT_ALLOWLIST_REGEX);
                return false;
            }
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
                if (!mode.matches("^(ASK|CODE|ARCHITECT|LUTZ|SEARCH|REVIEW|ISSUE|ISSUE_DIAGNOSE|ISSUE_WRITER)$")) {
                    System.err.println(
                            "ERROR: Invalid mode: " + value
                                    + ". Must be ASK, CODE, ARCHITECT, LUTZ, SEARCH, REVIEW, ISSUE, ISSUE_DIAGNOSE, or ISSUE_WRITER");
                    return false;
                }
            }
            case "planner-model" -> plannerModel = value;
            case "scan-model" -> scanModel = value;
            case "code-model" -> codeModel = value;
            case "reasoning-level" -> reasoningLevel = value;
            case "reasoning-level-code" -> reasoningLevelCode = value;
            case "temperature" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --temperature requires a value");
                    return false;
                }
                try {
                    temperature = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid --temperature value: " + value);
                    return false;
                }
            }
            case "temperature-code" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --temperature-code requires a value");
                    return false;
                }
                try {
                    temperatureCode = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid --temperature-code value: " + value);
                    return false;
                }
            }
            case "token" -> authToken = value;
            case "auto-commit" -> autoCommit = true;
            case "auto-compress" -> autoCompress = true;
            case "pre-scan" -> preScan = true;
            case "github-token" -> githubToken = value;
            case "repo-owner" -> repoOwner = value;
            case "repo-name" -> repoName = value;
            case "issue-number" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --issue-number requires a value");
                    return false;
                }
                try {
                    issueNumber = Integer.parseInt(value);
                    if (issueNumber <= 0) {
                        System.err.println("ERROR: --issue-number must be a positive integer");
                        return false;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid --issue-number value: " + value);
                    return false;
                }
            }
            case "pr-number" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --pr-number requires a value");
                    return false;
                }
                try {
                    prNumber = Integer.parseInt(value);
                    if (prNumber <= 0) {
                        System.err.println("ERROR: --pr-number must be a positive integer");
                        return false;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid --pr-number value: " + value);
                    return false;
                }
            }
            case "max-issue-fix-attempts" -> {
                if (value.isBlank()) {
                    System.err.println("ERROR: --max-issue-fix-attempts requires a value");
                    return false;
                }
                try {
                    int attempts = Integer.parseInt(value);
                    if (attempts <= 0) {
                        System.err.println("ERROR: --max-issue-fix-attempts must be a positive integer");
                        return false;
                    }
                    maxIssueFixAttempts = attempts;
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: Invalid --max-issue-fix-attempts value: " + value);
                    return false;
                }
            }
            case "build-settings" -> buildSettings = value;
            case "issue-delivery" -> issueDelivery = value;
            case "skip-verification" -> skipVerification = true;
            default -> {
                System.err.println("ERROR: Unknown option: --" + key);
                return false;
            }
        }
        return true;
    }
}
