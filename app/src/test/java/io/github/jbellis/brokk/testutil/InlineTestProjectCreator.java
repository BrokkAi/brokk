package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates temporary projects from source code defined by content-filename pairs. This is cleaned up once closed.
 */
public class InlineTestProjectCreator {

    private InlineTestProjectCreator() {}

    public static TestProjectBuilder code(String contents, String filename) {
        return new TestProjectBuilder().addFileContents(contents, filename);
    }

    public static class TestProjectBuilder {

        private final List<FileContents> entries = new ArrayList<>();

        private TestProjectBuilder() {}

        public TestProjectBuilder addFileContents(String contents, String filename) {
            entries.add(new FileContents(filename, contents));
            return this;
        }

        public IProject build() throws IOException {
            // Detect language(s) based on provided file extensions
            Set<Language> detected = new LinkedHashSet<>();
            for (var entry : entries) {
                var filename = Path.of(entry.relPath).getFileName().toString();
                int dot = filename.lastIndexOf('.');
                if (dot <= 0 || dot == filename.length() - 1) {
                    continue; // skip files without an extension or dotfiles without extension
                }
                var ext = filename.substring(dot + 1);
                for (var lang : Languages.ALL_LANGUAGES) {
                    if (lang.getExtensions().contains(ext)) {
                        detected.add(lang);
                    }
                }
            }

            var newTemporaryDirectory = Files.createTempDirectory("brokk-analyzer-test-");
            for (var entry : entries) {
                var absPath = newTemporaryDirectory.resolve(entry.relPath);
                Files.createDirectories(absPath.getParent());
                Files.writeString(absPath, entry.contents, StandardOpenOption.CREATE_NEW);
            }

            Language selectedLang = null;
            if (detected.size() == 1) {
                selectedLang = detected.iterator().next();
            } else if (!detected.isEmpty()) {
                selectedLang = new Language.MultiLanguage(detected);
            }

            if (selectedLang != null) {
                return new EphemeralTestProject(newTemporaryDirectory, selectedLang);
            } else {
                // No supported language detected; fall back to default behavior
                return new EphemeralTestProject(newTemporaryDirectory);
            }
        }
    }

    private static class EphemeralTestProject extends TestProject {

        public EphemeralTestProject(Path root) {
            super(root);
        }

        public EphemeralTestProject(Path root, Language language) {
            super(root, language);
        }

        @Override
        public void close() {
            FileUtil.deleteRecursively(this.getRoot());
        }
    }

    private record FileContents(String relPath, String contents) {}
}
