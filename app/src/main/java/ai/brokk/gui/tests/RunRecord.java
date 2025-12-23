package ai.brokk.gui.tests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RunRecord(
        String id,
        int fileCount,
        String command,
        long startedAtMillis,
        @Nullable Long completedAtMillis,
        int exitCode,
        String output) {

    public RunRecord(
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
