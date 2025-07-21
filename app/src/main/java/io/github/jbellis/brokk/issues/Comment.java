package io.github.jbellis.brokk.issues;

import org.jetbrains.annotations.Nullable;

import java.util.Date;

public record Comment(
        String author,
        String markdownBody,
        @Nullable Date created) {}
