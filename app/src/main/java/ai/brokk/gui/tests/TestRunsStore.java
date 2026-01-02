package ai.brokk.gui.tests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public interface TestRunsStore {
    List<Run> load();

    void save(List<Run> runs);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Run(
            String id,
            int fileCount,
            String command,
            long startedAtMillis,
            @Nullable Long completedAtMillis,
            int exitCode,
            String output) {

        public Run(
                String id,
                int fileCount,
                String command,
                Instant startedAt,
                @Nullable Instant completedAt,
                int exitCode,
                String output) {
            this(
                    id,
                    fileCount,
                    command,
                    startedAt.toEpochMilli(),
                    completedAt == null ? null : completedAt.toEpochMilli(),
                    exitCode,
                    output);
        }
    }
}
