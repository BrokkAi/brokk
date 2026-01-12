package ai.brokk.git;

import java.time.Instant;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public interface ICommitInfo {
    String id();

    String message();

    String author();

    @Nullable
    Instant date();

    /**
     * Returns the stash index if this commit represents a stash entry.
     *
     * @return An Optional containing the stash index (e.g., 0 for stash@{0}), or empty if not a stash.
     */
    Optional<Integer> stashIndex();

    class CommitInfoStub implements ICommitInfo {
        private final String message;

        public CommitInfoStub(String message) {
            this.message = message;
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
        public @Nullable Instant date() {
            return null;
        }

        @Override
        public Optional<Integer> stashIndex() {
            return Optional.empty();
        }
    }
}
