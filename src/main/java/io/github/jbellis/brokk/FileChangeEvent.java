package io.github.jbellis.brokk;

import java.nio.file.Path;

public record FileChangeEvent(EventType type, Path path) {
    // Internal event representation to replace DirectoryChangeEvent
    public enum EventType {
        CREATE, MODIFY, DELETE, OVERFLOW
    }
}


