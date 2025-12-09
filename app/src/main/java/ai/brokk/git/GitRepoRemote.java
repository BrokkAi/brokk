package ai.brokk.git;

import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/** Encapsulates remote-related operations for a GitRepo. Stores a reference to the owning GitRepo as `repo`. */
public class GitRepoRemote {

    /**
     * Represents a parsed remote branch reference (e.g., "origin/feature-branch").
     */
    public record RemoteBranchRef(String remoteName, String branchName) {
        /**
         * Parses a remote branch reference like "origin/feature-branch".
         * @param ref The full ref (must contain "/")
         * @return RemoteBranchRef with remote and branch parts, or null if no "/" found
         */
        @Nullable
        public static RemoteBranchRef parse(String ref) {
            int slash = ref.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            return new RemoteBranchRef(ref.substring(0, slash), ref.substring(slash + 1));
        }
    }

    private static final Logger logger = LogManager.getLogger(GitRepoRemote.class);

    private final GitRepo repo;
    private final Git git;
    private final Repository repository;

    public GitRepoRemote(GitRepo repo) {
        this.repo = repo;
        this.git = repo.getGit();
        repository = repo.getRepository();
    }

    /**
     * Determines if a push operation was successful for a specific ref update.
     *
     * <p>Kept here as a static utility so callers can reference GitRepoRemote.isPushSuccessful(...) if desired.
     */
    public static boolean isPushSuccessful(RemoteRefUpdate.Status status) {
        return status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE;
    }

    /**
     * Push the committed changes for the specified branch to the "origin" remote. This method assumes the remote is
     * "origin" and the remote branch has the same name as the local branch.
     */
    public void push(String branchName) throws GitAPIException {
        assert !branchName.isBlank();

        logger.debug("Pushing branch {} to origin", branchName);
        var refSpec = new RefSpec(String.format("refs/heads/%s:refs/heads/%s", branchName, branchName));

        var pushCommand = git.push().setRemote("origin").setRefSpecs(refSpec).setTimeout((int)
                Environment.GIT_NETWORK_TIMEOUT.toSeconds());
        repo.applyGitHubAuthentication(pushCommand, getUrl("origin"));
        Iterable<PushResult> results = pushCommand.call();
        List<String> rejectionMessages = new ArrayList<>();

        for (var result : results) {
            for (var rru : result.getRemoteUpdates()) {
                var status = rru.getStatus();
                if (!isPushSuccessful(status)) {
                    String message = "Ref '" + rru.getRemoteName() + "' (local '" + branchName + "') update failed: ";
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                            || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        message += "The remote contains work that you do not have locally. "
                                + "Pull and merge from the remote (or rebase) before pushing.";
                    } else {
                        message += status.toString();
                        if (rru.getMessage() != null) {
                            message += " (" + rru.getMessage() + ")";
                        }
                    }
                    rejectionMessages.add(message);
                }
            }
        }

        if (!rejectionMessages.isEmpty()) {
            throw new GitRepo.GitPushRejectedException(
                    "Push rejected by remote:\n" + String.join("\n", rejectionMessages));
        }
    }

    /**
     * Pushes the given local branch to the specified remote, creates upstream tracking for it, and returns the
     * PushResult list. Assumes the remote branch should have the same name as the local branch.
     */
    public Iterable<PushResult> pushAndSetRemoteTracking(String localBranchName, String remoteName)
            throws GitAPIException {
        return pushAndSetRemoteTracking(localBranchName, remoteName, localBranchName);
    }

    /**
     * Pushes the given local branch to the specified remote, creates upstream tracking for it, and returns the
     * PushResult list.
     */
    public Iterable<PushResult> pushAndSetRemoteTracking(
            String localBranchName, String remoteName, String remoteBranchName) throws GitAPIException {
        logger.debug(
                "Pushing branch {} to {}/{} and setting up remote tracking",
                localBranchName,
                remoteName,
                remoteBranchName);
        var refSpec = new RefSpec(String.format("refs/heads/%s:refs/heads/%s", localBranchName, remoteBranchName));

        // 1. Push the branch
        var pushCommand = git.push().setRemote(remoteName).setRefSpecs(refSpec).setTimeout((int)
                Environment.GIT_NETWORK_TIMEOUT.toSeconds());
        var remoteUrl = getUrl(remoteName);

        repo.applyGitHubAuthentication(pushCommand, remoteUrl);
        Iterable<PushResult> results = pushCommand.call();

        List<String> rejectionMessages = new ArrayList<>();
        for (var result : results) {
            for (var rru : result.getRemoteUpdates()) {
                var status = rru.getStatus();
                if (!isPushSuccessful(status)) {
                    String message =
                            "Ref '" + rru.getRemoteName() + "' (local '" + localBranchName + "') update failed: ";
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                            || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        message += "The remote contains work that you do not have locally. "
                                + "Pull and merge from the remote (or rebase) before pushing.";
                    } else {
                        message += status.toString();
                        if (rru.getMessage() != null) {
                            message += " (" + rru.getMessage() + ")";
                        }
                    }
                    rejectionMessages.add(message);
                }
            }
        }

        if (!rejectionMessages.isEmpty()) {
            throw new GitRepo.GitPushRejectedException(
                    "Push rejected by remote:\n" + String.join("\n", rejectionMessages));
        }

        // 2. Record upstream info in config only if push was successful
        try {
            var config = repository.getConfig();
            config.setString("branch", localBranchName, "remote", remoteName);
            config.setString("branch", localBranchName, "merge", "refs/heads/" + remoteBranchName);
            config.save();
            logger.info(
                    "Successfully set up remote tracking for branch {} -> {}/{}",
                    localBranchName,
                    remoteName,
                    remoteBranchName);
        } catch (IOException e) {
            throw new GitRepo.GitRepoException(
                    "Push to " + remoteName + "/" + remoteBranchName
                            + " succeeded, but failed to set up remote tracking configuration for " + localBranchName,
                    e);
        }

        repo.invalidateCaches();

        return results;
    }

    /**
     * Fetches all remotes with pruning, reporting progress to the given monitor.
     *
     * @param pm The progress monitor to report to.
     * @throws GitAPIException if a Git error occurs.
     */
    public void fetchAll(ProgressMonitor pm) throws GitAPIException {
        for (String remoteName : repository.getRemoteNames()) {
            var fetchCommand = git.fetch()
                    .setRemote(remoteName)
                    .setRemoveDeletedRefs(true) // --prune
                    .setProgressMonitor(pm);
            repo.applyGitHubAuthentication(fetchCommand, getUrl(remoteName));
            fetchCommand.call();
        }
        repo.invalidateCaches(); // Invalidate caches & ref-db
    }

    /**
     * Fetches a specific branch from a remote.
     *
     * @param remoteName The name of the remote (e.g., "origin").
     * @param branchName The name of the branch to fetch.
     * @throws GitAPIException if a Git error occurs.
     */
    public void fetchBranch(String remoteName, String branchName) throws GitAPIException {
        var refSpec = new RefSpec("+refs/heads/" + branchName + ":refs/remotes/" + remoteName + "/" + branchName);
        var fetchCommand = git.fetch().setRemote(remoteName).setRefSpecs(refSpec);
        repo.applyGitHubAuthentication(fetchCommand, getUrl(remoteName));
        fetchCommand.call();
    }

    /**
     * Checks if a branch needs to be fetched by comparing local and remote SHAs.
     * Uses ls-remote to query the remote without downloading objects.
     *
     * @param remoteName The name of the remote (e.g., "origin").
     * @param branchName The name of the branch to check.
     * @return true if the branch has updates on remote, false if up-to-date.
     * @throws GitAPIException if a Git error occurs.
     */
    public boolean branchNeedsFetch(String remoteName, String branchName) throws GitAPIException {
        String remoteUrl = getUrl(remoteName);

        // Query remote for current SHA of this branch
        var lsRemote = Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true);
        repo.applyGitHubAuthentication(lsRemote, remoteUrl);

        var refs = lsRemote.call();
        String remoteSha = null;
        for (var ref : refs) {
            if (ref.getName().equals("refs/heads/" + branchName)) {
                var objectId = ref.getObjectId();
                if (objectId != null) {
                    remoteSha = objectId.getName();
                }
                break;
            }
        }

        if (remoteSha == null) {
            // Branch doesn't exist on remote - check if we have a stale local ref
            try {
                var localRef = repository.findRef("refs/remotes/" + remoteName + "/" + branchName);
                if (localRef != null) {
                    logger.warn(
                            "Branch '{}' no longer exists on remote '{}' but local tracking ref exists (may be stale)",
                            branchName,
                            remoteName);
                }
            } catch (IOException e) {
                logger.debug("Error checking for stale local ref {}/{}: {}", remoteName, branchName, e.getMessage());
            }
            return false;
        }

        // Get local tracking ref SHA
        try {
            var localRef = repository.findRef("refs/remotes/" + remoteName + "/" + branchName);
            if (localRef == null) {
                // No local ref, needs fetch
                return true;
            }
            var localObjectId = localRef.getObjectId();
            if (localObjectId == null) {
                // No object ID, needs fetch
                return true;
            }
            String localSha = localObjectId.getName();
            return !remoteSha.equals(localSha);
        } catch (IOException e) {
            // Error reading local ref, assume needs fetch
            logger.warn("Error reading local ref for {}/{}: {}", remoteName, branchName, e.getMessage());
            return true;
        }
    }

    /** Pull changes from the remote repo.getRepository() for the current branch */
    public void pull() throws GitAPIException {
        var pullCommand = git.pull().setTimeout((int) Environment.GIT_NETWORK_TIMEOUT.toSeconds());
        repo.applyGitHubAuthentication(pullCommand, getUrl("origin"));
        pullCommand.call();
    }

    /** Get a set of commit IDs that exist in the local branch but not in its target remote branch */
    public Set<String> getUnpushedCommitIds(String branchName) throws GitAPIException {
        var unpushedCommits = new HashSet<String>();

        // Determine the remote/branch target to compare against
        var targetRemoteBranchName = getTargetRemoteBranchName(branchName);
        if (targetRemoteBranchName == null) {
            return unpushedCommits;
        }

        // Check if the resolved remote branch actually exists
        try {
            var remoteRef = "refs/remotes/" + targetRemoteBranchName;
            if (repository.findRef(remoteRef) == null) {
                return unpushedCommits; // No remote branch to compare against
            }
        } catch (Exception e) {
            logger.debug("Error checking remote branch existence for {}: {}", targetRemoteBranchName, e.getMessage());
            return unpushedCommits;
        }

        var branchRef = "refs/heads/" + branchName;
        var remoteRef = "refs/remotes/" + targetRemoteBranchName;

        var localObjectId = repo.resolveToCommit(branchRef);
        var remoteObjectId = repo.resolveToCommit(remoteRef);

        try (var revWalk = new RevWalk(repository)) {
            try {
                revWalk.markStart(revWalk.parseCommit(localObjectId));
                revWalk.markUninteresting(revWalk.parseCommit(remoteObjectId));
            } catch (IOException e) {
                throw new GitRepo.GitWrappedIOException(e);
            }

            revWalk.forEach(commit -> unpushedCommits.add(commit.getId().getName()));
        }
        return unpushedCommits;
    }

    /**
     * Determine the preferred target remote name, including upstream resolution.
     *
     * <p>Mirrors the previous behavior: try branch upstream, remote.pushDefault, single remote, origin.
     */
    public @Nullable String getTargetRemoteName() {
        try {
            var currentBranch = repo.getCurrentBranch();
            return getTargetRemoteNameWithUpstream(currentBranch);
        } catch (GitAPIException e) {
            logger.debug("Error getting current branch, falling back to upstream-less resolution: {}", e.getMessage());
            try {
                var config = repository.getConfig();
                var remoteNames = repository.getRemoteNames();

                var pushDefault = config.getString("remote", null, "pushDefault");
                if (pushDefault != null && remoteNames.contains(pushDefault)) {
                    return pushDefault;
                }

                if (remoteNames.size() == 1) {
                    return remoteNames.iterator().next();
                }

                if (remoteNames.contains("origin")) {
                    return "origin";
                }

                return null;
            } catch (Exception ex) {
                logger.debug("Error resolving target remote name: {}", ex.getMessage());
                return null;
            }
        }
    }

    /**
     * Get the target remote name following Git's standard remote resolution order including upstream:
     *
     * <p>If upstream exists for branch (branch.<name>.remote), use that Else if remote.pushDefault is configured, use
     * that Else if exactly one remote exists, use that Else if "origin" exists, use "origin"
     */
    public @Nullable String getTargetRemoteNameWithUpstream(String branchName) {
        try {
            var config = repository.getConfig();
            var remoteNames = repository.getRemoteNames();

            var configuredRemote = config.getString("branch", branchName, "remote");
            if (configuredRemote != null && remoteNames.contains(configuredRemote)) {
                return configuredRemote;
            }

            var pushDefault = config.getString("remote", null, "pushDefault");
            if (pushDefault != null && remoteNames.contains(pushDefault)) {
                return pushDefault;
            }

            if (remoteNames.size() == 1) {
                return remoteNames.iterator().next();
            }

            if (remoteNames.contains("origin")) {
                return "origin";
            }

            return null;
        } catch (Exception e) {
            logger.debug("Error resolving target remote name with upstream for {}: {}", branchName, e.getMessage());
            return null;
        }
    }

    /** Get the URL of the specified remote (defaults to "origin") */
    public @Nullable String getUrl(String remoteName) {
        try {
            var config = repository.getConfig();
            return config.getString("remote", remoteName, "url"); // getString can return null
        } catch (Exception e) {
            logger.warn("Failed to get remote URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the URL of the target remote using Git's standard remote resolution including upstream from current branch
     */
    public @Nullable String getUrl() {
        var targetRemote = getTargetRemoteName();
        return targetRemote != null ? getUrl(targetRemote) : null;
    }

    /**
     * Get the remote name for GitHub PR operations, preferring "origin".
     * Falls back to standard remote resolution if "origin" doesn't exist.
     */
    public @Nullable String getOriginRemoteNameWithFallback() {
        var remoteNames = repository.getRemoteNames();
        if (remoteNames.contains("origin")) {
            return "origin";
        }
        return getTargetRemoteName();
    }

    /**
     * Get the URL of the origin remote with fallback to target remote.
     * Preferred for GitHub PR operations.
     */
    public @Nullable String getOriginUrlWithFallback() {
        var remoteName = getOriginRemoteNameWithFallback();
        return remoteName != null ? getUrl(remoteName) : null;
    }

    /**
     * Lists branches and tags from a remote repository URL.
     *
     * @param url The URL of the remote repository.
     * @return A RemoteInfo record containing the branches, tags, and default branch.
     * @throws GitAPIException if the remote is inaccessible or another Git error occurs.
     */
    public static GitRepo.RemoteInfo listRemoteRefs(String url) throws GitAPIException {
        return listRemoteRefs(MainProject::getGitHubToken, url);
    }

    static GitRepo.RemoteInfo listRemoteRefs(Supplier<String> tokenSupplier, String url) throws GitAPIException {
        var lsRemote = Git.lsRemoteRepository().setHeads(true).setTags(true).setRemote(url);

        // Apply GitHub authentication if needed (only for GitHub HTTPS URLs)
        if (GitRepoFactory.isGitHubHttpsUrl(url)) {
            var token = tokenSupplier.get();
            if (!token.trim().isEmpty()) {
                logger.debug("Using GitHub token authentication for ls-remote: {}", url);
                lsRemote.setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token));
            }
            // Don't throw if token is empty - allow graceful failure for public repos
        }

        var remoteRefs = lsRemote.call();

        var branches = new ArrayList<String>();
        var tags = new ArrayList<String>();
        String defaultBranch = null;

        for (var ref : remoteRefs) {
            String name = ref.getName();
            if (name.startsWith("refs/heads/")) {
                branches.add(name.substring("refs/heads/".length()));
            } else if (name.startsWith("refs/tags/")) {
                tags.add(name.substring("refs/tags/".length()));
            } else if (name.equals("HEAD")) {
                var target = ref.getTarget();
                if (target.isSymbolic() && target.getName().startsWith("refs/heads/")) {
                    defaultBranch = target.getName().substring("refs/heads/".length());
                }
            }
        }
        Collections.sort(branches);
        Collections.sort(tags);
        return new GitRepo.RemoteInfo(url, branches, tags, defaultBranch);
    }

    /**
     * Get the target remote name and branch following Git's standard remote resolution order, with fallback to the next
     * option if a remote branch doesn't exist. Returns remote/branch format (e.g., "origin/main").
     *
     * <p>Resolution order:
     *
     * <ol>
     *   <li>Configured upstream branch if it exists
     *   <li>remote.pushDefault with branch name if it exists
     *   <li>Single remote with branch name if it exists
     *   <li>origin with branch name if it exists
     *   <li>Configured upstream even if it doesn't exist (for push targets)
     *   <li>pushDefault even if it doesn't exist
     *   <li>origin even if it doesn't exist
     * </ol>
     */
    @VisibleForTesting
    @Nullable
    String getTargetRemoteBranchName(String branchName) {
        try {
            var config = repository.getConfig();
            var remoteNames = repository.getRemoteNames();

            // 1. Check for configured upstream first
            var configuredRemote = config.getString("branch", branchName, "remote");
            var configuredMerge = config.getString("branch", branchName, "merge");

            if (configuredRemote != null && configuredMerge != null && remoteNames.contains(configuredRemote)) {
                var remoteBranch = configuredMerge;
                if (remoteBranch.startsWith("refs/heads/")) {
                    remoteBranch = remoteBranch.substring("refs/heads/".length());
                }
                var upstreamTarget = configuredRemote + "/" + remoteBranch;

                // Check if upstream branch exists, if so use it
                if (repository.findRef("refs/remotes/" + upstreamTarget) != null) {
                    return upstreamTarget;
                }
                // If upstream is configured but branch doesn't exist, fall through to other options
            }

            // 2. Check for remote.pushDefault
            var pushDefault = config.getString("remote", null, "pushDefault");
            if (pushDefault != null && remoteNames.contains(pushDefault)) {
                var pushDefaultTarget = pushDefault + "/" + branchName;
                if (repository.findRef("refs/remotes/" + pushDefaultTarget) != null) {
                    return pushDefaultTarget;
                }
            }

            // 3. If exactly one remote exists, use that
            if (remoteNames.size() == 1) {
                var remoteName = remoteNames.iterator().next();
                var singleRemoteTarget = remoteName + "/" + branchName;
                if (repository.findRef("refs/remotes/" + singleRemoteTarget) != null) {
                    return singleRemoteTarget;
                }
            }

            // 4. Fall back to origin if it exists
            if (remoteNames.contains("origin")) {
                var originTarget = "origin/" + branchName;
                if (repository.findRef("refs/remotes/" + originTarget) != null) {
                    return originTarget;
                }
            }

            // 5. No suitable remote branch found - return the first preference even if it doesn't exist
            // This preserves the resolution order for cases where no remote branch exists yet
            if (configuredRemote != null && configuredMerge != null && remoteNames.contains(configuredRemote)) {
                var remoteBranch = configuredMerge;
                if (remoteBranch.startsWith("refs/heads/")) {
                    remoteBranch = remoteBranch.substring("refs/heads/".length());
                }
                return configuredRemote + "/" + remoteBranch;
            }

            if (pushDefault != null && remoteNames.contains(pushDefault)) {
                return pushDefault + "/" + branchName;
            }

            if (remoteNames.size() == 1) {
                var remoteName = remoteNames.iterator().next();
                return remoteName + "/" + branchName;
            }

            if (remoteNames.contains("origin")) {
                return "origin/" + branchName;
            }

            return null;
        } catch (Exception e) {
            logger.debug("Error resolving target remote branch name for {}: {}", branchName, e.getMessage());
            return null;
        }
    }

    public boolean branchNeedsPush(String branch) throws GitAPIException {
        if (!repo.listLocalBranches().contains(branch)) {
            return false; // Not a local branch, so it cannot need pushing
        }

        // Get the target remote name (with built-in fallback logic)
        var targetRemoteBranchName = getTargetRemoteBranchName(branch);
        if (targetRemoteBranchName == null) {
            return true; // No target remote found, so needs push
        }

        // Check if the resolved remote branch actually exists
        try {
            var remoteRef = "refs/remotes/" + targetRemoteBranchName;
            if (repository.findRef(remoteRef) == null) {
                return true; // Remote branch doesn't exist, so needs push
            }
        } catch (Exception e) {
            logger.debug("Error checking remote branch existence for {}: {}", targetRemoteBranchName, e.getMessage());
            return true; // Assume needs push on error
        }

        // Remote branch exists, check if local has unpushed commits
        return !getUnpushedCommitIds(branch).isEmpty();
    }

    /**
     * Ensures a commit SHA is available locally by fetching a specific refSpec from a remote.
     *
     * @param sha The commit SHA that must be present locally
     * @param refSpec The refSpec to fetch (e.g. "+refs/pull/123/head:refs/remotes/origin/pr/123/head")
     * @param remoteName The remote to fetch from
     * @return true if the SHA is now available locally, false otherwise
     */
    public boolean ensureShaIsLocal(String sha, String refSpec, String remoteName) {
        if (repo.isCommitLocallyAvailable(sha)) {
            return true;
        }

        logger.debug("SHA {} not available locally - fetching {} from {}", sha, refSpec, remoteName);
        try {
            var fetchCommand =
                    git.fetch().setRemote(remoteName).setRefSpecs(new org.eclipse.jgit.transport.RefSpec(refSpec));
            repo.applyGitHubAuthentication(fetchCommand, getUrl(remoteName));
            fetchCommand.call();
            if (repo.isCommitLocallyAvailable(sha)) {
                logger.debug("Successfully fetched and verified SHA {}", sha);
                repo.invalidateCaches();
                return true;
            } else {
                logger.warn(
                        "Failed to make SHA {} available locally even after fetching {} from {}",
                        sha,
                        refSpec,
                        remoteName);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Error during fetch operation for SHA {}: {}", sha, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks if a commit's data is fully available and parsable in the local repository.
     *
     * @param sha The commit SHA to check
     * @return true if the commit is resolvable and its object data is parsable, false otherwise
     */
    public boolean isCommitLocallyAvailable(String sha) {
        return repo.isCommitLocallyAvailable(sha);
    }
}
