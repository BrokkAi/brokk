package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

public final class TempProject {
    private final Path root;
    private final CoreTestProject project;

    public TempProject(Set<Language> languages) {
        try {
            this.root = Files.createTempDirectory("brokk-test-project-")
                    .toAbsolutePath()
                    .normalize();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.project = new CoreTestProject(root, languages);
    }

    public Path root() {
        return root;
    }

    public CoreTestProject project() {
        return project;
    }

    public ProjectFile writeFile(String relPath, String contents) {
        var absPath = root.resolve(relPath).normalize();
        try {
            if (absPath.getParent() != null) {
                Files.createDirectories(absPath.getParent());
            }
            Files.writeString(absPath, contents, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ProjectFile(root, Path.of(relPath));
    }
}
