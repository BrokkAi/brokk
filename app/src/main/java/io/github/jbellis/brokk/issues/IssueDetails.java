package io.github.jbellis.brokk.issues;

import java.net.URI;
import java.util.List;

public record IssueDetails(
        IssueHeader header,
        String markdownBody,
        List<Comment> comments,
        List<URI> attachmentUrls) {}
