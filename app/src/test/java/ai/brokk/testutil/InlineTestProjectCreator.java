package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
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
            return Set.of();
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return List.of("."); // Add everything
        }
    }

    private static class GitCloneStrategy implements ProjectContentStrategy {
        private static final Path CACHE_ROOT =
                Path.of("build", "test-cache", "git").toAbsolutePath();

        private final String url;
        private final String ref;
        private int depth = 0;

        public GitCloneStrategy(String url, String ref) {
            this.url = url;
            this.ref = ref;
        }

        public void setDepth(int depth) {
            this.depth = depth;
        }

        @Override
        public void populate(Path root) throws IOException {
            Files.createDirectories(CACHE_ROOT);
            String cacheKey = hash(url + "|" + depth);
            Path cachePath = CACHE_ROOT.resolve(cacheKey);

            // Use an empty token for tests to allow cloning public GitHub repos without a configured token
            java.util.function.Supplier<String> noToken = () -> "";

            try {
                if (!Files.exists(cachePath)) {
                    GitRepoFactory.cloneRepo(noToken, url, cachePath, depth);
                }

                // Clone from cache to target root.
                // GitRepoFactory.cloneRepo with branch/tag selection works for branches and tags.
                // For SHAs, we clone the default then checkout.
                boolean isSha = ref.matches("^[0-9a-f]{7,40}$");
                if (!isSha) {
                    try {
                        GitRepoFactory.cloneRepo(noToken, cachePath.toUri().toString(), root, depth, ref);
                    } catch (GitAPIException e) {
                        isSha = true; // Try SHA logic as fallback
                    }
                }

                if (isSha) {
                    try (GitRepo ignored =
                            GitRepoFactory.cloneRepo(noToken, cachePath.toUri().toString(), root, depth)) {
                        try (Git git = Git.open(root.toFile())) {
                            git.checkout().setName(ref).call();
                        }
                    } catch (GitAPIException e) {
                        throw new IOException("Failed to checkout commit SHA: " + ref, e);
                    }
                }

                if (Boolean.getBoolean("brokk.test.debug.git")) {
                    System.out.println("Files in root after clone/checkout of " + ref + ":");
                    try (var s = Files.walk(root)) {
                        s.limit(20).forEach(System.out::println);
                    }
                }
            } catch (GitAPIException e) {
                throw new IOException("Failed to clone repository: " + url + " at ref: " + ref, e);
            }
        }

        @Override
        public Set<Language> detectLanguages() {
            return Set.of();
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

    public static TestGitProjectBuilder fromGitUrl(String url, String ref) {
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

        public ITestProject build() throws IOException {
            var newTemporaryDirectory = Files.createTempDirectory("brokk-analyzer-test-");
            strategy.populate(newTemporaryDirectory);

            EphemeralTestProject project;
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
                    project = new EphemeralTestGitProject(newTemporaryDirectory, new GitRepo(newTemporaryDirectory));
                    project.setHasGit(true);
                } catch (GitAPIException e) {
                    throw new IOException("Failed to initialize git repo for test project", e);
                }
            } else {
                project = new EphemeralTestProject(newTemporaryDirectory);
            }

            // Ensure languages are set before getting analyzer
            Set<Language> detected = strategy.detectLanguages();
            if (detected.isEmpty()) {
                detected = scanLanguages(newTemporaryDirectory);
            }
            if (!detected.isEmpty()) {
                project.setAnalyzerLanguages(detected);
            }

            // Force an initial update so the analyzer is populated with code units
            IAnalyzer analyzer = project.getAnalyzer();
            project.analyzer = analyzer.update();
            return project;
        }

        private Set<Language> scanLanguages(Path root) throws IOException {
            Set<Language> detected = new LinkedHashSet<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String filename = path.getFileName().toString();

                    // Detect by specific project files first
                    if (filename.equals("pyproject.toml") || filename.equals("requirements.txt")) {
                        detected.add(Languages.PYTHON);
                    } else if (filename.equals("Cargo.toml")) {
                        detected.add(Languages.RUST);
                    }

                    // Then by extension
                    int dot = filename.lastIndexOf('.');
                    if (dot >= 0 && dot < filename.length() - 1) {
                        String ext = filename.substring(dot + 1);
                        Language lang = Languages.fromExtension(ext);
                        if (lang != Languages.NONE) {
                            detected.add(lang);
                        }
                    }
                });
            }
            detected.remove(Languages.NONE);
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

        public TestGitProjectBuilder withDepth(int depth) {
            if (strategy instanceof GitCloneStrategy gitCloneStrategy) {
                gitCloneStrategy.setDepth(depth);
            }
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
                        // Filter out NONE
                        List<Language> activeLanguages = languages.stream()
                                .filter(l -> l != Languages.NONE)
                                .toList();

                        if (activeLanguages.isEmpty()) {
                            analyzer = Languages.NONE.createAnalyzer(this);
                        } else {
                            // Set the primary build language to the first detected one if not already set
                            if (getBuildLanguage() == Languages.NONE) {
                                setBuildLanguage(activeLanguages.getFirst());
                            }

                            if (activeLanguages.size() == 1) {
                                // Important: use the detected language to create the analyzer
                                analyzer = activeLanguages.getFirst().createAnalyzer(this);
                            } else {
                                analyzer = AnalyzerCreator.createMultiAnalyzer(
                                        this, activeLanguages.toArray(new Language[0]));
                            }
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
