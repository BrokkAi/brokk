package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerProjectRootTest {

    @Test
    void resolveProjectRoot_returnsNearestGitAncestorForNestedDirectory() throws Exception {
        Path repoRoot = Files.createTempDirectory("mcp-root");
        Path gitDir = Files.createDirectory(repoRoot.resolve(".git"));
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(gitDir.resolve("config"), "[core]\n");
        Path nestedDir =
                Files.createDirectories(repoRoot.resolve("a").resolve("b").resolve("c"));

        Path resolved = BrokkExternalMcpServer.resolveProjectRoot(nestedDir);

        assertEquals(repoRoot.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveProjectRoot_returnsNearestGitAncestorForNestedFile() throws Exception {
        Path repoRoot = Files.createTempDirectory("mcp-root-file");
        Files.writeString(repoRoot.resolve(".git"), "gitdir: ../.git/worktrees/test\n");
        Path nestedFile = repoRoot.resolve("src").resolve("main").resolve("Example.java");
        Files.createDirectories(nestedFile.getParent());
        Files.writeString(nestedFile, "class Example {}\n");

        Path resolved = BrokkExternalMcpServer.resolveProjectRoot(nestedFile);

        assertEquals(repoRoot.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveProjectRoot_returnsResolvedPathWhenNoGitMarkerExists() throws Exception {
        Path directory = Files.createTempDirectory("mcp-no-git");

        Path resolved = BrokkExternalMcpServer.resolveProjectRoot(directory);

        assertEquals(directory.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveProjectRoot_ignoresInvalidGitAncestor() throws Exception {
        Path parent = Files.createTempDirectory("mcp-invalid-git-parent");
        Files.createDirectory(parent.resolve(".git"));
        Path directory = Files.createDirectories(parent.resolve("no-git"));

        Path resolved = BrokkExternalMcpServer.resolveProjectRoot(directory);

        assertEquals(directory.toAbsolutePath().normalize(), resolved);
    }
}
