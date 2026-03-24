package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsRouter settingsRouter;
    private ContextManager contextManager;
    private MainProject project;

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
