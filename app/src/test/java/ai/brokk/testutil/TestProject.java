package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IProject;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.mcp.McpConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lightweight IProject implementation for unit-testing Tree-sitter analyzers. */
public class TestProject implements IProject {
    private final Path root;
    private final Language language;
    private BuildAgent.BuildDetails buildDetails = BuildAgent.BuildDetails.EMPTY;
    private IProject.CodeAgentTestScope codeAgentTestScope = IProject.CodeAgentTestScope.WORKSPACE;
    private String styleGuide = "";
    private boolean hasGit = false;

    public TestProject(Path root) {
        this(root, Languages.NONE);
    }

    public TestProject(Path root, Language language) {
        assertTrue(Files.exists(root), "TestProject root does not exist: " + root);
        assertTrue(Files.isDirectory(root), "TestProject root is not a directory: " + root);
        this.root = root;
        this.language = language;
    }

    public void setBuildDetails(BuildAgent.BuildDetails buildDetails) {
        this.buildDetails = buildDetails;
    }

    @Override
    public BuildAgent.BuildDetails loadBuildDetails() {
        return this.buildDetails;
    }

    @Override
    public BuildAgent.BuildDetails awaitBuildDetails() {
        return this.buildDetails;
    }

    @Override
    public void setCodeAgentTestScope(IProject.CodeAgentTestScope scope) {
        this.codeAgentTestScope = scope;
    }

    @Override
    public IProject.CodeAgentTestScope getCodeAgentTestScope() {
        return this.codeAgentTestScope;
    }

    @Override
    public String getStyleGuide() {
        return styleGuide;
    }

    public void setStyleGuide(String styleGuide) {
        this.styleGuide = styleGuide;
    }

    public void setHasGit(boolean hasGit) {
        this.hasGit = hasGit;
    }

    @Override
    public boolean hasGit() {
        return hasGit;
    }

    @Override
    public McpConfig getMcpConfig() {
        return McpConfig.EMPTY;
    }

    @Override
    public void setMcpConfig(McpConfig config) {}

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    public static TestProject createTestProject(String subDir, Language lang) {
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        return Set.of(language);
    }

    @Override
    public Language getBuildLanguage() {
        return language;
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public Path getMasterRootPathForConfig() {
        return getRoot();
    }

    @Override
    public IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<ProjectFile> getAllFiles() {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> new ProjectFile(root, root.relativize(p)))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n", root, e.getMessage());
            // This can happen if the test resource dir doesn't exist, which is a test setup error.
            if (!(e instanceof NoSuchFileException)) {
                e.printStackTrace(System.err);
            }
            return Collections.emptySet();
        }
    }
}
