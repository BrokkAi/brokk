package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsRouter settingsRouter;
    private ContextManager contextManager;
    private MainProject project;

    private static final BuildDetails INFERRED_DETAILS = new BuildDetails(
            "gradlew build",
            true,
            "gradlew test",
            true,
            "gradlew test --tests {{items}}",
            true,
            Set.of("build"),
            Map.of(),
            null,
            "",
            List.of());

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        project = MainProject.forTests(tempDir);
        contextManager = new ContextManager(project);
        settingsRouter = new SettingsRouter(contextManager);
    }

    @AfterEach
    void tearDown() {
        if (contextManager != null) {
            contextManager.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGetSettings_returnsAllSettings() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertTrue(body.containsKey("buildDetails"));
        assertTrue(body.containsKey("projectSettings"));
        assertTrue(body.containsKey("shellConfig"));
        assertTrue(body.containsKey("issueProvider"));
        assertTrue(body.containsKey("dataRetentionPolicy"));
        assertTrue(body.containsKey("analyzerLanguages"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesBuildDetails() throws Exception {
        var initialDetails = new BuildDetails(
                "initial-build",
                true,
                "initial-test",
                true,
                "initial-testsome",
                false,
                Set.of("node_modules"),
                Map.of(),
                null,
                "",
                List.of());
        project.saveBuildDetails(initialDetails);

        var body = Map.of("buildDetails", Map.of("buildLintCommand", "updated-build", "testAllEnabled", false));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var buildDetails = (Map<String, Object>) response.get("buildDetails");

        assertEquals("updated-build", buildDetails.get("buildLintCommand"));
        assertEquals(false, buildDetails.get("testAllEnabled"));
        assertEquals("initial-test", buildDetails.get("testAllCommand"));
        assertEquals("initial-testsome", buildDetails.get("testSomeCommand"));
        var patterns = (List<String>) buildDetails.get("exclusionPatterns");
        assertTrue(patterns.contains("node_modules"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesProjectSettings() throws Exception {
        var body = Map.of(
                "projectSettings",
                Map.of(
                        "commitMessageFormat",
                        "feat: {{description}}",
                        "codeAgentTestScope",
                        "ALL",
                        "runCommandTimeoutSeconds",
                        120,
                        "autoUpdateLocalDependencies",
                        true));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var projectSettings = (Map<String, Object>) response.get("projectSettings");

        assertEquals("feat: {{description}}", projectSettings.get("commitMessageFormat"));
        assertEquals("ALL", projectSettings.get("codeAgentTestScope"));
        assertEquals(120, ((Number) projectSettings.get("runCommandTimeoutSeconds")).intValue());
        assertEquals(true, projectSettings.get("autoUpdateLocalDependencies"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesShellConfig() throws Exception {
        var body = Map.of("shellConfig", Map.of("executable", "/bin/bash", "args", List.of("-lc")));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var shellConfig = (Map<String, Object>) response.get("shellConfig");

        assertEquals("/bin/bash", shellConfig.get("executable"));
        var args = (List<String>) shellConfig.get("args");
        assertEquals(1, args.size());
        assertEquals("-lc", args.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesIssueProvider() throws Exception {
        var body = Map.of(
                "issueProvider",
                Map.of(
                        "type",
                        "GITHUB",
                        "config",
                        Map.of("owner", "myorg", "repo", "myrepo", "host", "github.enterprise.com")));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var issueProvider = (Map<String, Object>) response.get("issueProvider");

        assertEquals("GITHUB", issueProvider.get("type"));
        var config = (Map<String, Object>) issueProvider.get("config");
        assertNotNull(config);
        assertEquals("myorg", config.get("owner"));
        assertEquals("myrepo", config.get("repo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesDataRetention() throws Exception {
        var body = Map.of("dataRetentionPolicy", "MINIMAL");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("MINIMAL", response.get("dataRetentionPolicy"));
    }

    @Test
    void handlePostSettings_invalidDataRetention_returns400() throws Exception {
        var body = Map.of("dataRetentionPolicy", "INVALID");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesLanguages() throws Exception {
        var body = Map.of("analyzerLanguages", Map.of("languages", List.of("JAVA", "PYTHON")));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var analyzerLanguages = (Map<String, Object>) response.get("analyzerLanguages");
        var configured = (List<String>) analyzerLanguages.get("configured");

        assertTrue(configured.contains("JAVA"));
        assertTrue(configured.contains("PYTHON"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostSettings_updatesAllSectionsAtOnce() throws Exception {
        var body = Map.of(
                "buildDetails", Map.of("buildLintCommand", "make lint"),
                "projectSettings", Map.of("commitMessageFormat", "fix: {msg}"),
                "shellConfig", Map.of("executable", "/bin/zsh", "args", List.of("-c")),
                "dataRetentionPolicy", "MINIMAL",
                "analyzerLanguages", Map.of("languages", List.of("JAVA")));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});

        var buildDetails = (Map<String, Object>) response.get("buildDetails");
        assertEquals("make lint", buildDetails.get("buildLintCommand"));

        var projectSettings = (Map<String, Object>) response.get("projectSettings");
        assertEquals("fix: {msg}", projectSettings.get("commitMessageFormat"));

        var shellConfig = (Map<String, Object>) response.get("shellConfig");
        assertEquals("/bin/zsh", shellConfig.get("executable"));

        assertEquals("MINIMAL", response.get("dataRetentionPolicy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostInferBuildDetails_returnsExistingWhenSavedDetailsExist() throws Exception {
        var existing = new BuildDetails(
                "saved-build",
                true,
                "saved-test",
                true,
                "saved-test-some",
                true,
                Set.of("node_modules"),
                Map.of(),
                null,
                "",
                List.of());
        project.saveBuildDetails(existing);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of());
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> response = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("existing", response.get("status"));

        var buildDetails = (Map<String, Object>) response.get("buildDetails");
        assertEquals("saved-build", buildDetails.get("buildLintCommand"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostInferBuildDetails_forceBypassesExistingReuse() throws Exception {
        try (var forceProject = new MainProject(project.getRoot())) {
            forceProject.saveBuildDetails(new BuildDetails(
                    "saved-build",
                    true,
                    "saved-test",
                    true,
                    "saved-test-some",
                    true,
                    Set.of("node_modules"),
                    Map.of(),
                    null,
                    "",
                    List.of()));

            var calls = new AtomicInteger();
            try (var forceContextManager = new ContextManager(forceProject) {
                @Override
                protected BuildDetails runBuildDetailsInference() {
                    calls.incrementAndGet();
                    return INFERRED_DETAILS;
                }
            }) {
                var forceRouter = new SettingsRouter(forceContextManager);

                var exchange =
                        TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of("force", true));
                forceRouter.handle(exchange);

                assertEquals(200, exchange.responseCode());
                Map<String, Object> response = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
                assertEquals("inferred", response.get("status"));
                assertEquals(1, calls.get());

                var buildDetails = (Map<String, Object>) response.get("buildDetails");
                assertEquals("gradlew build", buildDetails.get("buildLintCommand"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostInferBuildDetails_returnsEmptyOnRepeatedNonForcedCallsAfterEmptyInference() throws Exception {
        try (var emptyProject = new MainProject(project.getRoot());
                var emptyContextManager = new ContextManager(emptyProject) {
                    @Override
                    protected BuildDetails runBuildDetailsInference() {
                        return BuildDetails.EMPTY;
                    }
                }) {
            var emptyRouter = new SettingsRouter(emptyContextManager);

            var firstExchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of());
            emptyRouter.handle(firstExchange);
            Map<String, Object> first = MAPPER.readValue(firstExchange.responseBodyBytes(), new TypeReference<>() {});
            assertEquals("empty", first.get("status"));

            var secondExchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of());
            emptyRouter.handle(secondExchange);
            Map<String, Object> second = MAPPER.readValue(secondExchange.responseBodyBytes(), new TypeReference<>() {});
            assertEquals("empty", second.get("status"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGetSettings_waitsForInFlightInference() throws Exception {
        var waitingRoot = java.nio.file.Files.createTempDirectory("settings-router-waits-for-inference");
        try (var waitingProject = new MainProject(waitingRoot)) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var waitingContextManager = new ContextManager(waitingProject) {
                @Override
                protected BuildDetails runBuildDetailsInference() throws Exception {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return INFERRED_DETAILS;
                }
            }) {
                var waitingRouter = new SettingsRouter(waitingContextManager);
                waitingContextManager.createHeadless(true, new ai.brokk.testutil.NoOpConsoleIO());
                assertTrue(started.await(5, TimeUnit.SECONDS));

                var getFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        var exchange = TestHttpExchange.request("GET", "/v1/settings");
                        waitingRouter.handle(exchange);
                        return exchange;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                Thread.sleep(200);
                assertFalse(getFuture.isDone(), "GET /v1/settings should wait for in-flight inference");

                release.countDown();
                var exchange = getFuture.get(5, TimeUnit.SECONDS);
                Map<String, Object> response = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
                var buildDetails = (Map<String, Object>) response.get("buildDetails");
                assertEquals("gradlew build", buildDetails.get("buildLintCommand"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGetSettings_afterFailedInferenceRetriesAndReturnsEmpty() throws Exception {
        var failedRoot = java.nio.file.Files.createTempDirectory("settings-router-retries-after-failure");
        try (var failedProject = new MainProject(failedRoot)) {
            var calls = new AtomicInteger();
            try (var failedContextManager = new ContextManager(failedProject) {
                @Override
                protected BuildDetails runBuildDetailsInference() {
                    if (calls.getAndIncrement() == 0) {
                        throw new RuntimeException("boom");
                    }
                    return BuildDetails.EMPTY;
                }
            }) {
                var failedRouter = new SettingsRouter(failedContextManager);

                var postExchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of());
                failedRouter.handle(postExchange);
                Map<String, Object> post = MAPPER.readValue(postExchange.responseBodyBytes(), new TypeReference<>() {});
                assertEquals("failed", post.get("status"));

                var getExchange = TestHttpExchange.request("GET", "/v1/settings");
                failedRouter.handle(getExchange);
                Map<String, Object> get = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
                var buildDetails = (Map<String, Object>) get.get("buildDetails");
                assertEquals(List.of(), buildDetails.get("exclusionPatterns"));
                assertEquals(2, calls.get(), "GET /v1/settings should retry inference after failure");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostInferBuildDetails_forceIsThrottledWhenRepeated() throws Exception {
        try (var throttledProject = new MainProject(project.getRoot())) {
            throttledProject.saveBuildDetails(INFERRED_DETAILS);
            var calls = new AtomicInteger();
            try (var throttledContextManager = new ContextManager(throttledProject) {
                @Override
                protected BuildDetails runBuildDetailsInference() {
                    calls.incrementAndGet();
                    return INFERRED_DETAILS;
                }
            }) {
                var throttledRouter = new SettingsRouter(throttledContextManager);

                var firstExchange =
                        TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of("force", true));
                throttledRouter.handle(firstExchange);
                Map<String, Object> first =
                        MAPPER.readValue(firstExchange.responseBodyBytes(), new TypeReference<>() {});
                assertEquals("inferred", first.get("status"));

                var secondExchange =
                        TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of("force", true));
                throttledRouter.handle(secondExchange);
                Map<String, Object> second =
                        MAPPER.readValue(secondExchange.responseBodyBytes(), new TypeReference<>() {});
                assertEquals("failed", second.get("status"));
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostInferBuildDetails_timesOutBoundedly() throws Exception {
        try (var timeoutProject = new MainProject(project.getRoot());
                var timeoutContextManager = new ContextManager(timeoutProject) {
                    @Override
                    public java.util.concurrent.CompletableFuture<ContextManager.BuildDetailsInferenceResult>
                            inferBuildDetails(boolean force) {
                        return new java.util.concurrent.CompletableFuture<>();
                    }
                }) {
            var timeoutRouter = new SettingsRouter(timeoutContextManager, 1);

            var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/infer-build-details", Map.of());
            timeoutRouter.handle(exchange);

            assertEquals(200, exchange.responseCode());
            Map<String, Object> response = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
            assertEquals("failed", response.get("status"));
            assertEquals("Timed out waiting for build-details inference", response.get("diagnostics"));
        }
    }

    @Test
    void unknownPath_returns404() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/settings/unknown");
        settingsRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
    }

    @Test
    void wrongMethod_returns405() throws Exception {
        var exchange = TestHttpExchange.request("DELETE", "/v1/settings");
        settingsRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGetSettings_includesAnalyzerLanguages() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertTrue(body.containsKey("analyzerLanguages"));

        var analyzerLanguages = (Map<String, Object>) body.get("analyzerLanguages");
        assertTrue(analyzerLanguages.containsKey("configured"));
        assertTrue(analyzerLanguages.containsKey("detected"));
        assertTrue(analyzerLanguages.containsKey("available"));

        var available = (List<Map<String, String>>) analyzerLanguages.get("available");
        assertNotNull(available);
        assertTrue(available.size() > 0);

        var firstLang = available.get(0);
        assertTrue(firstLang.containsKey("name"));
        assertTrue(firstLang.containsKey("internalName"));
    }
}
