package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Creates temporary projects from source code defined by content-filename pairs. This is cleaned up once closed.
 */
public class InlineTestProjectCreator {

    private InlineTestProjectCreator() {}

    private interface ProjectContentStrategy {
        void populate(Path root) throws IOException;

        Set<Language> detectLanguages();

        List<String> getFilesForInitialCommit();
    }

    private static class InlineContentStrategy implements ProjectContentStrategy {
        private final List<FileContents> entries = new ArrayList<>();

        public void addFileContents(String contents, String filename) {
            entries.add(new FileContents(filename, contents));
        }

        @Override
        public void populate(Path root) throws IOException {
            for (var entry : entries) {
                var absPath = root.resolve(entry.relPath);
                Files.createDirectories(absPath.getParent());
                Files.writeString(absPath, entry.contents, StandardOpenOption.CREATE_NEW);
            }
        }

        @Override
        public Set<Language> detectLanguages() {
            Set<Language> detected = new LinkedHashSet<>();
            for (var entry : entries) {
                var filename = Path.of(entry.relPath).getFileName().toString();
                int dot = filename.lastIndexOf('.');
                if (dot <= 0 || dot == filename.length() - 1) {
                    continue;
                }
                var ext = filename.substring(dot + 1);
                for (var lang : Languages.ALL_LANGUAGES) {
                    if (lang.getExtensions().contains(ext)) {
                        detected.add(lang);
                    }
                }
            }
            return detected;
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return entries.stream().map(FileContents::relPath).toList();
        }

        public boolean hasFile(String filename) {
            return entries.stream().anyMatch(e -> e.relPath.equals(filename));
        }
    }

    private static class ZipContentStrategy implements ProjectContentStrategy {
        private final Path zipPath;

        public ZipContentStrategy(Path zipPath) {
            this.zipPath = zipPath;
        }

        @Override
        public void populate(Path root) throws IOException {
            try (var fis = Files.newInputStream(zipPath);
                    var zis = new ZipInputStream(fis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) {
                        throw new IOException("Zip entry outside of root: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.copy(zis, target);
                    }
                    zis.closeEntry();
                }
            }
        }

        @Override
        public Set<Language> detectLanguages() {
            return Set.of(); // Handled by post-population scan in builder
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return List.of("."); // Add everything
        }
    }

    private static class GitCloneStrategy implements ProjectContentStrategy {
        private static final Path CACHE_ROOT = Path.of(System.getProperty("user.home"), ".brokk", "test-cache", "git");

        private final String url;
        private final String ref;

        public GitCloneStrategy(String url, String ref) {
            this.url = url;
            this.ref = ref;
        }

        @Override
        public void populate(Path root) throws IOException {
            Files.createDirectories(CACHE_ROOT);
            String cacheKey = hash(url);
            Path cachePath = CACHE_ROOT.resolve(cacheKey);

            try {
                if (!Files.exists(cachePath)) {
                    GitRepoFactory.cloneRepo(url, cachePath, 0);
                }

                // Clone from cache to target root
                GitRepoFactory.cloneRepo(cachePath.toUri().toString(), root, 0, ref);
            } catch (GitAPIException e) {
                throw new IOException("Failed to clone repository: " + url, e);
            }
        }

        @Override
        public Set<Language> detectLanguages() {
            return Set.of(); // Post-population scan
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return List.of(); // Already initialized by clone
        }

        private static String hash(String input) {
            try {
                var md = MessageDigest.getInstance("SHA-256");
                byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
                return String.format("%064x", new BigInteger(1, messageDigest));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static TestProjectBuilder code(String contents, String filename) {
        return fromInline(contents, filename);
    }

    public static TestProjectBuilder fromInline(String contents, String filename) {
        return new TestProjectBuilder(new InlineContentStrategy()).addFileContents(contents, filename);
    }

    public static TestProjectBuilder fromZip(Path zipPath) {
        return new TestProjectBuilder(new ZipContentStrategy(zipPath));
    }

    public static TestProjectBuilder fromGitUrl(String url, String ref) {
        return new TestProjectBuilder(new GitCloneStrategy(url, ref)).withGit();
    }

    public static class TestProjectBuilder {
        protected final ProjectContentStrategy strategy;

        protected TestProjectBuilder(ProjectContentStrategy strategy) {
            this.strategy = strategy;
        }

        public TestProjectBuilder addFileContents(String contents, String filename) {
            if (strategy instanceof InlineContentStrategy inline) {
                inline.addFileContents(contents, filename);
            } else {
                throw new UnsupportedOperationException("Adding file contents is only supported for inline content.");
            }
            return this;
        }

        public TestGitProjectBuilder withGit() {
            return new TestGitProjectBuilder(this);
        }

        public IProject build() throws IOException {
            var newTemporaryDirectory = Files.createTempDirectory("brokk-analyzer-test-");
            strategy.populate(newTemporaryDirectory);

            Set<Language> detected = strategy.detectLanguages();
            if (detected.isEmpty()) {
                detected = scanLanguages(newTemporaryDirectory);
            }

            Language selectedLang = null;
            if (detected.size() == 1) {
                selectedLang = detected.iterator().next();
            } else if (!detected.isEmpty()) {
                selectedLang = new Language.MultiLanguage(detected);
            }

            if (this instanceof TestGitProjectBuilder gitBuilder) {
                try {
                    boolean alreadyRepo = GitRepoFactory.hasGitRepo(newTemporaryDirectory);
                    if (!alreadyRepo) {
                        GitRepoFactory.initRepo(newTemporaryDirectory);
                    }

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
                        } else if (!alreadyRepo) {
                            for (var relPath : strategy.getFilesForInitialCommit()) {
                                git.add().addFilepattern(relPath).call();
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

        private Set<Language> scanLanguages(Path root) throws IOException {
            Set<Language> detected = new LinkedHashSet<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String filename = path.getFileName().toString();
                    int dot = filename.lastIndexOf('.');
                    if (dot > 0 && dot < filename.length() - 1) {
                        String ext = filename.substring(dot + 1);
                        for (Language lang : Languages.ALL_LANGUAGES) {
                            if (lang.getExtensions().contains(ext)) {
                                detected.add(lang);
                            }
                        }
                    }
                });
            }
            return detected;
        }
    }

    public static class TestGitProjectBuilder extends TestProjectBuilder {
        private record CommitOp(String fileA, String fileB) {}

        private final List<CommitOp> commits = new ArrayList<>();

        private TestGitProjectBuilder(TestProjectBuilder existing) {
            super(existing.strategy);
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
            if (strategy instanceof InlineContentStrategy inline && !inline.hasFile(filename)) {
                throw new IllegalArgumentException("File not found in project entries: " + filename);
            }
        }
    }

    private static class EphemeralTestProject extends TestProject implements ITestProject {

        private volatile IAnalyzer analyzer;

        public EphemeralTestProject(Path root) {
            super(root);
        }

        public EphemeralTestProject(Path root, Language language) {
            super(root, language);
        }

        @Override
        public IAnalyzer getAnalyzer() {
            if (analyzer == null) {
                synchronized (this) {
                    if (analyzer == null) {
                        Set<Language> languages = getAnalyzerLanguages();
                        if (languages.size() == 1) {
                            analyzer = AnalyzerCreator.createTreeSitterAnalyzer(this);
                        } else {
                            analyzer = AnalyzerCreator.createMultiAnalyzer(this, languages.toArray(new Language[0]));
                        }
                    }
                }
            }
            return analyzer;
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
