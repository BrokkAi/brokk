package io.github.jbellis.brokk.gui.tests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RunRecord(
        String id,
        int fileCount,
        String command,
        Instant startedAt,
        @Nullable Instant completedAt,
        int exitCode,
        String output) {}
