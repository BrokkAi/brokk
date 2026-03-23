package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.IssueProvider;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.project.IProject.CodeAgentTestScope;
import ai.brokk.project.MainProject;
import ai.brokk.util.ShellConfig;
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
    void handleGetSettings_returnsExpectedStructure() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        // Check top-level keys exist
        assertTrue(body.containsKey("buildDetails"), "Should contain buildDetails");
        assertTrue(body.containsKey("projectSettings"), "Should contain projectSettings");
        assertTrue(body.containsKey("shellConfig"), "Should contain shellConfig");
        assertTrue(body.containsKey("issueProvider"), "Should contain issueProvider");
        assertTrue(body.containsKey("dataRetentionPolicy"), "Should contain dataRetentionPolicy");

        // Verify buildDetails structure
        var buildDetails = (Map<String, Object>) body.get("buildDetails");
        assertTrue(buildDetails.containsKey("buildLintCommand"));
        assertTrue(buildDetails.containsKey("buildLintEnabled"));
        assertTrue(buildDetails.containsKey("testAllCommand"));
        assertTrue(buildDetails.containsKey("testAllEnabled"));
        assertTrue(buildDetails.containsKey("testSomeCommand"));
        assertTrue(buildDetails.containsKey("testSomeEnabled"));
        assertTrue(buildDetails.containsKey("exclusionPatterns"));
        assertTrue(buildDetails.containsKey("environmentVariables"));
        assertTrue(buildDetails.containsKey("modules"));

        // Verify projectSettings structure
        var projectSettings = (Map<String, Object>) body.get("projectSettings");
        assertTrue(projectSettings.containsKey("commitMessageFormat"));
        assertTrue(projectSettings.containsKey("codeAgentTestScope"));
        assertTrue(projectSettings.containsKey("runCommandTimeoutSeconds"));
        assertTrue(projectSettings.containsKey("testCommandTimeoutSeconds"));
        assertTrue(projectSettings.containsKey("autoUpdateLocalDependencies"));
        assertTrue(projectSettings.containsKey("autoUpdateGitDependencies"));

        // Verify shellConfig structure
        var shellConfig = (Map<String, Object>) body.get("shellConfig");
        assertTrue(shellConfig.containsKey("executable"));
        assertTrue(shellConfig.containsKey("args"));

        // Verify issueProvider structure
        var issueProvider = (Map<String, Object>) body.get("issueProvider");
        assertTrue(issueProvider.containsKey("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostBuild_updatesCommands() throws Exception {
        // Set initial build details
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

        // Update only the build command
        var body = Map.of(
                "buildLintCommand", "updated-build",
                "testAllEnabled", false);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/build", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var buildDetails = (Map<String, Object>) response.get("buildDetails");

        assertEquals("updated-build", buildDetails.get("buildLintCommand"));
        assertEquals(false, buildDetails.get("testAllEnabled"));
        // Verify unchanged fields are preserved
        assertEquals("initial-test", buildDetails.get("testAllCommand"));
        assertEquals("initial-testsome", buildDetails.get("testSomeCommand"));
        var patterns = (List<String>) buildDetails.get("exclusionPatterns");
        assertTrue(patterns.contains("node_modules"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostProject_updatesSettings() throws Exception {
        var body = Map.of(
                "commitMessageFormat", "feat: {{description}}",
                "codeAgentTestScope", "ALL",
                "runCommandTimeoutSeconds", 120,
                "autoUpdateLocalDependencies", true);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/project", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
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
    void handlePostShell_updatesConfig() throws Exception {
        var body = Map.of(
                "executable", "/bin/bash",
                "args", List.of("-lc"));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/shell", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
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
    void handlePostShell_missingExecutable_returns400() throws Exception {
        var body = Map.of("args", List.of("-c"));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/shell", body);
        settingsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostIssues_updatesGithubProvider() throws Exception {
        var body = Map.of(
                "type", "GITHUB",
                "config", Map.of(
                        "owner", "myorg",
                        "repo", "myrepo",
                        "host", "github.enterprise.com"));

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/issues", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var issueProvider = (Map<String, Object>) response.get("issueProvider");

        assertEquals("GITHUB", issueProvider.get("type"));
        var config = (Map<String, Object>) issueProvider.get("config");
        assertNotNull(config);
        assertEquals("myorg", config.get("owner"));
        assertEquals("myrepo", config.get("repo"));
        assertEquals("github.enterprise.com", config.get("host"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostIssues_updatesNoneProvider() throws Exception {
        // First set to GITHUB
        project.setIssuesProvider(IssueProvider.github("owner", "repo"));

        // Then set to NONE
        var body = Map.of("type", "NONE");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/issues", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        var issueProvider = (Map<String, Object>) response.get("issueProvider");

        assertEquals("NONE", issueProvider.get("type"));
    }

    @Test
    void handlePostIssues_invalidType_returns400() throws Exception {
        var body = Map.of("type", "INVALID");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/issues", body);
        settingsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlePostDataRetention_updatesPolicy() throws Exception {
        var body = Map.of("policy", "MINIMAL");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/data-retention", body);
        settingsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/settings");
        settingsRouter.handle(getExchange);

        Map<String, Object> response = MAPPER.readValue(getExchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("MINIMAL", response.get("dataRetentionPolicy"));
    }

    @Test
    void handlePostDataRetention_invalidPolicy_returns400() throws Exception {
        var body = Map.of("policy", "INVALID");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/data-retention", body);
        settingsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    void handlePostDataRetention_unsetPolicy_returns400() throws Exception {
        var body = Map.of("policy", "UNSET");

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/settings/data-retention", body);
        settingsRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
    }

    @Test
    void unknownPath_returns404() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/settings/unknown");
        settingsRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
    }

    @Test
    void wrongMethod_returns405() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/settings");
        settingsRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }
}
