package io.github.jbellis.brokk.issues;

import java.util.Date;
import org.jetbrains.annotations.Nullable;

public record Comment(String author, String markdownBody, @Nullable Date created) {}
