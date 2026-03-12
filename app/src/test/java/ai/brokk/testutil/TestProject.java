package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.mcpclient.McpConfig;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/** Lightweight IProject implementation for unit-testing Tree-sitter analyzers. */
public class TestProject implements IProject {
    private final Path root;
    private Language language = Languages.NONE;

    private long runCommandTimeoutSeconds = Environment.DEFAULT_TIMEOUT.toSeconds();
    private long testCommandTimeoutSeconds = Environment.DEFAULT_TIMEOUT.toSeconds();

    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture =
            CompletableFuture.completedFuture(BuildAgent.BuildDetails.EMPTY);
    private BuildAgent.BuildDetails buildDetails = BuildAgent.BuildDetails.EMPTY;
    private boolean buildDetailsExplicitlySet = false;

    private IProject.CodeAgentTestScope codeAgentTestScope = IProject.CodeAgentTestScope.WORKSPACE;
    private String styleGuide = "";
    private Set<String> exclusionPatterns = Set.of();
    private boolean gitConfigDeclined = false;
    private @Nullable String jdk;
    private @Nullable IGitRepo repo;
    private boolean repoExplicitlySetToNull = false;
    private @Nullable Supplier<Set<ProjectFile>> allFilesSupplier;
    private @Nullable Set<ProjectFile> allFiles;
    private Set<ProjectFile> allOnDiskDependencies = Set.of();
    private Set<IProject.Dependency> liveDependencies = Set.of();
    private @Nullable Predicate<Path> gitignoredPredicate;
    private MainProject.DataRetentionPolicy dataRetentionPolicy = MainProject.DataRetentionPolicy.MINIMAL;

    public TestProject(Path root) {
        this(root, Languages.NONE);
    }

    public TestProject(Path root, Language language) {
        assertTrue(Files.exists(root), "TestProject root does not exist: " + root);
        assertTrue(Files.isDirectory(root), "TestProject root is not a directory: " + root);
        this.root = root;
        this.language = language;
    }

    @Override
    public Language getLanguageHandle() {
        return language;
    }

    public void setBuildDetails(BuildAgent.BuildDetails buildDetails) {
        this.buildDetails = buildDetails;
        this.buildDetailsExplicitlySet = true;

        if (!detailsFuture.isDone()) {
            detailsFuture.complete(buildDetails);
            return;
        }

        detailsFuture = CompletableFuture.completedFuture(buildDetails);
    }

    @Override
    public boolean hasBuildDetails() {
        return buildDetailsExplicitlySet;
    }

    @Override
    public CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return detailsFuture;
    }

    @Override
    public void saveBuildDetails(BuildAgent.BuildDetails details) {
        setBuildDetails(details);
    }

    @Override
    public Optional<BuildAgent.BuildDetails> loadBuildDetails() {
        return Optional.of(this.buildDetails);
    }

    @Override
    public BuildAgent.BuildDetails awaitBuildDetails() {
        return detailsFuture.join();
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

    public void setExclusionPatterns(Set<String> patterns) {
        this.exclusionPatterns = patterns;
    }

    public TestProject withAllFilesSupplier(Supplier<Set<ProjectFile>> filesSupplier) {
        this.allFilesSupplier = filesSupplier;
        this.allFiles = null;
        return this;
    }

    public TestProject withAllFiles(Set<ProjectFile> files) {
        this.allFiles = Set.copyOf(files);
        this.allFilesSupplier = null;
        return this;
    }

    public TestProject withDependencies(
            Set<ProjectFile> allOnDiskDependencies, Set<IProject.Dependency> liveDependencies) {
        this.allOnDiskDependencies = Set.copyOf(allOnDiskDependencies);
        this.liveDependencies = Set.copyOf(liveDependencies);
        return this;
    }

    public TestProject withGitignoredPredicate(Predicate<Path> gitignoredPredicate) {
        this.gitignoredPredicate = gitignoredPredicate;
        return this;
    }

    @Override
    public Set<String> getExclusionPatterns() {
        return exclusionPatterns;
    }

    @Override
    public boolean hasGit() {
        try {
            return getRepo() != null;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    @Override
    public @Nullable IGitRepo getRepo() {
        if (repo == null) {
            if (repoExplicitlySetToNull) {
                return null;
            }
            throw new UnsupportedOperationException("No repository configured for this TestProject");
        }
        return repo;
    }

    @Override
    public void setRepo(IGitRepo repo) {
        this.repo = repo;
        this.repoExplicitlySetToNull = false;
    }

    public TestProject withRepo(IGitRepo repo) {
        setRepo(repo);
        return this;
    }

    public TestProject withoutRepo() {
        this.repo = null;
        this.repoExplicitlySetToNull = true;
        return this;
    }

    @Override
    public boolean isGitConfigDeclined() {
        return gitConfigDeclined;
    }

    @Override
    public void setGitConfigDeclined(boolean declined) {
        this.gitConfigDeclined = declined;
    }

    @Override
    public @Nullable String getJdk() {
        return jdk;
    }

    @Override
    public void setJdk(@Nullable String jdkHome) {
        this.jdk = jdkHome;
    }

    @Override
    public boolean hasJdkOverride() {
        return jdk != null;
    }

    public TestProject withJdk(@Nullable String jdkHome) {
        setJdk(jdkHome);
        return this;
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

    private Set<Language> analyzerLanguages = Set.of();

    @Override
    public Set<Language> getAnalyzerLanguages() {
        return analyzerLanguages.isEmpty() ? Set.of(language) : analyzerLanguages;
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        this.analyzerLanguages = Set.copyOf(languages);
    }

    @Override
    public long getRunCommandTimeoutSeconds() {
        return runCommandTimeoutSeconds;
    }

    public void setRunCommandTimeoutSeconds(long seconds) {
        this.runCommandTimeoutSeconds = seconds;
    }

    @Override
    public long getTestCommandTimeoutSeconds() {
        return testCommandTimeoutSeconds;
    }

    public void setTestCommandTimeoutSeconds(long seconds) {
        this.testCommandTimeoutSeconds = seconds;
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
    public Set<ProjectFile> getAllFiles() {
        if (allFilesSupplier != null) {
            return allFilesSupplier.get();
        }
        if (allFiles != null) {
            return allFiles;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> Files.isRegularFile(p))
                    .map(p -> new ProjectFile(root, root.relativize(p)))
                    .collect(Collectors.toSet());
        } catch (IOException | UncheckedIOException e) {
            System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n", root, e.getMessage());
            // NoSuchFileException can occur directly (from Files.walk) or wrapped in UncheckedIOException (from stream
            // iteration)
            Throwable cause = e instanceof UncheckedIOException ? e.getCause() : e;
            if (!(cause instanceof NoSuchFileException)) {
                e.printStackTrace(System.err);
            }
            return Collections.emptySet();
        }
    }

    @Override
    public Set<ProjectFile> getAllOnDiskDependencies() {
        return allOnDiskDependencies;
    }

    @Override
    public Set<IProject.Dependency> getLiveDependencies() {
        return liveDependencies;
    }

    @Override
    public boolean isGitignored(Path relPath) {
        return gitignoredPredicate != null && gitignoredPredicate.test(relPath);
    }

    @Override
    public MainProject.DataRetentionPolicy getDataRetentionPolicy() {
        return dataRetentionPolicy;
    }

    /**
     * Returns true if this test project contains no analyzable source files.
     */
    public boolean isEmptyProject() {
        Set<String> analyzableExtensions = Languages.ALL_LANGUAGES.stream()
                .filter(lang -> lang != Languages.NONE)
                .flatMap(lang -> lang.getExtensions().stream())
                .collect(Collectors.toSet());

        return getAllFiles().stream().map(ProjectFile::extension).noneMatch(analyzableExtensions::contains);
    }
}
