package io.github.jbellis.brokk;

import java.nio.file.Path;

public record FileChangeEvent(EventType type, Path path) { }


