package ai.brokk.init.onboarding;

import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.MainProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Handles git ignore configuration and Brokk project file setup.
 * This class contains pure logic without UI dependencies.
 * Migration of legacy style.md to AGENTS.md is NOT performed here;
 * that responsibility is delegated to StyleGuideMigrator.
 */
public class GitIgnoreConfigurator {
    private static final Logger logger = LogManager.getLogger(GitIgnoreConfigurator.class);

    /** Result of git ignore setup operation. */
    public record SetupResult(boolean gitignoreUpdated, List<ProjectFile> stagedFiles, Optional<String> errorMessage) {}

    /**
     * Sets up .gitignore with Brokk entries and stages project files to git.
     * Note: This method does NOT perform legacy style.md → AGENTS.md migration.
     * Migration is the responsibility of the orchestrator and should be done via
     * StyleGuideMigrator before calling this method if desired.
     */
    public static SetupResult setupGitIgnoreAndStageFiles(IProject project, @Nullable IConsoleIO consoleIO) {
        var stagedFiles = new ArrayList<ProjectFile>();
        boolean gitignoreUpdated = false;

        try {
            var repo = project.getRepo();
            if (!(repo instanceof GitRepo gitRepo)) {
                String msg = "Project repo is not a GitRepo instance";
                logger.warn("setupGitIgnoreAndStageFiles: {}", msg);
                return new SetupResult(false, stagedFiles, Optional.of(msg));
            }

            var gitTopLevel = project.getMasterRootPathForConfig();

            // Update .gitignore
            var gitignorePf = new ProjectFile(gitTopLevel, ".gitignore");

            // Check if .gitignore already has comprehensive Brokk patterns
            if (!GitIgnoreUtils.isBrokkIgnored(gitignorePf)) {
                // Read existing content (or start fresh)
                String content = "";
                if (Files.exists(gitignorePf.absPath())) {
                    content = Files.readString(gitignorePf.absPath());
                    if (!content.endsWith("\n")) {
                        content += "\n";
                    }
                }

                // Add Brokk entries
                content += "\n### BROKK'S CONFIGURATION ###\n";
                content += ".brokk/**\n";
                content += "/.brokk/workspace.properties\n";
                content += "/.brokk/sessions/\n";
                content += "/.brokk/dependencies/\n";
                content += "/.brokk/history.zip\n";
                content += "!AGENTS.md\n";
                content += "!.brokk/style.md\n";
                content += "!.brokk/review.md\n";
                content += "!.brokk/project.properties\n";

                Files.writeString(gitignorePf.absPath(), content);
                gitignoreUpdated = true;
                if (consoleIO != null) {
                    consoleIO.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Updated .gitignore with .brokk entries");
                }

                // Stage .gitignore
                try {
                    gitRepo.add(List.of(gitignorePf));
                    stagedFiles.add(gitignorePf);
                    logger.debug("Staged .gitignore to git");
                } catch (Exception ex) {
                    logger.warn("Error staging .gitignore to git: {}", ex.getMessage());
                }
            }

            // Create .brokk directory at gitTopLevel if it doesn't exist
            var sharedBrokkDir = gitTopLevel.resolve(".brokk");
            Files.createDirectories(sharedBrokkDir);

            // Define shared file paths
            var agentsMdPath = gitTopLevel.resolve("AGENTS.md");
            var reviewMdPath = sharedBrokkDir.resolve("review.md");
            var projectPropsPath = sharedBrokkDir.resolve("project.properties");

            // NOTE: Legacy style.md → AGENTS.md migration is NOT performed here.
            // Migration is the orchestrator's responsibility and should be done via
            // StyleGuideMigrator before calling this method.

            // Create stub AGENTS.md if it doesn't exist (or preserve existing)
            if (!Files.exists(agentsMdPath)) {
                try {
                    Files.writeString(agentsMdPath, "# Agents Guide\n");
                    logger.debug("Created stub AGENTS.md");
                } catch (IOException ex) {
                    logger.error("Failed to create stub AGENTS.md: {}", ex.getMessage());
                }
            }

            if (!Files.exists(reviewMdPath)) {
                try {
                    Files.writeString(reviewMdPath, MainProject.DEFAULT_REVIEW_GUIDE);
                    logger.debug("Created stub review.md");
                } catch (IOException ex) {
                    logger.error("Failed to create stub review.md: {}", ex.getMessage());
                }
            }

            if (!Files.exists(projectPropsPath)) {
                try {
                    Files.writeString(projectPropsPath, "# Brokk project configuration\n");
                    logger.debug("Created stub project.properties");
                } catch (IOException ex) {
                    logger.error("Failed to create stub project.properties: {}", ex.getMessage());
                }
            }

            // Stage shared files to git
            var filesToStage = List.of(
                    new ProjectFile(gitTopLevel, "AGENTS.md"),
                    new ProjectFile(gitTopLevel, ".brokk/review.md"),
                    new ProjectFile(gitTopLevel, ".brokk/project.properties"));

            try {
                gitRepo.add(filesToStage);
                stagedFiles.addAll(filesToStage);
                logger.debug("Successfully staged shared project files to git");
                if (consoleIO != null) {
                    consoleIO.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Added shared project files (AGENTS.md, review.md, project.properties) to git");
                }
            } catch (Exception addEx) {
                logger.warn("Error staging shared project files to git: {}", addEx.getMessage());
            }

            return new SetupResult(gitignoreUpdated, stagedFiles, Optional.empty());

        } catch (Exception e) {
            logger.error("Error in setupGitIgnoreAndStageFiles", e);
            String errorMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new SetupResult(gitignoreUpdated, stagedFiles, Optional.of(errorMsg));
        }
    }
}
