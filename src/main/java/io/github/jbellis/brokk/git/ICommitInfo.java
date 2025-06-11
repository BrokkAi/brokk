package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ICommitInfo {
    List<ProjectFile> changedFiles() throws GitAPIException;

    String id();

    String message();

    String author();

    Instant date();

    /**
     * Returns the stash index if this commit represents a stash entry.
     * @return An Optional containing the stash index (e.g., 0 for stash@{0}), or empty if not a stash.
     */
    Optional<Integer> stashIndex();

    class CommitInfoStub implements ICommitInfo {
        private final String message;

        public CommitInfoStub(String message) {
            this.message = message;
        }

        @Override
        public List<ProjectFile> changedFiles() {
            return List.of();
        }

        @Override
        public String id() {
            return "";
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public String author() {
            return "";
        }

        @Override
        public Instant date() {
            return null;
        }

        @Override
        public Optional<Integer> stashIndex() {
            return Optional.empty();
        }
    }
}
