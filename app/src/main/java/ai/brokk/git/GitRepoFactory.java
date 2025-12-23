package ai.brokk.git;

import ai.brokk.project.MainProject;
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
import org.eclipse.jgit.lib.ProgressMonitor;
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
     * <p>If the URL looks like a plain GitHub HTTPS repo without ".git" (e.g. https://github.com/Owner/Repo) we
     * automatically append ".git".
     */
    public static GitRepo cloneRepo(String remoteUrl, Path directory, int depth) throws GitAPIException {
        return cloneRepoInternal(MainProject::getGitHubToken, remoteUrl, directory, depth, null, null);
    }

    /**
     * Clones a remote repository with cancellation support via ProgressMonitor.
     */
    public static GitRepo cloneRepo(String remoteUrl, Path directory, int depth, ProgressMonitor monitor)
            throws GitAPIException {
        return cloneRepoInternal(MainProject::getGitHubToken, remoteUrl, directory, depth, null, monitor);
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
        return cloneRepoInternal(MainProject::getGitHubToken, remoteUrl, directory, depth, branchOrTag, null);
    }

    // Package-private for testing
    static GitRepo cloneRepo(Supplier<String> tokenSupplier, String remoteUrl, Path directory, int depth)
            throws GitAPIException {
        return cloneRepoInternal(tokenSupplier, remoteUrl, directory, depth, null, null);
    }

    static GitRepo cloneRepo(
            Supplier<String> tokenSupplier, String remoteUrl, Path directory, int depth, @Nullable String branchOrTag)
            throws GitAPIException {
        return cloneRepoInternal(tokenSupplier, remoteUrl, directory, depth, branchOrTag, null);
    }

    private static GitRepo cloneRepoInternal(
            Supplier<String> tokenSupplier,
            String remoteUrl,
            Path directory,
            int depth,
            @Nullable String branchOrTag,
            @Nullable ProgressMonitor monitor)
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

            // Set progress monitor for cancellation support
            if (monitor != null) {
                cloneCmd.setProgressMonitor(monitor);
            }

            // Apply GitHub authentication if needed
            if (isGitHubHttpsUrl(effectiveUrl)) {
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

    /**
     * Checks if a URL is a GitHub HTTPS URL that requires token authentication.
     *
     * @param url The URL to check (may be null)
     * @return true if the URL is a non-null HTTPS URL containing "github.com"
     */
    public static boolean isGitHubHttpsUrl(@Nullable String url) {
        return url != null && url.startsWith("https://") && url.contains("github.com");
    }

    /**
     * Gets the HEAD commit hash from a local Git repository.
     *
     * @param repoDir path to the repository directory
     * @return the full commit hash, or null if it cannot be determined
     */
    public static @Nullable String getHeadCommit(Path repoDir) {
        try (var git = Git.open(repoDir.toFile())) {
            var head = git.getRepository().resolve("HEAD");
            return head != null ? head.name() : null;
        } catch (Exception e) {
            logger.debug("Could not read HEAD commit from {}: {}", repoDir, e.getMessage());
            return null;
        }
    }

    /**
     * Gets the commit hash for a ref (branch or tag) from a remote repository using ls-remote.
     * This is a network operation but much faster than a full clone.
     *
     * @param remoteUrl the remote repository URL
     * @param ref the branch or tag name
     * @return the full commit hash, or null if the ref cannot be found
     */
    public static @Nullable String getRemoteRefCommit(String remoteUrl, String ref) {
        return getRemoteRefCommit(MainProject::getGitHubToken, remoteUrl, ref);
    }

    static @Nullable String getRemoteRefCommit(Supplier<String> tokenSupplier, String remoteUrl, String ref) {
        try {
            var lsRemote =
                    Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true).setTags(true);

            // Apply GitHub authentication if needed (only for GitHub HTTPS URLs)
            if (isGitHubHttpsUrl(remoteUrl)) {
                var token = tokenSupplier.get();
                if (!token.trim().isEmpty()) {
                    logger.debug("Using GitHub token authentication for ls-remote: {}", remoteUrl);
                    lsRemote.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token));
                }
                // Don't throw if token is empty - allow graceful failure for public repos
            }

            var refs = lsRemote.call();

            for (var remoteRef : refs) {
                var refName = remoteRef.getName();
                if (refName.equals("refs/heads/" + ref) || refName.equals("refs/tags/" + ref)) {
                    var objectId = remoteRef.getObjectId();
                    return objectId != null ? objectId.name() : null;
                }
            }

            logger.debug("Could not find ref {} in remote {}", ref, remoteUrl);
            return null;
        } catch (Exception e) {
            logger.debug("Failed to check remote {} for ref {}: {}", remoteUrl, ref, e.getMessage());
            return null;
        }
    }
}
