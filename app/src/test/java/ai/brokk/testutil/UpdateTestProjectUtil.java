package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;

/** Small helpers shared by app-only tests to create temp projects and edit files. */
public final class UpdateTestProjectUtil {

    private UpdateTestProjectUtil() {}

    public static Path newTempDir() throws IOException {
        Path dir = Files.createTempDirectory("brokk-update-");
        dir.toFile().deleteOnExit();
        return dir;
    }

    public static void writeFile(Path root, String relative, String contents) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, contents);
    }

    public static TestProject newTestProject(Path root, Language lang) {
        Assertions.assertTrue(Files.exists(root));
        return new TestProject(root, lang);
    }
}
