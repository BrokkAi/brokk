package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.testutil.TestProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitDistanceRelatedFilesTest {

    private static final Logger logger = LoggerFactory.getLogger(GitDistanceRelatedFilesTest.class);

    private static JavaAnalyzer analyzer;

    private static TestProject testProject;

    private static Path testPath;

    @BeforeAll
    public static void setup() throws Exception {
        var testResourcePath = testResourcePath("testcode-git-rank-java");
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        testPath = GitDistanceTestSuite.setupGitHistory(testResourcePath);

        var testRepo = new GitRepo(testPath);
        testProject = new TestProject(testPath, Languages.JAVA).withRepo(testRepo);
        logger.debug("Setting up analyzer with test code from {}", testPath);
        analyzer = new JavaAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        GitDistanceTestSuite.teardownGitRepository(testPath);
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void testPMIWithSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");
        var testFile = new ProjectFile(testProject.getRoot(), "UserService.java");
        var seedWeights = Map.of(testFile, 1.0);

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10);
        assertFalse(results.isEmpty(), "PMI should return results");

        var user = results.stream()
                .filter(r -> r.file().getFileName().equals("User.java"))
                .findFirst();
        assertTrue(user.isPresent(), results.toString());

        var notification = results.stream()
                .filter(r -> r.file().getFileName().equals("NotificationService.java"))
                .findFirst();

        notification.ifPresent(
                fileRelevance -> assertTrue(user.get().score() > fileRelevance.score(), results.toString()));
    }

    @Test
    public void testPMISeedsNotInResults() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var userService = new ProjectFile(testProject.getRoot(), "UserService.java");
        var user = new ProjectFile(testProject.getRoot(), "User.java");
        var seedWeights = Map.of(userService, 1.0, user, 0.5);

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10);
        assertFalse(results.isEmpty(), "Should return non-seed related files");

        var resultFiles = results.stream().map(r -> r.file()).toList();
        assertFalse(resultFiles.contains(userService), "UserService (seed) should not appear in results");
        assertFalse(resultFiles.contains(user), "User (seed) should not appear in results");
    }

    @Test
    public void testPMINoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), Map.of(), 10);
        assertTrue(results.isEmpty(), "Empty seed weights should yield empty PMI results");
    }

    @Test
    public void testPMIReversed() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");
        var testFile = new ProjectFile(testProject.getRoot(), "UserService.java");
        var seedWeights = Map.of(testFile, 1.0);

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10);
        assertFalse(results.isEmpty(), "PMI should return results");
    }

    @Test
    public void pmiCanonicalizesRenames() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-pmi-rename-test-");
        try {
            try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.save();

                write(
                        tempDir.resolve("A.java"),
                        """
                        public class A {
                            public String id() { return \"a\"; }
                        }
                        """);
                write(
                        tempDir.resolve("UserService.java"),
                        """
                        public class UserService {
                            void useA() { new A().id(); }
                        }
                        """);
                git.add()
                        .addFilepattern("A.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Initial A and UserService")
                        .setSign(false)
                        .call();

                write(tempDir.resolve("A.java"), Files.readString(tempDir.resolve("A.java")) + "\n// tweak\n");
                write(
                        tempDir.resolve("UserService.java"),
                        Files.readString(tempDir.resolve("UserService.java")) + "\n// tweak\n");
                git.add()
                        .addFilepattern("A.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Co-change A and UserService")
                        .setSign(false)
                        .call();

                var src = tempDir.resolve("A.java");
                var dst = tempDir.resolve("Account.java");
                Files.move(src, dst);
                git.rm().addFilepattern("A.java").call();
                git.add().addFilepattern("Account.java").call();
                git.commit().setMessage("Rename A to Account").setSign(false).call();

                write(dst, Files.readString(dst) + "\n// more changes after rename\n");
                write(
                        tempDir.resolve("UserService.java"),
                        Files.readString(tempDir.resolve("UserService.java")) + "\n// integrate Account\n");
                git.add()
                        .addFilepattern("Account.java")
                        .addFilepattern("UserService.java")
                        .call();
                git.commit()
                        .setMessage("Co-change Account and UserService")
                        .setSign(false)
                        .call();
            }

            try (var repo = new GitRepo(tempDir)) {
                var newPf = new ProjectFile(tempDir, Path.of("Account.java"));
                var seed = new ProjectFile(tempDir, Path.of("UserService.java"));
                var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 10);
                assertFalse(results.isEmpty(), "PMI should return results");

                var anyOld =
                        results.stream().anyMatch(r -> r.file().getFileName().equals("A.java"));
                assertFalse(anyOld, "Old path A.java must not appear in PMI results after canonicalization");

                var account =
                        results.stream().filter(r -> r.file().equals(newPf)).findFirst();
                assertTrue(account.isPresent(), "Account.java should appear in PMI results");
            }
        } finally {
            GitDistanceTestSuite.teardownGitRepository(tempDir);
            if (Files.exists(tempDir)) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    @Test
    public void gitDistanceSortsNearTiesByPathName() throws Exception {
        var tempDir = Files.createTempDirectory("brokk-pmi-tie-order-");
        try {
            try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.save();

                write(tempDir.resolve("seed.txt"), "seed");
                write(
                        tempDir.resolve("Anthropic_Agent_With_Prompt_Caching.cs"),
                        "class AnthropicAgentWithPromptCaching {}");
                write(tempDir.resolve("AutoGen.Anthropic.Sample.csproj"), "<Project />");
                write(tempDir.resolve("Create_Anthropic_Agent.cs"), "class CreateAnthropicAgent {}");
                git.add()
                        .addFilepattern("seed.txt")
                        .addFilepattern("Anthropic_Agent_With_Prompt_Caching.cs")
                        .addFilepattern("AutoGen.Anthropic.Sample.csproj")
                        .addFilepattern("Create_Anthropic_Agent.cs")
                        .call();
                git.commit().setMessage("single tied change").setSign(false).call();
            }

            try (var repo = new GitRepo(tempDir)) {
                var seed = new ProjectFile(tempDir, Path.of("seed.txt"));
                var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 10);
                var topNames = results.stream()
                        .map(r -> r.file().getFileName().toString())
                        .limit(3)
                        .toList();
                assertEquals(
                        List.of(
                                "Anthropic_Agent_With_Prompt_Caching.cs",
                                "AutoGen.Anthropic.Sample.csproj",
                                "Create_Anthropic_Agent.cs"),
                        topNames);
            }
        } finally {
            GitDistanceTestSuite.teardownGitRepository(tempDir);
            if (Files.exists(tempDir)) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    @Test
    public void fixtureRenameRankingPrefersCanonicalizedTargetOverDistractor() throws Exception {
        try (var fixture = createRenameFixture();
                var repo = new GitRepo(fixture.root())) {
            var seed = projectFile(fixture.root(), "src/main/java/org/example/GitLibrary.java");
            var rankedPaths = rankedPaths(repo, seed, 10);

            var targetIndex = rankedPaths.indexOf("src/test/java/org/example/JavaLibraryTest.java");
            var distractorIndex = rankedPaths.indexOf("src/main/java/org/example/DmpLibrary.java");

            assertTrue(targetIndex >= 0, rankedPaths.toString());
            assertTrue(distractorIndex >= 0, rankedPaths.toString());
            assertTrue(targetIndex < distractorIndex, rankedPaths.toString());
        }
    }

    @Test
    public void fixtureCanonicalizerDoesNotFollowLowSimilarityMove() throws Exception {
        try (var fixture = createAutogenFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldCsproj = projectFile(fixture.root(), "samples/Prompt_Caching_Sample.csproj");

            var canonical = canonicalizer.canonicalize(fixture.commitId("csproj-replaced"), oldCsproj);

            assertEquals(
                    oldCsproj.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureGitDistanceRanksDocTargetAheadOfFrequentDistractor() throws Exception {
        try (var fixture = createAutogenFixture();
                var repo = new GitRepo(fixture.root())) {
            var seed = projectFile(fixture.root(), "samples/Checker.cs");
            var rankedPaths = rankedPaths(repo, seed, 25);

            var docfxIndex = rankedPaths.indexOf("docs/docfx.json");
            var metadataIndex = rankedPaths.indexOf("src/contracts/AgentMetadata.cs");

            assertTrue(docfxIndex >= 0, rankedPaths.toString());
            assertTrue(metadataIndex >= 0, rankedPaths.toString());
            assertTrue(docfxIndex < metadataIndex, rankedPaths.toString());
        }
    }

    @Test
    public void fixtureCanonicalizerDoesNotTreatInstallationDocAddDeleteAsContinuation() throws Exception {
        try (var fixture = createAutogenFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldInstallation = projectFile(fixture.root(), "docs/legacy/installation.md");

            var canonical = canonicalizer.canonicalize(fixture.commitId("installation-replaced"), oldInstallation);

            assertEquals(
                    oldInstallation.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureCanonicalizerDoesNotTreatDifferencesDocAddDeleteAsContinuation() throws Exception {
        try (var fixture = createAutogenFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldDifferences = projectFile(fixture.root(), "docs/legacy/differences.md");

            var canonical = canonicalizer.canonicalize(fixture.commitId("differences-replaced"), oldDifferences);

            assertEquals(
                    oldDifferences.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureCanonicalizerDoesNotTreatRuntimeTestAddDeleteAsContinuation() throws Exception {
        try (var fixture = createAutogenFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldRuntimeTest = projectFile(fixture.root(), "tests/AgentRuntimeTests.cs");

            var canonical = canonicalizer.canonicalize(fixture.commitId("runtime-replaced"), oldRuntimeTest);

            assertEquals(
                    oldRuntimeTest.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureGitDistanceSkipsDeletedContinuationCandidates() throws Exception {
        try (var fixture = createVectorFixture();
                var repo = new GitRepo(fixture.root())) {
            var seed = projectFile(fixture.root(), "src/query_result_struct.cpp");
            var ranked = assertDoesNotThrow(() -> GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25));
            assertNotNull(ranked);
        }
    }

    @Test
    public void fixtureCanonicalizerRequiresPathReplacementToFollowRename() throws Exception {
        try (var fixture = createVectorFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldFactory = projectFile(fixture.root(), "src/algorithms/brute_force_factory.cpp");

            var canonical = canonicalizer.canonicalize(fixture.commitId("brute-split"), oldFactory);

            assertEquals(
                    oldFactory.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureCanonicalizerFollowsAcceptedRename() throws Exception {
        try (var fixture = createVectorFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldFactory = projectFile(fixture.root(), "src/algorithms/hnsw_factory.cpp");

            var canonical = canonicalizer.canonicalize(fixture.commitId("hnsw-rename"), oldFactory);

            assertEquals(
                    Path.of("src/index_factories/hnsw_factory.cpp"),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void fixtureGitDistanceDoesNotSurfaceDefinitionsWithoutAcceptedRename() throws Exception {
        try (var fixture = createVectorFixture();
                var repo = new GitRepo(fixture.root())) {
            var seed = projectFile(fixture.root(), "src/query_result_struct.cpp");
            var rankedPaths = rankedPaths(repo, seed, 50);

            var definitionsIndex = rankedPaths.indexOf("tests/benchmark/spaces_class_definitions.h");
            assertTrue(definitionsIndex < 0, rankedPaths.toString());
        }
    }

    @Test
    public void fixtureCanonicalizerDoesNotMapGenericInitAcrossTrees() throws Exception {
        try (var fixture = createAstrapyFixture();
                var repo = new GitRepo(fixture.root())) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var idiomaticInit = projectFile(fixture.root(), "pkg/idiomatic/__init__.py");

            var canonical = canonicalizer.canonicalize(fixture.commitId("idiomatic-only"), idiomaticInit);

            assertEquals(
                    idiomaticInit.getRelPath(),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
            assertNotEquals(projectFile(fixture.root(), "pkg/ops/__init__.py"), canonical);
            assertNotEquals(projectFile(fixture.root(), "tests/core/__init__.py"), canonical);
        }
    }

    private static GitDistanceTestSuite.FixtureRepo createRenameFixture() throws Exception {
        return fixtureRepo("testcode-git-rank-rename", history -> {
            history.delete("src/main/java/org/example/DmpLibrary.java");
            history.delete("src/test/java/org/example/JavaLibraryTest.java");
            history.write(
                    "src/test/java/org/example/Java_Library_Test.java",
                    """
                    package org.example;
                    public class JavaLibraryTest {
                        int value() { return 1; }
                    }
                    """);
            history.commit("initial", "Initial library linkage");

            history.append("src/main/java/org/example/GitLibrary.java", "\n// strengthen old target\n");
            history.append("src/test/java/org/example/Java_Library_Test.java", "\n// strengthen old target\n");
            history.commit("paired-old", "Strengthen old target linkage");

            history.rename(
                    "src/test/java/org/example/Java_Library_Test.java",
                    "src/test/java/org/example/JavaLibraryTest.java");
            history.commit("rename", "Rename library test file");

            history.append("src/main/java/org/example/GitLibrary.java", "\n// strengthen renamed target\n");
            history.append("src/test/java/org/example/JavaLibraryTest.java", "\n// strengthen renamed target\n");
            history.commit("paired-new", "Strengthen renamed target linkage");

            history.write(
                    "src/main/java/org/example/DmpLibrary.java",
                    """
                    package org.example;
                    public class DmpLibrary {
                        int value() { return 2; }
                    }
                    """);
            history.commit("distractor-alone", "Add distractor library");

            history.append("src/main/java/org/example/GitLibrary.java", "\n// weak distractor link\n");
            history.append("src/main/java/org/example/DmpLibrary.java", "\n// weak distractor link\n");
            history.commit("distractor-pair", "Weak distractor tie");
        });
    }

    private static GitDistanceTestSuite.FixtureRepo createAutogenFixture() throws Exception {
        return fixtureRepo("testcode-git-rank-autogen", history -> {
            history.delete("docs/docfx.json");
            history.delete("src/contracts/AgentMetadata.cs");
            history.delete("docs/user-guide/installation.md");
            history.delete("docs/user-guide/differences.md");
            history.delete("tests/InProcessRuntimeTests.cs");
            history.delete("samples/PromptCachingSample.csproj");

            history.write(
                    "docs/docfx.json",
                    """
                    {
                      \"build\": {
                        \"content\": [\"docs/**/*.md\"]
                      }
                    }
                    """);
            history.commit("docfx-1", "Add checker and docs target");

            history.append("samples/Checker.cs", "\n// docs touch one\n");
            history.append("docs/docfx.json", "\n// docs touch one\n");
            history.commit("docfx-2", "Strengthen checker to docs link");

            history.append("samples/Checker.cs", "\n// docs touch two\n");
            history.append("docs/docfx.json", "\n// docs touch two\n");
            history.commit("docfx-3", "Strengthen checker to docs link again");

            history.write(
                    "src/contracts/AgentMetadata.cs",
                    """
                    namespace Contracts;
                    public class AgentMetadata {
                        public string Name => \"agent\";
                    }
                    """);
            history.commit("metadata-1", "Add metadata distractor");

            history.append("src/contracts/AgentMetadata.cs", "\n// metadata only\n");
            history.commit("metadata-2", "Increase distractor history");

            history.append("samples/Checker.cs", "\n// metadata touch\n");
            history.append("src/contracts/AgentMetadata.cs", "\n// checker touch\n");
            history.commit("checker-metadata", "One weak checker to metadata tie");

            history.write(
                    "samples/Prompt_Caching_Sample.csproj",
                    """
                    <Project>
                      <ItemGroup>
                        <PackageReference Include=\"Legacy.Agent\" Version=\"1.0.0\" />
                      </ItemGroup>
                    </Project>
                    """);
            history.commit("csproj-old", "Add old sample project");

            history.delete("samples/Prompt_Caching_Sample.csproj");
            history.write(
                    "samples/PromptCachingSample.csproj",
                    """
                    <Project>
                      <PropertyGroup>
                        <TargetFramework>net9.0</TargetFramework>
                        <Nullable>enable</Nullable>
                      </PropertyGroup>
                    </Project>
                    """);
            history.commit("csproj-replaced", "Replace sample project without rename continuity");

            history.write(
                    "docs/legacy/installation.md",
                    """
                    legacy install steps
                    use powershell scripts
                    """);
            history.commit("installation-old", "Add legacy installation doc");

            history.delete("docs/legacy/installation.md");
            history.write(
                    "docs/user-guide/installation.md",
                    """
                    current installation guide
                    use dotnet tool restore
                    """);
            history.commit("installation-replaced", "Replace installation doc without rename continuity");

            history.write(
                    "docs/legacy/differences.md",
                    """
                    old differences guide
                    compare legacy runtime behavior
                    """);
            history.commit("differences-old", "Add legacy differences doc");

            history.delete("docs/legacy/differences.md");
            history.write(
                    "docs/user-guide/differences.md",
                    """
                    current differences guide
                    compare modern agent behavior
                    """);
            history.commit("differences-replaced", "Replace differences doc without rename continuity");

            history.write(
                    "tests/AgentRuntimeTests.cs",
                    """
                    namespace Tests;
                    public class AgentRuntimeTests {
                        public void UsesLegacyRuntime() {}
                    }
                    """);
            history.commit("runtime-old", "Add old runtime tests");

            history.delete("tests/AgentRuntimeTests.cs");
            history.write(
                    "tests/InProcessRuntimeTests.cs",
                    """
                    namespace Tests;
                    public class InProcessRuntimeTests {
                        public void UsesInProcessRuntime() {}
                    }
                    """);
            history.commit("runtime-replaced", "Replace runtime tests without rename continuity");
        });
    }

    private static GitDistanceTestSuite.FixtureRepo createVectorFixture() throws Exception {
        return fixtureRepo("testcode-git-rank-vectorsimilarity", history -> {
            history.delete("src/index_factories/hnsw_factory.cpp");
            history.delete("src/index_factories/brute_force_factory.cpp");
            history.delete("tests/benchmark/spaces_class_definitions.h");

            history.write(
                    "src/algorithms/hnsw_factory.cpp",
                    """
                    int make_hnsw_factory() {
                      return 1;
                    }
                    """);
            history.write(
                    "src/algorithms/brute_force_factory.cpp",
                    """
                    int make_brute_force_factory() {
                      return 1;
                    }
                    """);
            history.write(
                    "tests/benchmark/legacy_spaces_definitions.h",
                    """
                    #define LEGACY_SPACE_KIND 1
                    """);
            history.commit("initial", "Initial vector similarity files");

            history.rename("src/algorithms/hnsw_factory.cpp", "src/index_factories/hnsw_factory.cpp");
            history.commit("hnsw-rename", "Accepted hnsw rename");

            history.write(
                    "src/index_factories/brute_force_factory.cpp",
                    """
                    int make_brute_force_factory() {
                      return 2;
                    }
                    """);
            history.append("src/algorithms/brute_force_factory.cpp", "\n// old path still exists\n");
            history.commit("brute-split", "Add replacement path without deleting old one");

            history.delete("tests/benchmark/legacy_spaces_definitions.h");
            history.write(
                    "tests/benchmark/spaces_class_definitions.h",
                    """
                    #define SPACE_KIND 2
                    """);
            history.commit("definitions-replaced", "Replace definitions header without rename continuity");
        });
    }

    private static GitDistanceTestSuite.FixtureRepo createAstrapyFixture() throws Exception {
        return fixtureRepo("testcode-git-rank-astrapy", history -> {
            history.delete("pkg/ops/__init__.py");
            history.delete("tests/core/__init__.py");
            history.commit("idiomatic-only", "Initial idiomatic package");

            history.write("pkg/ops/__init__.py", """
                    OPS = True
                    """);
            history.commit("ops-added", "Add ops package");

            history.write("tests/core/__init__.py", """
                    TESTS = True
                    """);
            history.commit("tests-added", "Add tests package");
        });
    }

    private static GitDistanceTestSuite.FixtureRepo fixtureRepo(
            String resourceDir, GitDistanceTestSuite.HistoryScript script) throws Exception {
        var testResourcePath = testResourcePath(resourceDir);
        assertTrue(Files.exists(testResourcePath), "Test resource directory '" + resourceDir + "' not found.");
        return GitDistanceTestSuite.setupGitHistoryFixture(testResourcePath, script);
    }

    private static List<String> rankedPaths(GitRepo repo, ProjectFile seed, int limit) throws InterruptedException {
        return GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), limit).stream()
                .map(entry -> entry.file().getRelPath().toString())
                .toList();
    }

    private static ProjectFile projectFile(Path root, String relPath) {
        return new ProjectFile(root, Path.of(relPath));
    }

    private static Path testResourcePath(String resourceDir) {
        return Path.of("src/test/resources", resourceDir).toAbsolutePath().normalize();
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
