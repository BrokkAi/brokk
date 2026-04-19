package ai.brokk.testutil;

import java.net.URISyntaxException;
import java.nio.file.Path;

public final class TestResourcePaths {

    private TestResourcePaths() {}

    public static Path resourceDirectory(String resourceDirName) {
        var url = TestResourcePaths.class.getClassLoader().getResource(resourceDirName);
        if (url == null) {
            throw new IllegalArgumentException("Missing test resource directory: " + resourceDirName);
        }

        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid resource URI for: " + resourceDirName, e);
        }
    }
}
