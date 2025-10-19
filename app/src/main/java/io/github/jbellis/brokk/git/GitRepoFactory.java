package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.MainProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jetbrains.annotations.Nullable;

public class GitRepoFactory {
    private static final Logger logger = LogManager.getLogger(GitRepoFactory.class);

    /**
     * Returns true if the directory has a .git folder, is a valid repository, and contains at least one local branch.
     */
    public static boolean hasGitRepo(Path dir) {
        try {
            var builder = new FileRepositoryBuilder().findGitDir(dir.toFile());
            if (builder.getGitDir() == null) {
                return false;
            }
            try (var repo = builder.build()) {
                // A valid repo for Brokk must have at least one local branch
                return !repo.getRefDatabase().getRefsByPrefix("refs/heads/").isEmpty();
            }
        } catch (IOException e) {
            // Corrupted or unreadable repo -> treat as non-git
            logger.warn("Could not read git repo at {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Initializes a new Git repository in the specified root directory. Creates a .gitignore file with a .brokk/ entry
     * if it doesn't exist or if .brokk/ is missing.
     *
     * @param root The path to the directory where the Git repository will be initialized.
     * @throws GitAPIException If an error occurs during Git initialization.
     * @throws IOException If an I/O error occurs while creating or modifying .gitignore.
     */
    public static void initRepo(Path root) throws GitAPIException, IOException {
        logger.info("Initializing new Git repository at {}", root);
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            logger.info("Git repository initialized at {}.", root);
            ensureBrokkIgnored(root);
            git.commit()
                    .setAllowEmpty(true)
                    .setMessage("Initial commit")
                    .setSign(false)
                    .call();
        }
    }

    private static void ensureBrokkIgnored(Path root) throws IOException {
        Path gitignorePath = root.resolve(".gitignore");
        String brokkDirEntry = ".brokk/";

        if (!Files.exists(gitignorePath)) {
            Files.writeString(gitignorePath, brokkDirEntry + "\n", StandardCharsets.UTF_8);
            logger.info("Created default .gitignore file with '{}' entry at {}.", brokkDirEntry, gitignorePath);
        } else {
            List<String> lines = Files.readAllLines(gitignorePath, StandardCharsets.UTF_8);
            boolean entryExists = lines.stream()
                    .anyMatch(line -> line.trim().equals(brokkDirEntry.trim())
                            || line.trim().equals(brokkDirEntry.substring(0, brokkDirEntry.length() - 1)));

            if (!entryExists) {
                // Append with a newline ensuring not to add multiple blank lines if file ends with one
                String contentToAppend = (lines.isEmpty() || lines.getLast().isBlank())
                        ? brokkDirEntry + "\n"
                        : "\n" + brokkDirEntry + "\n";
                Files.writeString(gitignorePath, contentToAppend, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                logger.info("Appended '{}' entry to existing .gitignore file at {}.", brokkDirEntry, gitignorePath);
            } else {
                logger.debug("'{}' entry already exists in .gitignore file at {}.", brokkDirEntry, gitignorePath);
            }
        }
    }

    /**
     * Clones a remote repository into {@code directory}. If {@code depth} &gt; 0 a shallow clone of that depth is
     * performed, otherwise a full clone is made.
     *
     * <p>If the URL looks like a plain GitHub HTTPS repo without “.git” (e.g. https://github.com/Owner/Repo) we
     * automatically append “.git”.
     */
    public static GitRepo cloneRepo(String remoteUrl, Path directory, int depth) throws GitAPIException {
        return cloneRepo(() -> MainProject.getGitHubToken(), remoteUrl, directory, depth);
    }

    static GitRepo cloneRepo(Supplier<String> tokenSupplier, String remoteUrl, Path directory, int depth)
            throws GitAPIException {
        String effectiveUrl = normalizeRemoteUrl(remoteUrl);

        // Ensure the target directory is empty (or doesn't yet exist)
        if (Files.exists(directory)
                && directory.toFile().list() != null
                && directory.toFile().list().length > 0) {
            throw new IllegalArgumentException("Target directory " + directory + " must be empty or not yet exist");
        }

        try {
            var cloneCmd = Git.cloneRepository()
                    .setURI(effectiveUrl)
                    .setDirectory(directory.toFile())
                    .setCloneAllBranches(depth <= 0);

            // Apply GitHub authentication if needed
            if (effectiveUrl.startsWith("https://") && effectiveUrl.contains("github.com")) {
                var token = tokenSupplier.get();
                if (!token.trim().isEmpty()) {
                    cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token));
                } else {
                    throw new GitHubAuthenticationException("GitHub token required for HTTPS authentication. "
                            + "Configure in Settings -> Global -> GitHub, or use SSH URL instead.");
                }
            }

            if (depth > 0) {
                cloneCmd.setDepth(depth);
                cloneCmd.setNoTags();
            }
            // Perform clone and immediately close the returned Git handle
            try (var ignored = cloneCmd.call()) {
                // nothing – resources closed via try-with-resources
            }
            return new GitRepo(directory, tokenSupplier);
        } catch (GitAPIException e) {
            logger.error("Failed to clone {} into {}: {}", effectiveUrl, directory, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Clone a repository to the specified directory with branch/tag selection.
     *
     * @param remoteUrl the URL of the remote repository
     * @param directory the local directory to clone into (must be empty or not exist)
     * @param depth clone depth (0 for full clone, > 0 for shallow)
     * @param branchOrTag specific branch or tag to clone (null for default branch)
     * @return a GitRepo instance for the cloned repository
     * @throws GitAPIException if the clone fails
     */
    public static GitRepo cloneRepo(String remoteUrl, Path directory, int depth, @Nullable String branchOrTag)
            throws GitAPIException {
        return cloneRepo(MainProject::getGitHubToken, remoteUrl, directory, depth, branchOrTag);
    }

    static GitRepo cloneRepo(
            Supplier<String> tokenSupplier, String remoteUrl, Path directory, int depth, @Nullable String branchOrTag)
            throws GitAPIException {
        String effectiveUrl = normalizeRemoteUrl(remoteUrl);

        // Ensure the target directory is empty (or doesn't yet exist)
        if (Files.exists(directory)
                && directory.toFile().list() != null
                && directory.toFile().list().length > 0) {
            throw new IllegalArgumentException("Target directory " + directory + " must be empty or not yet exist");
        }

        try {
            var cloneCmd = Git.cloneRepository()
                    .setURI(effectiveUrl)
                    .setDirectory(directory.toFile())
                    .setCloneAllBranches(depth <= 0);

            // Apply GitHub authentication if needed
            if (effectiveUrl.startsWith("https://") && effectiveUrl.contains("github.com")) {
                var token = tokenSupplier.get();
                if (!token.trim().isEmpty()) {
                    cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token));
                } else {
                    throw new GitHubAuthenticationException("GitHub token required for HTTPS authentication. "
                            + "Configure in Settings -> Global -> GitHub, or use SSH URL instead.");
                }
            }

            if (branchOrTag != null && !branchOrTag.trim().isEmpty()) {
                cloneCmd.setBranch(branchOrTag);
            }

            if (depth > 0) {
                cloneCmd.setDepth(depth);
                cloneCmd.setNoTags();
            }
            // Perform clone and immediately close the returned Git handle
            try (var ignored = cloneCmd.call()) {
                // nothing – resources closed via try-with-resources
            }
            return new GitRepo(directory, tokenSupplier);
        } catch (GitAPIException e) {
            logger.error(
                    "Failed to clone {} (branch/tag: {}) into {}: {}",
                    effectiveUrl,
                    branchOrTag,
                    directory,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /** Adds ".git" to simple GitHub HTTPS URLs when missing. */
    private static String normalizeRemoteUrl(String remoteUrl) {
        var pattern = Pattern.compile("^https://github\\.com/[^/]+/[^/]+$");
        return pattern.matcher(remoteUrl).matches() && !remoteUrl.endsWith(".git") ? remoteUrl + ".git" : remoteUrl;
    }
}
