package ai.brokk.issues;

import java.net.URI;
import java.util.Date;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record IssueHeader(
        String id, // e.g., "#123" or "FOO-456"
        String title,
        String author,
        @Nullable Date updated,
        List<String> labels,
        List<String> assignees,
        String status,
        @Nullable URI htmlUrl) {}
