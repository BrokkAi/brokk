package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

public final class TestCodeProject {

    private TestCodeProject() {}

    public static CoreTestProject fromResourceDir(String testCodeDirName, Language language) {
        return fromResourceDir(testCodeDirName, Set.of(language));
    }

    public static CoreTestProject fromResourceDir(String testCodeDirName, Set<Language> languages) {
        Path sourceRoot = TestResourcePaths.resourceDirectory(testCodeDirName);
        Path tempRoot;
        try {
            tempRoot = Files.createTempDirectory("brokk-testcode-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        copyDirectory(sourceRoot, tempRoot);
        return new CoreTestProject(tempRoot, languages);
    }

    private static void copyDirectory(Path from, Path to) {
        try (var paths = Files.walk(from)) {
            paths.sorted(Comparator.comparingInt(p -> p.getNameCount())).forEach(path -> {
                Path dest = to.resolve(from.relativize(path).toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        if (dest.getParent() != null) {
                            Files.createDirectories(dest.getParent());
                        }
                        Files.copy(path, dest);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
