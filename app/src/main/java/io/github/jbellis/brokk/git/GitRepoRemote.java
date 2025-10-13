package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.util.Environment;
import java.io.IOException;
import java.util.*;
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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/** Encapsulates remote-related operations for a GitRepo. Stores a reference to the owning GitRepo as `repo`. */
public class GitRepoRemote {
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
     * Lists branches and tags from a remote repository URL.
     *
     * @param url The URL of the remote repository.
     * @return A RemoteInfo record containing the branches, tags, and default branch.
     * @throws GitAPIException if the remote is inaccessible or another Git error occurs.
     */
    public static GitRepo.RemoteInfo listRemoteRefs(String url) throws GitAPIException {
        var remoteRefs = Git.lsRemoteRepository()
                .setHeads(true)
                .setTags(true)
                .setRemote(url)
                .call();

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
}
