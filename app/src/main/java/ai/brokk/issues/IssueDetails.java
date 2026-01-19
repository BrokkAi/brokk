package ai.brokk.issues;

import java.net.URI;
import java.util.List;

public record IssueDetails(
        IssueHeader header, String markdownBody, String htmlBody, List<Comment> comments, List<URI> attachmentUrls) {}
