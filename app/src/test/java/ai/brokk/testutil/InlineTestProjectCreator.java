package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Creates temporary projects from source code defined by content-filename pairs. This is cleaned up once closed.
 */
public class InlineTestProjectCreator {

    private InlineTestProjectCreator() {}

    public static TestProjectBuilder code(String contents, String filename) {
        return new TestProjectBuilder().addFileContents(contents, filename);
    }

    public static class TestProjectBuilder {

        protected final List<FileContents> entries = new ArrayList<>();

        protected TestProjectBuilder() {}

        public TestProjectBuilder addFileContents(String contents, String filename) {
            entries.add(new FileContents(filename, contents));
            return this;
        }

        public TestGitProjectBuilder withGit() {
            return new TestGitProjectBuilder(this);
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

            if (this instanceof TestGitProjectBuilder gitBuilder) {
                try {
                    GitRepoFactory.initRepo(newTemporaryDirectory);
                    try (Git git = Git.open(newTemporaryDirectory.toFile())) {
                        if (!gitBuilder.commits.isEmpty()) {
                            for (var commit : gitBuilder.commits) {
                                git.add()
                                        .addFilepattern(commit.fileA())
                                        .addFilepattern(commit.fileB())
                                        .call();
                                git.commit()
                                        .setAuthor("Brokk Test", "test@brokk.ai")
                                        .setMessage("Test commit: " + commit.fileA() + ", " + commit.fileB())
                                        .setSign(false)
                                        .call();
                            }
                        } else {
                            for (var entry : entries) {
                                git.add().addFilepattern(entry.relPath).call();
                            }
                            git.commit()
                                    .setAuthor("Brokk Test", "test@brokk.ai")
                                    .setMessage("Initial test commit")
                                    .setSign(false)
                                    .call();
                        }
                    }
                    EphemeralTestGitProject project = selectedLang != null
                            ? new EphemeralTestGitProject(
                                    newTemporaryDirectory, selectedLang, new GitRepo(newTemporaryDirectory))
                            : new EphemeralTestGitProject(newTemporaryDirectory, new GitRepo(newTemporaryDirectory));
                    project.setHasGit(true);
                    return project;
                } catch (GitAPIException e) {
                    throw new IOException("Failed to initialize git repo for test project", e);
                }
            }

            return selectedLang != null
                    ? new EphemeralTestProject(newTemporaryDirectory, selectedLang)
                    : new EphemeralTestProject(newTemporaryDirectory);
        }
    }

    public static class TestGitProjectBuilder extends TestProjectBuilder {
        private record CommitOp(String fileA, String fileB) {}

        private final List<CommitOp> commits = new ArrayList<>();

        private TestGitProjectBuilder(TestProjectBuilder existing) {
            this.entries.addAll(existing.entries);
        }

        @Override
        public TestGitProjectBuilder addFileContents(String contents, String filename) {
            super.addFileContents(contents, filename);
            return this;
        }

        @Override
        public TestGitProjectBuilder withGit() {
            return this;
        }

        public TestGitProjectBuilder addCommit(String filenameA, String filenameB) {
            validateFileExists(filenameA);
            validateFileExists(filenameB);
            commits.add(new CommitOp(filenameA, filenameB));
            return this;
        }

        private void validateFileExists(String filename) {
            if (entries.stream().noneMatch(e -> e.relPath.equals(filename))) {
                throw new IllegalArgumentException("File not found in project entries: " + filename);
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

    private static class EphemeralTestGitProject extends EphemeralTestProject {
        private final GitRepo repo;

        public EphemeralTestGitProject(Path root, GitRepo repo) {
            super(root);
            this.repo = repo;
        }

        public EphemeralTestGitProject(Path root, Language language, GitRepo repo) {
            super(root, language);
            this.repo = repo;
        }

        @Override
        public IGitRepo getRepo() {
            return repo;
        }

        @Override
        public void close() {
            try {
                repo.close();
            } finally {
                super.close();
            }
        }
    }

    private record FileContents(String relPath, String contents) {}
}
