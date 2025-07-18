package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A record to hold commit details
 */
public final class CommitInfo implements ICommitInfo {
    private final GitRepo repo;
    private final String id;
    private final String message;
    private final String author;
    private final Instant date;
    private final Optional<Integer> stashIndex; // Optional stash index

    /**
     * Constructor for regular commits.
     */
    public CommitInfo(GitRepo repo, String id, String message, String author, Instant date) {
        this(repo, id, message, author, date, Optional.empty());
    }

    /**
     * Constructor for stash commits.
     */
    public CommitInfo(GitRepo repo, String id, String message, String author, Instant date, int stashIndex) {
        this(repo, id, message, author, date, Optional.of(stashIndex));
    }

    /**
     * General purpose constructor.
     */
    private CommitInfo(GitRepo repo, String id, String message, String author, Instant date, Optional<Integer> stashIndex) {
        this.repo = Objects.requireNonNull(repo);
        this.id = Objects.requireNonNull(id);
        this.message = Objects.requireNonNull(message);
        this.author = Objects.requireNonNull(author);
        this.date = Objects.requireNonNull(date);
        this.stashIndex = Objects.requireNonNull(stashIndex);
    }

    /**
     * Lists files changed in this commit compared to its primary parent.
     * For an initial commit, lists all files in that commit.
     * This method fetches data on demand.
     *
     * @return A list of relative file paths changed in this commit.
     * @throws GitAPIException if there's an error accessing Git data.
     */
    @Override
    public List<ProjectFile> changedFiles() throws GitAPIException {
        return repo.listFilesChangedInCommit(this.id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public String author() {
        return author;
    }

    @Override
    public Instant date() {
        return date;
    }

    @Override
    public Optional<Integer> stashIndex() {
        return stashIndex;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof CommitInfo that)) return false; // Pattern matching
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.message, that.message) &&
                Objects.equals(this.author, that.author) &&
                Objects.equals(this.date, that.date) && // Instant has well-defined equals
                Objects.equals(this.stashIndex, that.stashIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, message, author, date, stashIndex);
    }

    @Override
    public String toString() {
        return "CommitInfo[" +
                "id=" + id + ", " +
                "message=" + message + ", " +
                "author=" + author + ", " +
                "date=" + date + ", " +
                "stashIndex=" + stashIndex + ']'; // Include stashIndex in toString
    }
}
