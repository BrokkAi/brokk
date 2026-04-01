package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerWorkspaceActivationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void startupWorkspaceRemainsDefaultUntilRuntimeActivation() throws Exception {
        Path repoA = createRepoWithMarker("repo-a", "alpha-marker");
        Path repoB = createRepoWithMarker("repo-b", "beta-marker");

        try (var project = new ai.brokk.project.MainProject(repoA);
                var cm = new ContextManager(project)) {
            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));
            var server = new BrokkExternalMcpServer(cm);

            var specs = specsByName(server.toolSpecifications());

            Map<String, Object> startupState = parseStatus(invoke(specs, "getActiveWorkspace", Map.of()));
            assertEquals(repoA.toString(), startupState.get("activeWorkspacePath"));
            assertEquals("startup", startupState.get("source"));

            Map<String, Object> switched =
                    parseStatus(invoke(specs, "activateWorkspace", Map.of("workspacePath", repoB.toString())));
            assertEquals(repoB.toString(), switched.get("activeWorkspacePath"));
            assertEquals("runtime_override", switched.get("source"));
            assertEquals(true, switched.get("changed"));
        }
    }

    @Test
    void activationCanSwitchBackAndForthWithoutRestart() throws Exception {
        Path repoA = createRepoWithMarker("repo-a-reroute", "switch-a");
        Path repoB = createRepoWithMarker("repo-b-reroute", "switch-b");

        try (var project = new ai.brokk.project.MainProject(repoA);
                var cm = new ContextManager(project)) {
            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));
            var server = new BrokkExternalMcpServer(cm);
            var specs = specsByName(server.toolSpecifications());

            parseStatus(invoke(specs, "activateWorkspace", Map.of("workspacePath", repoB.toString())));
            parseStatus(invoke(specs, "activateWorkspace", Map.of("workspacePath", repoA.toString())));

            Map<String, Object> state = parseStatus(invoke(specs, "getActiveWorkspace", Map.of()));
            assertEquals(repoA.toString(), state.get("activeWorkspacePath"));
            assertEquals("runtime_override", state.get("source"));
        }
    }

    @Test
    void activateWorkspaceValidatesPathInputs() throws Exception {
        Path repoA = createRepoWithMarker("repo-a-validation", "valid-a");
        Path missing = repoA.resolve("missing-dir");

        try (var project = new ai.brokk.project.MainProject(repoA);
                var cm = new ContextManager(project)) {
            cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));
            var server = new BrokkExternalMcpServer(cm);
            var specs = specsByName(server.toolSpecifications());

            McpSchema.CallToolResult relativeResult =
                    invokeRaw(specs, "activateWorkspace", Map.of("workspacePath", "relative/path"));
            assertTrue(Boolean.TRUE.equals(relativeResult.isError()));
            assertTrue(getText(relativeResult).contains("absolute path"));

            McpSchema.CallToolResult missingResult =
                    invokeRaw(specs, "activateWorkspace", Map.of("workspacePath", missing.toString()));
            assertTrue(Boolean.TRUE.equals(missingResult.isError()));
            assertTrue(getText(missingResult).contains("existing directory"));
        }
    }

    private static Path createRepoWithMarker(String prefix, String marker) throws Exception {
        Path repo = Files.createTempDirectory(prefix);
        Files.createDirectory(repo.resolve(".git"));
        Files.writeString(repo.resolve("marker.txt"), marker + "\n");
        return repo.toAbsolutePath().normalize();
    }

    private static Map<String, McpServerFeatures.SyncToolSpecification> specsByName(
            List<McpServerFeatures.SyncToolSpecification> specs) {
        return specs.stream().collect(Collectors.toMap(s -> s.tool().name(), Function.identity()));
    }

    private static String invoke(
            Map<String, McpServerFeatures.SyncToolSpecification> specs, String name, Map<String, Object> args) {
        return getText(invokeRaw(specs, name, args));
    }

    private static McpSchema.CallToolResult invokeRaw(
            Map<String, McpServerFeatures.SyncToolSpecification> specs, String name, Map<String, Object> args) {
        McpSchema.CallToolRequest request =
                McpSchema.CallToolRequest.builder().name(name).arguments(args).build();
        return specs.get(name).callHandler().apply(null, request);
    }

    private static String getText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> parseStatus(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<>() {});
    }
}
