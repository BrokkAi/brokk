package io.github.jbellis.brokk.issues;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Date;
import java.util.List;

public record IssueHeader(
        String id,              // e.g., "#123" or "FOO-456"
        String title,
        String author,
        @Nullable Date   updated,
        List<String> labels,
        List<String> assignees,
        String status,
        @Nullable URI    htmlUrl) {}
