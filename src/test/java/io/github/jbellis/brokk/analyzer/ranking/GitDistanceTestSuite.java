package io.github.jbellis.brokk.analyzer.ranking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GitDistanceTestSuite {

    private static final Logger logger = LoggerFactory.getLogger(GitDistanceTestSuite.class);

    static Path setupGitHistory(Path testResourcePath) throws Exception {
        final var testPath = Files.createTempDirectory("brokk-git-distance-tests-");
        try (final var walk = Files.walk(testResourcePath)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                final var relativePath = testResourcePath.relativize(path);
                final var newPath = testPath.resolve(relativePath);
                try {
                    Files.copy(path, newPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        testPath.toFile().deleteOnExit();

        try (var git = Git.init().setDirectory(testPath.toFile()).call()) {
            // Configure git user for commits
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            // Commit 1: User and UserRepository together (creates User-UserRepository edge)
            git.add().addFilepattern("User.java").call();
            git.add().addFilepattern("UserRepository.java").call();
            git.commit()
                    .setMessage("Initial user model and repository")
                    .setSign(false)
                    .call();

            // Commit 2: UserService with User and UserRepository (strengthens existing edges, adds UserService)
            git.add().addFilepattern("UserService.java").call();
            git.commit().setMessage("Add user service layer").setSign(false).call();

            // Commit 3: Update User and UserService together (strengthens User-UserService edge)
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

            // Commit 4: NotificationService standalone (creates isolated component)
            git.add().addFilepattern("NotificationService.java").call();
            git.commit().setMessage("Add notification service").setSign(false).call();

            // Commit 5: UserService and NotificationService together (creates UserService-NotificationService edge)
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

            // Commit 6: ValidationService alone
            git.add().addFilepattern("ValidationService.java").call();
            git.commit().setMessage("Add validation service").setSign(false).call();

            // Commit 7: User, UserService, and ValidationService together (creates multiple edges)
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

    static void teardownGitRepository(Path testPath) {
        if (testPath != null) {
            Path gitDir = testPath.resolve(".git");
            if (Files.exists(gitDir)) {
                try (final var walk = Files.walk(gitDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                } catch (Exception e) {
                    logger.warn("Failed to delete git directory: {}", e.getMessage());
                }
            }
        }
    }
}
