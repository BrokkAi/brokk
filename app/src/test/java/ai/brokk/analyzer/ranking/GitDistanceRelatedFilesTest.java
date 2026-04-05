package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CSharpAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.testutil.TestProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
        var testResourcePath = Path.of("src/test/resources/testcode-git-rank-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(testResourcePath), "Test resource directory 'testcode-git-rank-java' not found.");

        // Prepare a git history that matches the co-change patterns expected by the assertions
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

        // PMI should emphasize genuinely related files over loosely related ones
        notification.ifPresent(
                fileRelevance -> assertTrue(user.get().score() > fileRelevance.score(), results.toString()));
    }

    @Test
    public void testPMISeedsNotInResults() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        // Use multiple seeds that co-occur in commits
        var userService = new ProjectFile(testProject.getRoot(), "UserService.java");
        var user = new ProjectFile(testProject.getRoot(), "User.java");
        var seedWeights = Map.of(userService, 1.0, user, 0.5);

        var results = GitDistance.getRelatedFiles((GitRepo) testProject.getRepo(), seedWeights, 10);
        assertFalse(results.isEmpty(), "Should return non-seed related files");

        // Verify no seeds appear in results
        var resultFiles = results.stream().map(r -> r.file()).toList();
        assertFalse(resultFiles.contains(userService), "UserService (seed) should not appear in results");
        assertFalse(resultFiles.contains(user), "User (seed) should not appear in results");
    }

    @Test
    public void testPMINoSeedWeights() throws Exception {
        assertNotNull(analyzer, "Analyzer should be initialized");
        assertNotNull(testPath, "Test path should not be null");

        // FIXME 793
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
            // Initialize repo
            try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.save();

                // Create initial files: A.java and UserService.java, commit together
                write(
                        tempDir.resolve("A.java"),
                        """
                        public class A {
                            public String id() { return "a"; }
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

                // Modify both together once more (strengthen co-change)
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

                // Rename A.java -> Account.java (git will detect rename on similarity)
                var src = tempDir.resolve("A.java");
                var dst = tempDir.resolve("Account.java");
                Files.move(src, dst);
                git.rm().addFilepattern("A.java").call();
                git.add().addFilepattern("Account.java").call();
                git.commit().setMessage("Rename A to Account").setSign(false).call();

                // Co-change Account.java and UserService.java
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

            // Use GitRepo + PMI
            try (var repo = new GitRepo(tempDir)) {
                // PMI seeded with UserService should surface Account.java (not A.java)
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
            // best-effort cleanup of .git to free locks on Windows and then delete temp dir
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
    public void gitDistanceTracksPlumeRenameAtFiftyFourPercentSimilarity() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/main/java/org/plumelib/merging/GitLibrary.java"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25);
            var rankedPaths = results.stream()
                    .map(entry -> entry.file().getRelPath().toString())
                    .toList();

            var javaLibraryTestIndex = rankedPaths.indexOf("src/test/java/org/plumelib/merging/JavaLibraryTest.java");
            var dmpLibraryIndex = rankedPaths.indexOf("src/main/java/org/plumelib/merging/DmpLibrary.java");

            assertTrue(javaLibraryTestIndex >= 0, rankedPaths.toString());
            assertTrue(dmpLibraryIndex >= 0, rankedPaths.toString());
            assertTrue(javaLibraryTestIndex < dmpLibraryIndex, rankedPaths.toString());
        }
    }

    @Test
    public void buildCanonicalizerDoesNotTreatAutogenAnthropicCsprojAsRenameAtFiftyThreshold() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldCsproj =
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/AutoGen.Anthropic.Samples/AutoGen.Anthropic.Samples.csproj"));

            var canonical = canonicalizer.canonicalize("6a9c14715b04de653b16a2d1376461e710b80179", oldCsproj);

            assertEquals(oldCsproj.getRelPath(), canonical.getRelPath(), canonical.getRelPath().toString());
        }
    }

    @Test
    public void gitDistanceRanksAutogenDocfxAheadOfHigherHistoryContracts() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/GettingStarted/Checker.cs"));
            var rankedPaths = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 60).stream()
                    .map(entry -> entry.file().getRelPath().toString())
                    .toList();

            var docfxIndex = rankedPaths.indexOf("docs/dotnet/docfx.json");
            var agentMetadataIndex = rankedPaths.indexOf("dotnet/src/Microsoft.AutoGen/Contracts/AgentMetadata.cs");

            assertTrue(docfxIndex >= 0, rankedPaths.toString());
            assertTrue(agentMetadataIndex >= 0, rankedPaths.toString());
            assertTrue(docfxIndex < agentMetadataIndex, rankedPaths.toString());
        }
    }

    @Test
    public void buildCanonicalizerDoesNotTreatAutogenInstallationDocAsContinuationWithoutRenameLabel() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldInstallation =
                    new ProjectFile(repoRoot, Path.of("docs/dotnet/user-guide/core-user-guide/installation.md"));

            var canonical = canonicalizer.canonicalize("b02965e42077295ca2ef1ce01066680373b9ba66", oldInstallation);

            assertEquals(
                    Path.of("docs/dotnet/user-guide/core-user-guide/installation.md"),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void gitDistanceSkipsUnreadableContinuationCandidatesInVectorSimilarity() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/VecSim/query_result_struct.cpp"));

            var ranked = assertDoesNotThrow(() -> GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25));

            assertNotNull(ranked);
        }
    }

    @Test
    public void buildCanonicalizerDoesNotTreatVectorSimilarityBmSpacesClassAsDefinitionsRename() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldCpp = new ProjectFile(repoRoot, Path.of("tests/benchmark/bm_spaces_class.cpp"));

            var canonical = canonicalizer.canonicalize("73e7225e5754ec9f2edf27a0fed58e3f315ec9e7", oldCpp);

            assertEquals(oldCpp.getRelPath(), canonical.getRelPath(), canonical.getRelPath().toString());
        }
    }

    @Test
    public void buildCanonicalizerRequiresPathReplacementForVectorSimilarityBruteForceFactoryMove() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldFactory =
                    new ProjectFile(repoRoot, Path.of("src/VecSim/algorithms/brute_force/brute_force_factory.cpp"));

            var canonical = canonicalizer.canonicalize("73e7225e5754ec9f2edf27a0fed58e3f315ec9e7", oldFactory);

            assertEquals(oldFactory.getRelPath(), canonical.getRelPath(), canonical.getRelPath().toString());
        }
    }

    @Test
    public void buildCanonicalizerTreatsVectorSimilarityHnswFactoryMoveAsRename() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldFactory = new ProjectFile(repoRoot, Path.of("src/VecSim/algorithms/hnsw/hnsw_factory.cpp"));

            var canonical = canonicalizer.canonicalize("73e7225e5754ec9f2edf27a0fed58e3f315ec9e7", oldFactory);

            assertEquals(
                    Path.of("src/VecSim/index_factories/hnsw_factory.cpp"),
                    canonical.getRelPath(),
                    canonical.getRelPath().toString());
        }
    }

    @Test
    public void gitDistanceDoesNotSurfaceVectorSimilarityDefinitionsWithoutNativeRename() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/VecSim/query_result_struct.cpp"));
            var rankedPaths = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100).stream()
                    .map(entry -> entry.file().getRelPath().toString())
                    .toList();

            var definitionsIndex =
                    rankedPaths.indexOf("tests/benchmark/spaces_benchmarks/bm_spaces_class_definitions.h");
            assertTrue(definitionsIndex < 0, rankedPaths.toString());
        }
    }

    @Test
    public void buildCanonicalizerDoesNotTreatAutogenDifferencesDocAsContinuation() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldDifferences =
                    new ProjectFile(repoRoot, Path.of("docs/dotnet/user-guide/core-user-guide/differences-python.md"));

            var canonical = canonicalizer.canonicalize("b02965e42077295ca2ef1ce01066680373b9ba66", oldDifferences);

            assertEquals(oldDifferences.getRelPath(), canonical.getRelPath(), canonical.getRelPath().toString());
        }
    }

    @Test
    public void buildCanonicalizerDoesNotTreatAutogenAgentRuntimeTestsAsInProcessContinuation() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldRuntimeTest =
                    new ProjectFile(repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Core.Tests/AgentRuntimeTests.cs"));

            var canonical = canonicalizer.canonicalize("b16b94feb8bd89ef07c14fc7f34419490924b993", oldRuntimeTest);

            assertEquals(oldRuntimeTest.getRelPath(), canonical.getRelPath(), canonical.getRelPath().toString());
        }
    }

    @Test
    public void debugAutogenCsprojCanonicalization() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldCsproj =
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/AutoGen.Anthropic.Samples/AutoGen.Anthropic.Samples.csproj"));
            var canonical = canonicalizer.canonicalize("6a9c14715b04de653b16a2d1376461e710b80179", oldCsproj);
            System.err.println("canonical csproj: " + canonical.getRelPath());

            var changed = repo.listFilesChangedInCommit("6a9c14715b04de653b16a2d1376461e710b80179");
            changed.stream()
                    .map(file -> file.file().getRelPath().toString())
                    .filter(path -> path.contains("AutoGen.Anthropic.Samples.csproj")
                            || path.contains("AutoGen.Anthropic.Sample.csproj")
                            || path.contains("Program.cs")
                            || path.contains("Create_Anthropic_Agent.cs"))
                    .sorted()
                    .forEach(path -> System.err.println("changed old commit: " + path));
        }
    }

    private static void write(Path p, String content) throws Exception {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void debugGitScoresForPlumeMergeGitLibrarySeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/main/java/org/plumelib/merging/GitLibrary.java"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25);
            results.forEach(entry ->
                    System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitScoresForAutogenProgramSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed =
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/AgentChat/AutoGen.Anthropic.Sample/Program.cs"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25);
            results.forEach(entry ->
                    System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitScoresForAutogenCheckerSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/GettingStarted/Checker.cs"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 25);
            results.forEach(entry -> System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugImportScoresForAutogenCheckerSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        var project = new TestProject(repoRoot, Languages.C_SHARP);
        var analyzer = new CSharpAnalyzer(project);
        try {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/GettingStarted/Checker.cs"));
            var results = ImportPageRanker.getRelatedFilesByImports(analyzer, Map.of(seed, 1.0), 100, false);
            for (var entry : results) {
                var relPath = entry.file().getRelPath().toString();
                if (relPath.equals(
                                "dotnet/samples/AgentChat/AutoGen.Anthropic.Sample/Anthropic_Agent_With_Prompt_Caching.cs")
                        || relPath.equals(
                                "dotnet/samples/AgentChat/AutoGen.Anthropic.Sample/AutoGen.Anthropic.Sample.csproj")) {
                    System.out.printf("target %.15f %s%n", entry.score(), relPath);
                }
            }
            results.forEach(entry -> System.out.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        } finally {
            project.close();
        }
    }

    @Test
    public void debugGitScoresForAutogenHelloAiAgentsProgramSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/Hello/HelloAIAgents/Program.cs"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100);
            results.forEach(entry -> System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitScoresForAutogenHelloAgentProgramSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/Hello/HelloAgent/Program.cs"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100);
            results.forEach(entry -> System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitScoresForAutogenTopicIdAndInMemoryRuntimePair() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var topicId = new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/TopicId.cs"));
            var inMemory = new ProjectFile(
                    repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Integration.Tests/InMemoryRuntimeIntegrationTests.cs"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(topicId, 1.0, inMemory, 1.0), 100);
            results.forEach(entry -> System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitTermsForAutogenHelloAiAgentsProgramSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/Hello/HelloAIAgents/Program.cs"));
            var targets = List.of(
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/Hello/HelloAgent/appsettings.json")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/AgentHost/appsettings.json")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Agents/IOAgent/ConsoleAgent/IHandleConsole.cs")),
                    new ProjectFile(repoRoot, Path.of("python/samples/core_xlang_hello_python_agent/protos/agent_events_pb2.py")),
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/dev-team/DevTeam.ServiceDefaults/DevTeam.ServiceDefaults.csproj")),
                    new ProjectFile(repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Integration.Tests/HelloAppHostIntegrationTests.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/samples/dev-team/DevTeam.Backend/Program.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Core.Tests/Microsoft.AutoGen.Core.Tests.csproj")));

            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            var fileDocFreq = new HashMap<ProjectFile, Integer>();
            var jointMass = new HashMap<ProjectFile, Double>();
            int seedCommitCount = 0;

            for (var commit : baselineCommits) {
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var file : changedFiles) {
                    fileDocFreq.merge(file, 1, Integer::sum);
                }

                if (!changedFiles.contains(seed)) {
                    continue;
                }

                seedCommitCount += 1;
                double commitPairMass = 1.0 / changedFiles.size();
                for (var target : targets) {
                    if (changedFiles.contains(target)) {
                        jointMass.merge(target, commitPairMass, Double::sum);
                    }
                }
            }

            double baselineCommitCount = baselineCommits.size();
            for (var target : targets) {
                double df = Math.max(1, fileDocFreq.getOrDefault(target, 0));
                double joint = jointMass.getOrDefault(target, 0.0);
                double conditional = seedCommitCount == 0 ? 0.0 : joint / seedCommitCount;
                double idf = Math.log1p(baselineCommitCount / df);
                double score = conditional * idf;
                System.err.printf(
                        "%s df=%d seed_den=%d joint=%.15f conditional=%.15f idf=%.15f score=%.15f%n",
                        target.getRelPath(),
                        fileDocFreq.getOrDefault(target, 0),
                        seedCommitCount,
                        joint,
                        conditional,
                        idf,
                        score);
            }
        }
    }

    @Test
    public void debugGitTermsForAutogenHelloAgentProgramSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/Hello/HelloAgent/Program.cs"));
            var targets = List.of(
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/test/Microsoft.AutoGen.Integration.Tests.AppHosts/InMemoryTests.AppHost/InMemoryTests.AppHost.csproj")),
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/src/Microsoft.AutoGen/RuntimeGateway.Grpc/Services/Orleans/Surrogates/AnySurrogate.cs")),
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/src/Microsoft.AutoGen/RuntimeGateway.Grpc/Services/Orleans/Surrogates/AgentIdSurrogate.cs")),
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/src/Microsoft.AutoGen/RuntimeGateway.Grpc/Services/Orleans/Surrogates/RpcRequestSurrogate.cs")),
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/src/Microsoft.AutoGen/RuntimeGateway.Grpc/Services/Orleans/Surrogates/SubscriptionSurrogate.cs")));

            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            var fileDocFreq = new HashMap<ProjectFile, Integer>();
            var jointMass = new HashMap<ProjectFile, Double>();
            int seedCommitCount = 0;

            for (var commit : baselineCommits) {
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var file : changedFiles) {
                    fileDocFreq.merge(file, 1, Integer::sum);
                }

                if (!changedFiles.contains(seed)) {
                    continue;
                }

                seedCommitCount += 1;
                double commitPairMass = 1.0 / changedFiles.size();
                for (var target : targets) {
                    if (changedFiles.contains(target)) {
                        jointMass.merge(target, commitPairMass, Double::sum);
                    }
                }
            }

            double baselineCommitCount = baselineCommits.size();
            for (var target : targets) {
                double df = Math.max(1, fileDocFreq.getOrDefault(target, 0));
                double joint = jointMass.getOrDefault(target, 0.0);
                double conditional = seedCommitCount == 0 ? 0.0 : joint / seedCommitCount;
                double idf = Math.log1p(baselineCommitCount / df);
                double score = conditional * idf;
                System.err.printf(
                        "%s df=%d seed_den=%d joint=%.15f conditional=%.15f idf=%.15f score=%.15f%n",
                        target.getRelPath(),
                        fileDocFreq.getOrDefault(target, 0),
                        seedCommitCount,
                        joint,
                        conditional,
                        idf,
                        score);
            }
        }
    }

    @Test
    public void debugGitPerSeedTermsForAutogenTopicIdAndInMemoryRuntimePair() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var topicId = new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/TopicId.cs"));
            var inMemory = new ProjectFile(
                    repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Integration.Tests/InMemoryRuntimeIntegrationTests.cs"));
            var seeds = List.of(topicId, inMemory);
            var targets = List.of(
                    new ProjectFile(repoRoot, Path.of("dotnet/AutoGen.sln")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/KVStringParseHelper.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/IAgentRuntime.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/IHandle.cs")),
                    new ProjectFile(
                            repoRoot,
                            Path.of("dotnet/src/Microsoft.AutoGen/Agents/IOAgent/ConsoleAgent/IHandleConsole.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Core/AgentsApp.cs")),
                    new ProjectFile(repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Core.Tests/InProcessRuntimeTests.cs")));

            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            var fileDocFreq = new HashMap<ProjectFile, Integer>();
            var jointMass = new HashMap<GitDistance.FileEdge, Double>();
            var seedCommitCount = new HashMap<ProjectFile, Integer>();

            for (var commit : baselineCommits) {
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var file : changedFiles) {
                    fileDocFreq.merge(file, 1, Integer::sum);
                }

                var seedsInCommit = seeds.stream().filter(changedFiles::contains).toList();
                if (seedsInCommit.isEmpty()) {
                    continue;
                }

                for (var seed : seedsInCommit) {
                    seedCommitCount.merge(seed, 1, Integer::sum);
                }

                double commitPairMass = 1.0 / changedFiles.size();
                for (var seed : seedsInCommit) {
                    for (var target : targets) {
                        if (changedFiles.contains(target)) {
                            jointMass.merge(new GitDistance.FileEdge(seed, target), commitPairMass, Double::sum);
                        }
                    }
                }
            }

            double baselineCommitCount = baselineCommits.size();
            for (var target : targets) {
                double df = Math.max(1, fileDocFreq.getOrDefault(target, 0));
                double idf = Math.log1p(baselineCommitCount / df);
                System.err.printf("target=%s df=%d idf=%.15f%n", target.getRelPath(), (int) df, idf);
                double total = 0.0;
                for (var seed : seeds) {
                    double joint = jointMass.getOrDefault(new GitDistance.FileEdge(seed, target), 0.0);
                    int seedDenom = seedCommitCount.getOrDefault(seed, 0);
                    double conditional = seedDenom == 0 ? 0.0 : joint / seedDenom;
                    double contribution = conditional * idf;
                    total += contribution;
                    System.err.printf(
                            "  seed=%s seed_den=%d joint=%.15f conditional=%.15f contribution=%.15f%n",
                            seed.getRelPath(),
                            seedDenom,
                            joint,
                            conditional,
                            contribution);
                }
                System.err.printf("  total=%.15f%n", total);
            }
        }
    }

    @Test
    public void debugGitContributingCommitsForAutogenTopicIdAndInMemoryRuntimePair() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var topicId = new ProjectFile(repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/TopicId.cs"));
            var inMemory = new ProjectFile(
                    repoRoot, Path.of("dotnet/test/Microsoft.AutoGen.Integration.Tests/InMemoryRuntimeIntegrationTests.cs"));
            var pairs = List.of(
                    Map.entry(
                            topicId,
                            new ProjectFile(
                                    repoRoot, Path.of("dotnet/src/Microsoft.AutoGen/Contracts/KVStringParseHelper.cs"))),
                    Map.entry(inMemory, new ProjectFile(repoRoot, Path.of("dotnet/AutoGen.sln"))));

            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            for (var commit : baselineCommits) {
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var pair : pairs) {
                    if (changedFiles.contains(pair.getKey()) && changedFiles.contains(pair.getValue())) {
                        System.err.printf(
                                "pair %s -> %s commit=%s size=%d%n",
                                pair.getKey().getRelPath(),
                                pair.getValue().getRelPath(),
                                commit.id(),
                                changedFiles.size());
                    }
                }
            }
        }
    }

    @Test
    public void debugVectorSimilarityQueryResultStructGitRanking() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/VecSim/query_result_struct.cpp"));
            var targets = List.of(
                    new ProjectFile(repoRoot, Path.of("tests/unit/test_bruteforce.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/vec_sim.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/vec_sim.h")),
                    new ProjectFile(repoRoot, Path.of("src/python_bindings/bindings.cpp")),
                    new ProjectFile(repoRoot, Path.of("tests/flow/test_bruteforce.py")),
                    new ProjectFile(repoRoot, Path.of("tests/benchmark/spaces_benchmarks/bm_spaces_class_fp32.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/algorithms/brute_force/brute_force_multi.h")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/spaces/IP/IP_AVX512_FP32.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/spaces/L2_space.h")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/index_factories/brute_force_factory.cpp")),
                    new ProjectFile(repoRoot, Path.of("tests/benchmark/spaces_benchmarks/bm_spaces_class_definitions.h")));
            var ranked = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100);
            System.out.println("git top 100");
            for (var entry : ranked) {
                if (targets.contains(entry.file())) {
                    System.out.printf("  target git %.15f %s%n", entry.score(), entry.file().getRelPath());
                }
            }
            for (var target : targets) {
                int index = -1;
                for (int i = 0; i < ranked.size(); i++) {
                    if (ranked.get(i).file().equals(target)) {
                        index = i + 1;
                        break;
                    }
                }
                System.out.printf("target %s git_rank=%s%n", target.getRelPath(), index >= 0 ? index : "none");
            }
        }
    }

    @Test
    public void debugVectorSimilarityQueryResultStructGitScores() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/VecSim/query_result_struct.cpp"));
            var targets = List.of(
                    new ProjectFile(repoRoot, Path.of("tests/unit/test_bruteforce.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/vec_sim.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/vec_sim.h")),
                    new ProjectFile(repoRoot, Path.of("src/python_bindings/bindings.cpp")),
                    new ProjectFile(repoRoot, Path.of("tests/flow/test_bruteforce.py")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/utils/arr_cpp.h")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/algorithms/hnsw/hnsw.h")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/spaces/IP/IP_AVX512_FP32.cpp")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/spaces/L2_space.h")),
                    new ProjectFile(repoRoot, Path.of("src/VecSim/index_factories/brute_force_factory.cpp")),
                    new ProjectFile(repoRoot, Path.of("tests/benchmark/spaces_benchmarks/bm_spaces_class_definitions.h")));
            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            var fileDocFreq = new HashMap<ProjectFile, Integer>();
            var jointMass = new HashMap<ProjectFile, Double>();
            int seedCommitCount = 0;

            for (var commit : baselineCommits) {
                var changed = repo.listFilesChangedInCommit(commit.id());
                if (changed.isEmpty()) {
                    continue;
                }
                var changedFiles = changed.stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(path -> canonicalizer.canonicalize(commit.id(), path))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var file : changedFiles) {
                    fileDocFreq.merge(file, 1, Integer::sum);
                }
                if (!changedFiles.contains(seed)) {
                    continue;
                }
                seedCommitCount += 1;
                double commitPairMass = 1.0 / changedFiles.size();
                for (var target : targets) {
                    if (changedFiles.contains(target)) {
                        jointMass.merge(target, commitPairMass, Double::sum);
                    }
                }
            }

            double baselineCount = baselineCommits.size();
            for (var target : targets) {
                double df = Math.max(1, fileDocFreq.getOrDefault(target, 0));
                double joint = jointMass.getOrDefault(target, 0.0);
                double conditional = seedCommitCount == 0 ? 0.0 : joint / seedCommitCount;
                double idf = Math.log(1.0 + baselineCount / df);
                double score = conditional * idf;
                System.out.printf(
                        "%s df=%d seed_den=%d joint=%.15f conditional=%.15f idf=%.15f score=%.15f%n",
                        target.getRelPath(),
                        fileDocFreq.getOrDefault(target, 0),
                        seedCommitCount,
                        joint,
                        conditional,
                        idf,
                        score);
            }
        }
    }

    @Test
    public void debugVectorSimilarityHnswCanonicalizedCommitIds() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/VectorSimilarity");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "VectorSimilarity checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var baseline = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baseline);
            var target = new ProjectFile(repoRoot, Path.of("src/VecSim/algorithms/hnsw/hnsw.h"));
            for (var commit : baseline) {
                var changed = repo.listFilesChangedInCommit(commit.id());
                if (changed.isEmpty()) {
                    continue;
                }
                var rawPaths = changed.stream().map(file -> file.file().getRelPath().toString()).toList();
                var changedFiles = changed.stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(path -> canonicalizer.canonicalize(commit.id(), path))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (!changedFiles.contains(target)) {
                    continue;
                }
                boolean rawContainsTarget = rawPaths.contains(target.getRelPath().toString());
                if (!rawContainsTarget) {
                    System.out.println(commit.id() + " => " + rawPaths);
                }
            }
        }
    }

    @Test
    public void debugChangedFilesForAutogenPairCommits() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        var interesting = Set.of(
                "b16b94feb8bd89ef07c14fc7f34419490924b993",
                "1a789dfcc44dc2f90b2bf2805a78a4e4f4112c4a",
                "0100201dd41111473f8624cbf1ab1c2a926f8c93",
                "7d01bc61368d912460e28daf8ea2edb228bfde24",
                "ff7f863e739cd0339f54d460d2be8a79bcdd0231");
        try (var repo = new GitRepo(repoRoot)) {
            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            for (var commit : baselineCommits) {
                if (!interesting.contains(commit.id())) {
                    continue;
                }
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .sorted()
                        .toList();
                System.err.printf("commit=%s size=%d%n", commit.id(), changedFiles.size());
                for (var file : changedFiles) {
                    System.err.printf("  %s%n", file.getRelPath());
                }
            }
        }
    }

    @Test
    public void debugAutogenCheckerDocChangedCommits() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            for (var commit : repo.listCommitsDetailed(repo.getCurrentBranch(), 1000)) {
                var changed = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(entry -> entry.file().getRelPath().toString())
                        .filter(path -> path.endsWith("tutorial.md") || path.endsWith("protobuf-message-types.md"))
                        .sorted()
                        .toList();
                if (!changed.isEmpty()) {
                    System.err.println(commit.id() + " " + changed);
                }
            }
        }
    }

    @Test
    public void debugAutogenCheckerDocCanonicalizedCommits() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            for (var commit : commits) {
                var changed = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(entry -> canonicalizer.canonicalize(commit.id(), entry.file()))
                        .map(file -> file.getRelPath().toString())
                        .filter(path ->
                                path.endsWith("tutorial.md")
                                        || path.endsWith("protobuf-message-types.md")
                                        || path.endsWith("installation.md"))
                        .distinct()
                        .sorted()
                        .toList();
                if (!changed.isEmpty()) {
                    System.err.println(commit.id() + " " + changed);
                }
            }
        }
    }

    @Test
    public void debugGitScoresForPlumeImportsTest2GoalSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/test/resources/ImportsTest2Goal.java"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100);
            results.forEach(entry ->
                    System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void debugGitScoresForPlumeImportsTest8BaseSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("src/test/resources/ImportsTest8Base.java"));
            var results = GitDistance.getRelatedFiles(repo, Map.of(seed, 1.0), 100);
            results.forEach(entry ->
                    System.err.printf("%.15f %s%n", entry.score(), entry.file().getRelPath()));
        }
    }

    @Test
    public void buildCanonicalizerDoesNotMapAstrapyIdiomaticInitIntoTestsCoreInit() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/astrapy");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "astrapy checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldInit = new ProjectFile(repoRoot, Path.of("astrapy/idiomatic/__init__.py"));
            var canonical = canonicalizer.canonicalize(
                    "ed88549d90c44af8228a8aff2a52ac3f601919b4", oldInit);

            assertNotEquals(
                    new ProjectFile(repoRoot, Path.of("tests/core/__init__.py")),
                    canonical,
                    "Generic __init__.py fallback should not drift across trees into tests/core");
        }
    }

    @Test
    public void buildCanonicalizerDoesNotMapAstrapyIdiomaticInitIntoOpsInit() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/astrapy");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "astrapy checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(commits);
            var oldInit = new ProjectFile(repoRoot, Path.of("astrapy/idiomatic/__init__.py"));
            var canonical = canonicalizer.canonicalize(
                    "ed88549d90c44af8228a8aff2a52ac3f601919b4", oldInit);

            assertNotEquals(
                    new ProjectFile(repoRoot, Path.of("astrapy/ops/__init__.py")),
                    canonical,
                    "Generic __init__.py fallback should not drift into astrapy/ops");
        }
    }

    @Test
    public void debugImportResolutionForPlumeImportsTest8BaseSeed() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        var analyzer = new JavaAnalyzer(new TestProject(repoRoot, Languages.JAVA));
        var seed = new ProjectFile(repoRoot, Path.of("src/test/resources/ImportsTest8Base.java"));
        analyzer.importedCodeUnitsOf(seed).stream()
                .map(cu -> cu.source())
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .sorted()
                .forEach(System.err::println);
    }

    @Test
    public void debugGitTermsForAutogenCheckerAnthropicPair() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/brokkbench/clones/microsoft__autogen");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "autogen checkout not present");
        try (var repo = new GitRepo(repoRoot)) {
            var seed = new ProjectFile(repoRoot, Path.of("dotnet/samples/GettingStarted/Checker.cs"));
            var targets = List.of(
                    new ProjectFile(
                            repoRoot,
                            Path.of(
                                    "dotnet/samples/AgentChat/AutoGen.Anthropic.Sample/Anthropic_Agent_With_Prompt_Caching.cs")),
                    new ProjectFile(
                            repoRoot,
                            Path.of("dotnet/samples/AgentChat/AutoGen.Anthropic.Sample/AutoGen.Anthropic.Sample.csproj")));

            var baselineCommits = repo.listCommitsDetailed(repo.getCurrentBranch(), 1_000);
            var canonicalizer = repo.buildCanonicalizer(baselineCommits);
            var fileDocFreq = new HashMap<ProjectFile, Integer>();
            var jointMass = new HashMap<ProjectFile, Double>();
            int seedCommitCount = 0;

            for (var commit : baselineCommits) {
                var changedFiles = repo.listFilesChangedInCommit(commit.id()).stream()
                        .map(IGitRepo.ModifiedFile::file)
                        .map(pf -> canonicalizer.canonicalize(commit.id(), pf))
                        .distinct()
                        .filter(ProjectFile::exists)
                        .toList();
                if (changedFiles.isEmpty()) {
                    continue;
                }

                for (var file : changedFiles) {
                    fileDocFreq.merge(file, 1, Integer::sum);
                }

                if (!changedFiles.contains(seed)) {
                    continue;
                }

                seedCommitCount += 1;
                double commitPairMass = 1.0 / changedFiles.size();
                for (var target : targets) {
                    if (changedFiles.contains(target)) {
                        jointMass.merge(target, commitPairMass, Double::sum);
                    }
                }
            }

            double baselineCommitCount = baselineCommits.size();
            for (var target : targets) {
                double df = Math.max(1, fileDocFreq.getOrDefault(target, 0));
                double joint = jointMass.getOrDefault(target, 0.0);
                double conditional = seedCommitCount == 0 ? 0.0 : joint / seedCommitCount;
                double idf = Math.log1p(baselineCommitCount / df);
                double score = conditional * idf;
                System.out.printf(
                        "%s df=%d seed_den=%d joint=%.15f conditional=%.15f idf=%.15f score=%.15f%n",
                        target.getRelPath(),
                        fileDocFreq.getOrDefault(target, 0),
                        seedCommitCount,
                        joint,
                        conditional,
                        idf,
                        score);
            }
        }
    }

    @Test
    public void debugDefinitionsForPlumeBooleanColumn() throws Exception {
        var repoRoot = Path.of("/home/jonathan/Projects/plume-merge");
        Assumptions.assumeTrue(Files.isDirectory(repoRoot), "plume-merge checkout not present");
        var analyzer = new JavaAnalyzer(new TestProject(repoRoot, Languages.JAVA));
        analyzer.getDefinitions("tech.tablesaw.api.BooleanColumn").stream()
                .map(CodeUnit::source)
                .map(ProjectFile::getRelPath)
                .map(Path::toString)
                .forEach(System.err::println);
    }
}
