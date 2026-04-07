package ai.brokk.analyzer.ranking;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GitDistanceTestSuite {

    private static final Logger logger = LoggerFactory.getLogger(GitDistanceTestSuite.class);

    @FunctionalInterface
    interface HistoryScript {
        void run(HistoryBuilder history) throws Exception;
    }

    record FixtureRepo(Path root, Map<String, String> commitIds) implements AutoCloseable {
        String commitId(String label) {
            return commitIds.get(label);
        }

        @Override
        public void close() {
            teardownGitRepository(root);
            if (Files.exists(root)) {
                deleteRecursively(root);
            }
        }
    }

    static final class HistoryBuilder {
        private final Git git;
        private final Path root;
        private final Map<String, String> commitIds = new HashMap<>();

        private HistoryBuilder(Git git, Path root) {
            this.git = git;
            this.root = root;
        }

        void write(String relPath, String content) throws IOException {
            var path = root.resolve(relPath);
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
        }

        void append(String relPath, String content) throws IOException {
            var path = root.resolve(relPath);
            Files.writeString(path, Files.readString(path) + content);
        }

        void delete(String relPath) throws IOException {
            Files.deleteIfExists(root.resolve(relPath));
        }

        void rename(String oldRelPath, String newRelPath) throws IOException {
            var destination = root.resolve(newRelPath);
            var parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(root.resolve(oldRelPath), destination, StandardCopyOption.REPLACE_EXISTING);
        }

        String commit(String label, String message) throws Exception {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            var commit = git.commit()
                    .setMessage(message)
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
            commitIds.put(label, commit.getName());
            return commit.getName();
        }

        Map<String, String> commitIds() {
            return Map.copyOf(commitIds);
        }
    }

    static Path setupGitHistory(Path testResourcePath) throws Exception {
        final var testPath = Files.createTempDirectory("brokk-git-distance-tests-");
        copyFixtureTree(testResourcePath, testPath);

        testPath.toFile().deleteOnExit();

        try (var git = Git.init().setDirectory(testPath.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserRepository.java").call();
            git.commit()
                    .setMessage("Initial user model and repository")
                    .setSign(false)
                    .call();

            git.add().addFilepattern("UserService.java").call();
            git.commit().setMessage("Add user service layer").setSign(false).call();

            Files.writeString(
                    testPath.resolve("User.java"),
                    Files.readString(testPath.resolve("User.java")) + "\n    // Added toString method stub\n");
            Files.writeString(
                    testPath.resolve("UserService.java"),
                    Files.readString(testPath.resolve("UserService.java")) + "\n    // Added validation\n");
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserService.java").call();
            git.commit()
                    .setMessage("Update user model and service")
                    .setSign(false)
                    .call();

            git.add().addFilepattern("NotificationService.java").call();
            git.commit().setMessage("Add notification service").setSign(false).call();

            Files.writeString(
                    testPath.resolve("UserService.java"),
                    Files.readString(testPath.resolve("UserService.java"))
                            + "\n    // Added notification integration\n");
            git.add().addFilepattern("UserService.java").call();
            git.add().addFilepattern("NotificationService.java").call();
            git.commit()
                    .setMessage("Integrate notifications with user service")
                    .setSign(false)
                    .call();

            git.add().addFilepattern("ValidationService.java").call();
            git.commit().setMessage("Add validation service").setSign(false).call();

            Files.writeString(
                    testPath.resolve("User.java"),
                    Files.readString(testPath.resolve("User.java")) + "\n    // Added validation\n");
            Files.writeString(
                    testPath.resolve("UserService.java"),
                    Files.readString(testPath.resolve("UserService.java")) + "\n    // More validation\n");
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserService.java").call();
            git.add().addFilepattern("ValidationService.java").call();
            git.commit()
                    .setMessage("Add validation to user workflows")
                    .setSign(false)
                    .call();

            logger.debug("Created git history with 7 commits for GitRank testing");
        }

        return testPath;
    }

    static FixtureRepo setupGitHistoryFixture(Path testResourcePath, HistoryScript historyScript) throws Exception {
        final var testPath = Files.createTempDirectory("brokk-git-distance-fixture-");
        copyFixtureTree(testResourcePath, testPath);

        try (var git = Git.init().setDirectory(testPath.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            var history = new HistoryBuilder(git, testPath);
            historyScript.run(history);
            return new FixtureRepo(testPath, history.commitIds());
        } catch (Exception e) {
            teardownGitRepository(testPath);
            deleteRecursively(testPath);
            throw e;
        }
    }

    static void teardownGitRepository(Path testPath) {
        if (testPath != null) {
            Path gitDir = testPath.resolve(".git");
            if (Files.exists(gitDir)) {
                deleteRecursively(gitDir);
            }
        }
    }

    private static void copyFixtureTree(Path source, Path destination) throws IOException {
        try (final var walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                final var relativePath = source.relativize(path);
                final var newPath = destination.resolve(relativePath);
                try {
                    var parent = newPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, newPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursively(Path path) {
        try (final var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception e) {
            logger.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }
}
