package ai.brokk.issues;

import java.time.Instant;
import org.jetbrains.annotations.Nullable;

public record Comment(String author, String markdownBody, @Nullable Instant created) {}
