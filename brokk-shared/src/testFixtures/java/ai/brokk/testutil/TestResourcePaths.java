package ai.brokk.testutil;

import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TestResourcePaths {

    private TestResourcePaths() {}

    private static final Map<java.net.URI, FileSystem> jarFileSystems = new ConcurrentHashMap<>();

    public static Path resourceDirectory(String resourceDirName) {
        var url = TestResourcePaths.class.getClassLoader().getResource(resourceDirName);
        if (url == null) {
            throw new IllegalArgumentException("Missing test resource directory: " + resourceDirName);
        }

        try {
            var uri = url.toURI();
            try {
                return Path.of(uri);
            } catch (FileSystemNotFoundException e) {
                // When tests run from a jar, resources are accessed via a jar: URI. Ensure a filesystem exists.
                if (!"jar".equals(uri.getScheme())) {
                    throw e;
                }

                jarFileSystems.computeIfAbsent(uri, u -> {
                    try {
                        return FileSystems.newFileSystem(u, Map.of());
                    } catch (FileSystemAlreadyExistsException alreadyExists) {
                        return FileSystems.getFileSystem(u);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException(
                                "Failed to create jar filesystem for: " + resourceDirName, ex);
                    }
                });
                return Path.of(uri);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid resource URI for: " + resourceDirName, e);
        }
    }
}
