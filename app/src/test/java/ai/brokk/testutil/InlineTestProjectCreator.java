package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.concurrent.AtomicWrites;
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
import java.util.Map;
import java.util.Set;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroPolicyLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates temporary projects from source code defined by content-filename pairs. This is cleaned up once closed.
 */
public class InlineTestProjectCreator {

    private static Logger log = LoggerFactory.getLogger(InlineTestProjectCreator.class);

    private InlineTestProjectCreator() {}

    private interface ProjectContentStrategy {
        void populate(Path root) throws IOException;

        Set<Language> detectLanguages();

        List<String> getFilesForInitialCommit();

        default boolean isAlreadyGit() {
            return false;
        }
    }

    private static class InlineContentStrategy implements ProjectContentStrategy {
        private final List<FileContents> entries = new ArrayList<>();

        public void addFileContents(String contents, String filename) {
            entries.add(new FileContents(filename, contents));
        }

        @Override
        public Set<Language> detectLanguages() {
            Set<Language> detected = new LinkedHashSet<>();
            for (var entry : entries) {
                String filename = Path.of(entry.relPath).getFileName().toString();
                int dot = filename.lastIndexOf('.');
                String ext = dot != -1 ? filename.substring(dot + 1) : "";
                Language lang = Languages.fromExtension(ext);
                if (lang != Languages.NONE) {
                    detected.add(lang);
                }
            }
            return detected;
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
        public Set<Language> detectLanguages() {
            return Set.of();
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
        public List<String> getFilesForInitialCommit() {
            return List.of(".");
        }
    }

    private static class DirectoryStrategy implements ProjectContentStrategy {
        private final Path sourceDir;

        public DirectoryStrategy(Path sourceDir) {
            this.sourceDir = sourceDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(this.sourceDir)) {
                throw new IllegalArgumentException("Directory does not exist: " + sourceDir);
            }
        }

        @Override
        public void populate(Path root) throws IOException {
            try (var stream = Files.walk(sourceDir)) {
                for (var sourcePath : stream.toList()) {
                    if (sourcePath.equals(sourceDir)) {
                        continue;
                    }
                    Path relativePath = sourceDir.relativize(sourcePath);
                    Path targetPath = root.resolve(relativePath).normalize();
                    if (!targetPath.startsWith(root)) {
                        throw new IOException("Path outside of root: " + relativePath);
                    }
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        if (targetPath.getParent() != null) {
                            Files.createDirectories(targetPath.getParent());
                        }
                        Files.copy(sourcePath, targetPath);
                    }
                }
            }
        }

        @Override
        public Set<Language> detectLanguages() {
            return Set.of();
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return List.of(".");
        }

        @Override
        public boolean isAlreadyGit() {
            return Files.isDirectory(sourceDir.resolve(".git"));
        }
    }

    private static class ExistingGitRepoStrategy implements ProjectContentStrategy {
        private final IGitRepo repo;
        private final Path workTreeRoot;

        ExistingGitRepoStrategy(IGitRepo repo) {
            this.repo = java.util.Objects.requireNonNull(repo);
            this.workTreeRoot = repo.getWorkTreeRoot().toAbsolutePath().normalize();
            if (!Files.isDirectory(this.workTreeRoot)) {
                throw new IllegalArgumentException("Git repo work tree does not exist: " + this.workTreeRoot);
            }
        }

        Path getWorkTreeRoot() {
            return workTreeRoot;
        }

        IGitRepo getRepo() {
            return repo;
        }

        @Override
        public void populate(Path root) {}

        @Override
        public Set<Language> detectLanguages() {
            return Set.of();
        }

        @Override
        public List<String> getFilesForInitialCommit() {
            return List.of();
        }

        @Override
        public boolean isAlreadyGit() {
            return true;
        }
    }

    static class GitCloneStrategy implements ProjectContentStrategy {
        private static Path CACHE_ROOT = Path.of("build", "test-cache", "git").toAbsolutePath();
        private static final Map<Path, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

        static void setCacheRoot(Path path) {
            CACHE_ROOT = path.toAbsolutePath();
        }

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
        public boolean isAlreadyGit() {
            return true;
        }

        @Override
        public Set<Language> detectLanguages() {
            return Set.of();
        }

        @Override
        public void populate(Path root) throws IOException {
            // Bypass cache for local file URLs to avoid infinite cache growth (local temp paths change)
            if (url.startsWith("file:")) {
                try {
                    // Clone directly from the file URL
                    cloneAndCheckout(root, url, ref, depth, () -> "");
                } catch (GitAPIException e) {
                    throw new IOException("Failed to clone local repository: " + url, e);
                }
                return;
            }

            // Use an empty token for tests to allow cloning public GitHub repos without a configured token
            Supplier<String> noToken = () -> "";

            Path expandedPath = null;
            try {
                Files.createDirectories(CACHE_ROOT);
                String cacheKey = hash(url + "|" + depth);
                Path archivePath = CACHE_ROOT.resolve(cacheKey + ".tar.lz4");
                // Option 2: Use per-call temporary expansion directory
                expandedPath = Files.createTempDirectory(CACHE_ROOT, cacheKey + ".expanded-");

                ensureCachedRepoAvailable(archivePath, expandedPath, noToken);
                String sourceUrl = expandedPath.toUri().toString();

                try {
                    cloneAndCheckout(root, sourceUrl, ref, depth, noToken);
                } catch (GitAPIException e) {
                    throw new IOException("Failed to clone or checkout ref: " + ref, e);
                }

                log.trace("Files in root after clone/checkout of {}:", ref);
                try (var s = Files.walk(root)) {
                    s.limit(20).forEach(f -> log.trace(f.toString()));
                }
            } finally {
                if (expandedPath != null) {
                    FileUtil.deleteRecursively(expandedPath);
                }
            }
        }

        private void cloneAndCheckout(
                Path root, String sourceUrl, String ref, int depth, Supplier<String> tokenSupplier)
                throws GitAPIException, IOException {
            boolean isSha = ref.matches("^[0-9a-f]{7,40}$");
            if (!isSha) {
                try {
                    GitRepoFactory.cloneRepo(tokenSupplier, sourceUrl, root, depth, ref, true);
                    return;
                } catch (GitAPIException e) {
                    // Fallback to clone default + checkout
                }
            }

            try (GitRepo ignored = GitRepoFactory.cloneRepo(tokenSupplier, sourceUrl, root, depth, null, true)) {
                try (Git git = Git.open(root.toFile())) {
                    git.checkout().setName(ref).call();
                }
            }
        }

        private void ensureCachedRepoAvailable(Path archivePath, Path expandedPath, Supplier<String> noToken)
                throws IOException {
            synchronized (CACHE_LOCKS.computeIfAbsent(archivePath, k -> new Object())) {
                if (Files.exists(archivePath)) {
                    try {
                        extractArchive(archivePath, expandedPath);
                        return;
                    } catch (IOException e) {
                        // If extraction fails, archive might be corrupt. Re-clone.
                        // For Option 2, expandedPath is unique, so we can just clear it and try again.
                        FileUtil.deleteRecursively(expandedPath);
                        Files.deleteIfExists(archivePath);
                    }
                }

                Path tempCloneDir = Files.createTempDirectory(CACHE_ROOT, "git-clone-");
                try {
                    try {
                        GitRepoFactory.cloneRepo(noToken, url, tempCloneDir, depth, null, true);

                        AtomicWrites.save(archivePath, out -> {
                            try (var lz4Out = new LZ4FrameOutputStream(out);
                                    var tarOut = new TarArchiveOutputStream(lz4Out)) {
                                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                                try (var stream = Files.walk(tempCloneDir)) {
                                    List<Path> paths = stream.toList();
                                    for (Path path : paths) {
                                        String entryName =
                                                tempCloneDir.relativize(path).toString();
                                        if (entryName.isEmpty()) continue;
                                        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
                                        tarOut.putArchiveEntry(entry);
                                        if (Files.isRegularFile(path)) {
                                            Files.copy(path, tarOut);
                                        }
                                        tarOut.closeArchiveEntry();
                                    }
                                }
                            }
                        });

                        // Move the fresh clone to expandedPath
                        // expandedPath (temp dir) might exist empty from createTempDirectory in caller.
                        // Files.move with REPLACE_EXISTING works if target is empty dir or file.
                        if (Files.exists(expandedPath)) {
                            FileUtil.deleteRecursively(expandedPath);
                        }
                        Files.move(tempCloneDir, expandedPath);
                    } catch (GitAPIException | IOException | RuntimeException e) {
                        FileUtil.deleteRecursively(expandedPath);
                        Files.deleteIfExists(archivePath);
                        throw new IOException("Failed to cache repository: " + url, e);
                    }
                } finally {
                    FileUtil.deleteRecursively(tempCloneDir);
                }
            }
        }

        private void extractArchive(Path archivePath, Path destination) throws IOException {
            Files.createDirectories(destination);
            try (var fis = Files.newInputStream(archivePath);
                    var lz4In = new LZ4FrameInputStream(fis);
                    var tarIn = new TarArchiveInputStream(lz4In)) {
                TarArchiveEntry entry;
                while ((entry = tarIn.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    Path entryPath = destination.resolve(name).normalize();
                    if (!entryPath.startsWith(destination)) {
                        throw new IOException("Tar entry outside of root (path traversal attempt?): " + name);
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        if (entryPath.getParent() != null) {
                            Files.createDirectories(entryPath.getParent());
                        }
                        Files.copy(tarIn, entryPath);
                    }
                }
            }
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
        return empty().addFileContents(contents, filename);
    }

    public static TestProjectBuilder empty() {
        return new TestProjectBuilder(new InlineContentStrategy());
    }

    public static TestProjectBuilder at(Path dir) {
        return new TestProjectBuilder(new DirectoryStrategy(dir));
    }

    public static TestProjectBuilder fromZip(Path zipPath) {
        return new TestProjectBuilder(new ZipContentStrategy(zipPath));
    }

    public static TestGitProjectBuilder fromGitUrl(String url, String ref) {
        return new TestProjectBuilder(new GitCloneStrategy(url, ref)).withMockGit();
    }

    public static TestProjectBuilder fromGit(IGitRepo repo) {
        return new TestProjectBuilder(new ExistingGitRepoStrategy(repo));
    }

    public static TestProjectBuilder fromGit(GitRepo repo) {
        return fromGit((IGitRepo) repo);
    }

    public static class TestProjectBuilder {
        protected final ProjectContentStrategy strategy;
        protected MacroPolicy macroPolicy;

        protected TestProjectBuilder(ProjectContentStrategy strategy) {
            this.strategy = strategy;
        }

        public TestProjectBuilder withRustMacros() {
            try {
                this.macroPolicy = MacroPolicyLoader.loadFromResource("/macros/rust/std-v1.yml");
            } catch (IOException e) {
                throw new RuntimeException("Failed to load rust macro policy", e);
            }
            return this;
        }

        public TestProjectBuilder addFileContents(String contents, String filename) {
            if (strategy instanceof InlineContentStrategy inline) {
                inline.addFileContents(contents, filename);
            } else {
                throw new UnsupportedOperationException("Adding file contents is only supported for inline content.");
            }
            return this;
        }

        public TestGitProjectBuilder withMockGit() {
            if (strategy instanceof ExistingGitRepoStrategy) {
                throw new UnsupportedOperationException("withMockGit() is not supported for fromGit(repo) projects.");
            }
            return new TestGitProjectBuilder(this);
        }

        public ITestProject build() {
            try {
                return buildInternal();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public ITestProject buildInternal() throws IOException {
            if (strategy instanceof ExistingGitRepoStrategy existingGitRepoStrategy) {
                Path projectRoot = existingGitRepoStrategy.getWorkTreeRoot();
                var project = new ExistingRootTestProject(projectRoot);
                project.setRepo(existingGitRepoStrategy.getRepo());
                if (macroPolicy != null) {
                    project.setMacroPolicy(macroPolicy);
                }
                initializeLanguages(project, projectRoot);
                return project;
            }

            var newTemporaryDirectory = Files.createTempDirectory("brokk-analyzer-test-");
            strategy.populate(newTemporaryDirectory);

            EphemeralTestProject project;
            if (this instanceof TestGitProjectBuilder gitBuilder) {
                try {
                    boolean alreadyGit = strategy.isAlreadyGit();
                    if (!alreadyGit) {
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
                        } else if (!alreadyGit) {
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
                } catch (GitAPIException e) {
                    throw new IOException("Failed to initialize git repo for test project", e);
                }
            } else {
                project = new EphemeralTestProject(newTemporaryDirectory);
            }

            if (macroPolicy != null) {
                project.setMacroPolicy(macroPolicy);
            }

            initializeLanguages(project, newTemporaryDirectory);
            return project;
        }

        private void initializeLanguages(ITestProject project, Path projectRoot) throws IOException {
            Set<Language> detected = new LinkedHashSet<>(strategy.detectLanguages());
            detected.addAll(scanLanguages(projectRoot));
            detected.remove(Languages.NONE);

            if (!detected.isEmpty()) {
                project.setAnalyzerLanguages(detected);
            } else {
                project.setAnalyzerLanguages(Set.of(Languages.NONE));
            }
        }

        private Set<Language> scanLanguages(Path root) throws IOException {
            Set<Language> detected = new LinkedHashSet<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> Stream.of(root.relativize(path))
                                .noneMatch(p -> p.toString().equals(".git")
                                        || p.toString().contains(".git/")))
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            String filename = path.getFileName().toString();

                            // Detect by specific project files first
                            if (filename.equals("pyproject.toml") || filename.equals("requirements.txt")) {
                                detected.add(Languages.PYTHON);
                            } else if (filename.equals("Cargo.toml")) {
                                detected.add(Languages.RUST);
                            }

                            // Then by extension
                            int dot = filename.lastIndexOf('.');
                            String ext = (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
                            Language lang = Languages.fromExtension(ext);
                            if (lang != Languages.NONE) {
                                detected.add(lang);
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
        public TestGitProjectBuilder withMockGit() {
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

    private static class ExistingRootTestProject extends TestProject implements ITestProject {
        private volatile IAnalyzer analyzer;

        ExistingRootTestProject(Path root) {
            super(root);
        }

        @Override
        public IAnalyzer getAnalyzer() {
            if (analyzer == null) {
                synchronized (this) {
                    if (analyzer == null) {
                        analyzer = AnalyzerCreator.createFor(this);
                    }
                }
            }
            return analyzer;
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
                        analyzer = AnalyzerCreator.createFor(this);
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
        public boolean hasGit() {
            return true;
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
