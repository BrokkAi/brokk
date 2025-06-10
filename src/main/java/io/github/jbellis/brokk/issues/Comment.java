package io.github.jbellis.brokk.issues;

import java.util.Date;

public record Comment(
        String author,
        String markdownBody,
        Date created) {}
