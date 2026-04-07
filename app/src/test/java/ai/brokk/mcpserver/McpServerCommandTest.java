package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class McpServerCommandTest {

    @Test
    void toolSpecifications_containsSearchTools() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-test-specs");
        try (var project = new MainProject(tempDir)) {
            ContextManager cm = new ContextManager(project);
            List<McpServerFeatures.SyncToolSpecification> specs = new BrokkExternalMcpServer(cm).toolSpecifications();
            Set<String> names = specs.stream().map(s -> s.tool().name()).collect(Collectors.toSet());

            assertTrue(names.containsAll(Set.of(
                    "scan",
                    "searchSymbols",
                    "scanUsages",
                    "getFileSummaries",
                    "callSearchAgent",
                    "callCodeAgent",
                    "getClassSources",
                    "getMethodSources")));
        }
    }
}
