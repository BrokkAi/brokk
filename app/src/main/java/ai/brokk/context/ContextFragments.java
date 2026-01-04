package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CallGraphProvider;
import ai.brokk.analyzer.CallSite;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.analyzer.SourceCodeProvider;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.FuzzyUsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.git.GitRepo;
import ai.brokk.util.ComputedValue;
import ai.brokk.util.FragmentUtils;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.LoggingExecutorService;
import ai.brokk.util.Messages;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

public class ContextFragments {
    public static final Logger logger = LogManager.getLogger(ContextFragments.class);

    public static final LoggingExecutorService FRAGMENT_EXECUTOR = createFragmentExecutor();

    public static byte @Nullable [] convertToByteArray(@Nullable List<Byte> imageBytes) {
        if (imageBytes == null) {
            return null;
        }

        byte[] result = new byte[imageBytes.size()];
        for (int i = 0; i < imageBytes.size(); i++) {
            result[i] = imageBytes.get(i);
        }
        return result;
    }

    public static @Nullable List<Byte> convertToList(byte @Nullable [] arr) {
        if (arr == null) return null;

        return IntStream.range(0, arr.length).mapToObj(i -> arr[i]).toList(); // Creates an unmodifiable list
    }

    @VisibleForTesting
    public static LoggingExecutorService getFragmentExecutor() {
        return FRAGMENT_EXECUTOR;
    }

    /**
     * Forcefully shuts down the dedicated ContextFragment executor. Safe to call multiple times.
     * Intended for application shutdown to ensure no lingering threads keep the JVM alive.
     */
    public static void shutdownFragmentExecutor() {
        try {
            FRAGMENT_EXECUTOR.shutdownNow();
        } catch (Throwable t) {
            logger.warn("Error shutting down fragment executor", t);
        }
    }

    private static LoggingExecutorService createFragmentExecutor() {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("brokk-cf-", 0) // Prefix and starting counter
                .factory();
        ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        return new LoggingExecutorService(
                virtualExecutor,
                th -> logger.error("Uncaught exception in ContextFragment Virtual Thread executor", th));
    }

    public sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment {
        BrokkFile file();
    }

    public interface OutputFragment {
        List<TaskEntry> entries();

        default boolean isEscapeHtml() {
            return true;
        }
    }

    record ContentSnapshot(
            String text,
            Set<CodeUnit> sources,
            Set<ProjectFile> files,
            // may not be a byte[] object as records require immutability which cannot be enforced by arrays
            @Nullable List<Byte> imageByteList,
            boolean valid) {

        public ContentSnapshot(
                String text,
                Set<CodeUnit> sources,
                Set<ProjectFile> files,
                byte @Nullable [] imageBytes,
                boolean valid) {
            this(text, sources, files, convertToList(imageBytes), valid);
        }

        /**
         * Factory method for valid text fragments with no image content.
         */
        public static ContentSnapshot textSnapshot(String text, Set<CodeUnit> sources, Set<ProjectFile> files) {
            return new ContentSnapshot(text, sources, files, (List<Byte>) null, true);
        }

        public static ContentSnapshot textSnapshot(String text) {
            return textSnapshot(text, Set.of(), Set.of());
        }

        public byte @Nullable [] imageBytes() {
            return convertToByteArray(imageByteList);
        }
    }

    // Base implementation for fragments with async computation
    abstract static class AbstractComputedFragment implements ContextFragment.ComputedFragment {
        protected final String id;
        protected final IContextManager contextManager;
        protected final ComputedValue<String> descriptionCv;
        protected final ComputedValue<String> shortDescriptionCv;
        protected final ComputedValue<String> syntaxStyleCv;
        final ComputedValue<ContentSnapshot> snapshotCv;
        private final ConcurrentMap<String, ComputedValue<?>> derivedCvs = new ConcurrentHashMap<>();

        /**
         * Constructor for metadata known eagerly.
         */
        protected AbstractComputedFragment(
                String id,
                IContextManager contextManager,
                String description,
                String shortDescription,
                String syntaxStyle,
                @Nullable ContentSnapshot initialSnapshot,
                @Nullable Callable<ContentSnapshot> computeTask) {
            this(
                    id,
                    contextManager,
                    ComputedValue.completed("desc-" + id, description),
                    ComputedValue.completed("short-" + id, shortDescription),
                    ComputedValue.completed("syntax-" + id, syntaxStyle),
                    initialSnapshot,
                    computeTask);
        }

        /**
         * Constructor for metadata provided via ComputedValues (for async metadata).
         */
        protected AbstractComputedFragment(
                String id,
                IContextManager contextManager,
                ComputedValue<String> descriptionCv,
                ComputedValue<String> shortDescriptionCv,
                ComputedValue<String> syntaxStyleCv,
                @Nullable ContentSnapshot initialSnapshot,
                @Nullable Callable<ContentSnapshot> computeTask) {
            assert (initialSnapshot == null) ^ (computeTask == null);
            this.id = id;
            this.contextManager = contextManager;
            this.descriptionCv = descriptionCv;
            this.shortDescriptionCv = shortDescriptionCv;
            this.syntaxStyleCv = syntaxStyleCv;
            this.snapshotCv = initialSnapshot == null
                    ? new ComputedValue<>("snap-" + id, getFragmentExecutor().submit(requireNonNull(computeTask)))
                    : ComputedValue.completed("snap-" + id, initialSnapshot);
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            return snapshotCv.await(timeout).isPresent();
        }

        @Override
        public ComputedValue.Subscription onComplete(Runnable runnable) {
            return snapshotCv.onComplete((v, ex) -> runnable.run());
        }

        @Override
        public String id() {
            return id;
        }

        protected <T> ComputedValue<T> derived(String key, Function<ContentSnapshot, T> extractor) {
            @SuppressWarnings("unchecked")
            ComputedValue<T> cv = (ComputedValue<T>) derivedCvs.computeIfAbsent(key, k -> snapshotCv.map(extractor));
            return cv;
        }

        @Override
        public ComputedValue<String> text() {
            return derived("text", ContentSnapshot::text);
        }

        @Override
        public ComputedValue<String> description() {
            return descriptionCv;
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return shortDescriptionCv;
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return syntaxStyleCv;
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return derived("sources", ContentSnapshot::sources);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return derived("files", ContentSnapshot::files);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            // Can be overridden by image fragments
            return null;
        }

        @Override
        public boolean isValid() {
            return snapshotCv.tryGet().map(ContentSnapshot::valid).orElse(true);
        }

        // Common hasSameSource implementation (identity for dynamic, override for others)
        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (this.getClass() != other.getClass()) return false;
            return (this.repr().equals(other.repr()) && !this.repr().isEmpty())
                    || this.id().equals(other.id());
        }
    }

    // Base implementation for fragments with static/known content
    abstract static class AbstractStaticFragment implements ContextFragment {
        protected final String id;
        protected final String description;
        protected final String shortDescription;
        protected final String syntaxStyle;
        protected final ContentSnapshot snapshot;

        protected AbstractStaticFragment(
                String id, String description, String shortDescription, String syntaxStyle, ContentSnapshot snapshot) {
            this.id = id;
            this.description = description;
            this.shortDescription = shortDescription;
            this.syntaxStyle = syntaxStyle;
            this.snapshot = snapshot;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("text-" + id, snapshot.text());
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed("desc-" + id, description);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("short-" + id, shortDescription);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("syntax-" + id, syntaxStyle);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return ComputedValue.completed("sources-" + id, snapshot.sources());
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed("files-" + id, snapshot.files());
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (this.getClass() != other.getClass()) return false;
            return this.id().equals(other.id());
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof AbstractStaticFragment asf && id().equals(asf.id());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id());
        }
    }

    public static final class ProjectPathFragment extends AbstractComputedFragment implements PathFragment {
        private final ProjectFile file;

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, UUID.randomUUID().toString(), contextManager, null);
        }

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, UUID.randomUUID().toString(), contextManager, snapshotText);
        }

        public static ProjectPathFragment withId(ProjectFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ProjectPathFragment withId(
                ProjectFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            return new ProjectPathFragment(file, existingId, contextManager, snapshotText);
        }

        private static String computeDescription(ProjectFile file) {
            String name = file.getFileName();
            return file.getParent().equals(Path.of("")) ? name : "%s [%s]".formatted(name, file.getParent());
        }

        private static ContentSnapshot decodeFrozen(ProjectFile file, IContextManager contextManager, byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String name = file.getFileName();
            Set<CodeUnit> sources;
            try {
                sources = contextManager.getAnalyzerUninterrupted().getDeclarations(file);
            } catch (Throwable t) {
                logger.error("Failed to analyze declarations for file {}, sources will be empty", name, t);
                sources = Set.of();
            }
            return new ContentSnapshot(text, sources, Set.of(file), (List<Byte>) null, true);
        }

        private static ContentSnapshot computeSnapshotFor(ProjectFile file, IContextManager contextManager) {
            boolean valid = file.exists();
            String text = file.read().orElse("");
            String name = file.getFileName();
            Set<CodeUnit> sources = Set.of();
            try {
                sources = contextManager.getAnalyzerUninterrupted().getDeclarations(file);
            } catch (Exception e) {
                logger.error("Failed to analyze declarations for file {}, sources will be empty", name, e);
            }

            return new ContentSnapshot(text, sources, Set.of(file), (List<Byte>) null, valid);
        }

        private ProjectPathFragment(
                ProjectFile file, String id, IContextManager contextManager, @Nullable String snapshotText) {
            super(
                    id,
                    contextManager,
                    computeDescription(file),
                    file.getFileName(),
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                    snapshotText == null
                            ? null
                            : decodeFrozen(file, contextManager, snapshotText.getBytes(StandardCharsets.UTF_8)),
                    snapshotText == null ? () -> computeSnapshotFor(file, contextManager) : null);
            this.file = file;
        }

        @Override
        public ProjectFile file() {
            return file;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PROJECT_PATH;
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String repr() {
            return "File(['%s'])".formatted(file.toString());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ProjectPathFragment(file, UUID.randomUUID().toString(), contextManager, null);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(description().renderNowOr(file.toString()));
        }
    }

    public static final class GitFileFragment extends AbstractStaticFragment implements PathFragment {
        private final ProjectFile file;
        private final String revision;
        private final String content;

        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(
                    file,
                    revision,
                    content,
                    FragmentUtils.calculateContentHash(
                            FragmentType.GIT_FILE,
                            String.format("%s @%s", file.getFileName(), revision),
                            content,
                            FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                            GitFileFragment.class.getName()));
        }

        private static String computeDescription(ProjectFile file, String revision) {
            var parentDir = file.getParent();
            var shortDesc = "%s @%s".formatted(file.getFileName(), revision);
            return parentDir.equals(Path.of("")) ? shortDesc : "%s [%s]".formatted(shortDesc, parentDir);
        }

        public GitFileFragment(ProjectFile file, String revision, String content, String id) {
            super(
                    id,
                    computeDescription(file, revision),
                    "%s @%s".formatted(file.getFileName(), revision),
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                    ContentSnapshot.textSnapshot(content, Set.of(), Set.of(file)));
            this.file = file;
            this.revision = revision;
            this.content = content;
        }

        public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
            return new GitFileFragment(file, revision, content, existingId);
        }

        /**
         * Create a GitFileFragment representing the content of the given file at the given revision.
         * This reads the file content via the provided GitRepo. On error, falls back to empty content.
         */
        public static GitFileFragment fromCommit(ProjectFile file, String revision, GitRepo repo) {
            try {
                var content = repo.getFileContent(revision, file);
                return new GitFileFragment(file, revision, content);
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        }

        public String revision() {
            return revision;
        }

        public String content() {
            return content;
        }

        @Override
        public ProjectFile file() {
            return file;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.GIT_FILE;
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof GitFileFragment that)) return false;
            return this.file.absPath().normalize().equals(that.file.absPath().normalize())
                    && this.revision.equals(that.revision);
        }
    }

    public static final class ExternalPathFragment extends AbstractComputedFragment implements PathFragment {
        private final ExternalFile file;

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, UUID.randomUUID().toString(), contextManager, null);
        }

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, UUID.randomUUID().toString(), contextManager, snapshotText);
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            return new ExternalPathFragment(file, existingId, contextManager, snapshotText);
        }

        private static ContentSnapshot decodeFrozen(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return ContentSnapshot.textSnapshot(text);
        }

        private static ContentSnapshot computeSnapshotFor(ExternalFile file) {
            String text = file.read().orElse("");
            return new ContentSnapshot(text, Set.of(), Set.of(), (byte[]) null, file.exists());
        }

        private ExternalPathFragment(
                ExternalFile file, String id, IContextManager contextManager, @Nullable String snapshotText) {
            super(
                    id,
                    contextManager,
                    file.toString(),
                    file.toString(),
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                    snapshotText == null ? null : decodeFrozen(snapshotText.getBytes(StandardCharsets.UTF_8)),
                    snapshotText == null ? () -> computeSnapshotFor(file) : null);
            this.file = file;
        }

        @Override
        public ExternalFile file() {
            return file;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.EXTERNAL_PATH;
        }

        @Override
        public String repr() {
            return "ExternalFile('%s')".formatted(file.toString());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ExternalPathFragment(file, UUID.randomUUID().toString(), contextManager, null);
        }
    }

    public static final class ImageFileFragment extends AbstractComputedFragment
            implements PathFragment, ContextFragment.ImageFragment {
        private final BrokkFile file;

        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, UUID.randomUUID().toString(), contextManager);
        }

        private ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) {
            super(
                    id,
                    contextManager,
                    computeDescription(file),
                    file.getFileName(),
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    null,
                    () -> computeSnapshotFor(file));
            this.file = file;
        }

        private static String computeDescription(BrokkFile file) {
            if (file instanceof ProjectFile pf && !pf.getParent().equals(Path.of(""))) {
                return "%s [%s]".formatted(file.getFileName(), pf.getParent());
            }
            return file.toString();
        }

        public static ImageFileFragment withId(BrokkFile file, String existingId, IContextManager contextManager) {
            return new ImageFileFragment(file, existingId, contextManager);
        }

        @Override
        public BrokkFile file() {
            return file;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.IMAGE_FILE;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String contentHash() {
            return id();
        }

        private static ContentSnapshot computeSnapshotFor(BrokkFile file) {
            byte[] bytes = null;
            try {
                var path = file.absPath();
                var imageFile = path.toFile();
                if (imageFile.exists() && imageFile.canRead()) {
                    // Always try raw bytes first for stability
                    try {
                        bytes = Files.readAllBytes(path);
                        if (bytes.length == 0) {
                            bytes = null;
                        }
                    } catch (IOException e) {
                        logger.warn(
                                "Exception when reading raw bytes from {}. Falling back to ImageIO.",
                                file.getFileName(),
                                e);
                    }

                    // Fallback to ImageIO if raw read failed or returned empty
                    if (bytes == null) {
                        try {
                            Image img = ImageIO.read(imageFile);
                            if (img != null) {
                                bytes = ImageUtil.imageToBytes(img);
                                if (bytes != null && bytes.length == 0) {
                                    bytes = null;
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("ImageIO failed to read {}: {}", file.getFileName(), e.getMessage());
                        }
                    }

                    // Last resort: try with BufferedInputStream for better reliability
                    if (bytes == null) {
                        try (var bis = new BufferedInputStream(Files.newInputStream(path))) {
                            Image img = ImageIO.read(bis);
                            if (img != null) {
                                bytes = ImageUtil.imageToBytes(img);
                            }
                        } catch (Exception e) {
                            logger.warn(
                                    "BufferedInputStream + ImageIO failed for {}: {}",
                                    file.getFileName(),
                                    e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Unexpected error reading image file {}: {}", file.getFileName(), e.getMessage(), e);
            }

            return new ContentSnapshot(
                    "[Image content provided out of band]",
                    Set.of(),
                    (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of(),
                    bytes,
                    file.exists());
        }

        @Override
        public ComputedValue<byte[]> imageBytes() {
            return derived("imageBytes", ContentSnapshot::imageBytes);
        }

        @Override
        public String repr() {
            return "ImageFile('%s')".formatted(file.toString());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ImageFileFragment(file, UUID.randomUUID().toString(), contextManager);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    public static class StringFragment extends AbstractStaticFragment {

        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STRING, description, text, syntaxStyle, StringFragment.class.getName()),
                    contextManager,
                    text,
                    description,
                    syntaxStyle,
                    extractFilesFromDiff(text, contextManager));
        }

        public StringFragment(
                String id, IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(
                    id,
                    description,
                    description,
                    syntaxStyle,
                    new ContentSnapshot(
                            text, Set.of(), extractFilesFromDiff(text, contextManager), (List<Byte>) null, true));
        }

        /**
         * Extracts ProjectFile references from text content.
         * Tries unified diff parsing first, then falls back to plain file path list extraction.
         */
        private static Set<ProjectFile> extractFilesFromDiff(String text, IContextManager contextManager) {
            var diffFiles = extractFilesFromUnifiedDiff(text, contextManager);
            if (!diffFiles.isEmpty()) {
                return diffFiles;
            }
            return ContextFragment.extractFilesFromText(text, contextManager);
        }

        /**
         * Extracts ProjectFile references using java-diff-utils UnifiedDiffReader.
         * Returns an empty set if parsing fails or yields no recognizable file paths.
         */
        private static Set<ProjectFile> extractFilesFromUnifiedDiff(String text, IContextManager contextManager) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
                UnifiedDiff diff = UnifiedDiffReader.parseUnifiedDiff(in);
                if (diff == null) {
                    return Set.of();
                }

                Set<ProjectFile> files = new LinkedHashSet<>();
                for (UnifiedDiffFile udf : diff.getFiles()) {
                    String rawPath = primaryPathFromUnifiedDiffFile(udf);
                    String normalized = normalizeDiffPath(rawPath);
                    if (normalized == null) {
                        continue;
                    }
                    ProjectFile projectFile = contextManager.toFile(normalized);
                    if (projectFile.exists()) {
                        files.add(projectFile);
                    }
                }
                return files;
            } catch (IOException e) {
                return Set.of();
            }
        }

        /**
         * Picks the most appropriate path from a UnifiedDiffFile, preferring the "to" path and
         * falling back to the "from" path (for deletions/renames).
         */
        private static @Nullable String primaryPathFromUnifiedDiffFile(UnifiedDiffFile file) {
            String path = file.getToFile();
            if (path == null || path.isBlank() || "/dev/null".equals(path.trim())) {
                path = file.getFromFile();
            }
            return path;
        }

        /**
         * Normalizes a diff path by trimming, stripping leading a/ or b/ prefixes and ignoring /dev/null.
         */
        private static @Nullable String normalizeDiffPath(@Nullable String rawPath) {
            if (rawPath == null) return null;

            String path = rawPath.trim();
            if (path.isEmpty() || "/dev/null".equals(path)) {
                return null;
            }
            if (path.startsWith("a/") || path.startsWith("b/")) {
                path = path.substring(2);
            }
            return path;
        }

        public StringFragment(
                IContextManager contextManager,
                String text,
                String description,
                String syntaxStyle,
                Set<ProjectFile> files) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STRING, description, text, syntaxStyle, StringFragment.class.getName()),
                    contextManager,
                    text,
                    description,
                    syntaxStyle,
                    files);
        }

        public StringFragment(
                String id,
                IContextManager contextManager,
                String text,
                String description,
                String syntaxStyle,
                Set<ProjectFile> files) {
            super(
                    id,
                    description,
                    description,
                    syntaxStyle,
                    new ContentSnapshot(text, Set.of(), Set.copyOf(files), (List<Byte>) null, true));
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        public Optional<SpecialTextType> specialType() {
            return SpecialTextType.fromDescription(description);
        }

        public String previewSyntaxStyle() {
            return specialType().map(SpecialTextType::previewSyntaxStyle).orElse(syntaxStyle);
        }

        public String previewText() {
            return specialType().map(st -> st.renderPreview(snapshot.text())).orElse(snapshot.text());
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (!(other instanceof StringFragment that)) return false;
            var thisType = SpecialTextType.fromDescription(this.description);
            var thatType = SpecialTextType.fromDescription(that.description);
            if (thisType.isPresent() && thisType.equals(thatType)) {
                return true;
            }
            return Objects.equals(this.description, that.description)
                    && Objects.equals(this.syntaxStyle, that.syntaxStyle)
                    && Objects.equals(this.snapshot, that.snapshot);
        }
    }

    public static class PasteTextFragment extends AbstractComputedFragment {
        public PasteTextFragment(
                IContextManager contextManager,
                String text,
                CompletableFuture<String> descriptionFuture,
                CompletableFuture<String> syntaxStyleFuture) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_TEXT,
                            "(Pasting text)",
                            text,
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            PasteTextFragment.class.getName()),
                    contextManager,
                    text,
                    descriptionFuture,
                    syntaxStyleFuture);
        }

        public PasteTextFragment(
                String id,
                IContextManager contextManager,
                String text,
                CompletableFuture<String> descriptionFuture,
                CompletableFuture<String> syntaxStyleFuture) {
            super(
                    id,
                    contextManager,
                    new ComputedValue<>("desc-" + id, descriptionFuture.exceptionally(ex -> "text content"))
                            .map(d -> "Paste of " + d),
                    new ComputedValue<>("short-" + id, descriptionFuture.exceptionally(ex -> "text content"))
                            .map(d -> "Paste of " + d),
                    new ComputedValue<>("syntax-" + id, syntaxStyleFuture),
                    null,
                    () -> computeSnapshotFor(text, contextManager));
        }

        /**
         * Constructor for deserialization: accepts already-computed description without applying "Paste of " prefix.
         */
        public static PasteTextFragment withResolvedDescription(
                String id,
                IContextManager contextManager,
                String text,
                String resolvedDescription,
                String syntaxStyle) {
            return new PasteTextFragment(id, contextManager, text, resolvedDescription, syntaxStyle);
        }

        private PasteTextFragment(
                String id,
                IContextManager contextManager,
                String text,
                String resolvedDescription,
                String syntaxStyle) {
            super(
                    id,
                    contextManager,
                    ComputedValue.completed("desc-" + id, resolvedDescription),
                    ComputedValue.completed("short-" + id, resolvedDescription),
                    ComputedValue.completed("syntax-" + id, syntaxStyle),
                    null,
                    () -> computeSnapshotFor(text, contextManager));
        }

        private static ContentSnapshot computeSnapshotFor(String text, IContextManager contextManager) {
            var files = ContextFragment.extractFilesFromText(text, contextManager);
            return ContentSnapshot.textSnapshot(text, Set.of(), files);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }
    }

    public static class AnonymousImageFragment extends AbstractComputedFragment
            implements ContextFragment.ImageFragment {
        final CompletableFuture<String> descriptionFuture;

        public AnonymousImageFragment(
                IContextManager contextManager, Image image, CompletableFuture<String> descriptionFuture) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_IMAGE,
                            "(Pasting image)",
                            null,
                            imageToBytes(image),
                            false,
                            SyntaxConstants.SYNTAX_STYLE_NONE,
                            Set.of(),
                            AnonymousImageFragment.class.getName(),
                            Map.of()),
                    contextManager,
                    image,
                    descriptionFuture);
        }

        public AnonymousImageFragment(
                String id, IContextManager contextManager, Image image, CompletableFuture<String> descriptionFuture) {
            super(
                    id,
                    contextManager,
                    new ComputedValue<>("desc-" + id, descriptionFuture),
                    new ComputedValue<>("short-" + id, descriptionFuture),
                    ComputedValue.completed(SyntaxConstants.SYNTAX_STYLE_NONE),
                    null,
                    () -> computeSnapshotFor(image));
            this.descriptionFuture = descriptionFuture;
        }

        @Nullable
        private static byte[] imageToBytes(@Nullable Image image) {
            try {
                return ImageUtil.imageToBytes(image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_IMAGE;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String contentHash() {
            return id();
        }

        private static ContentSnapshot computeSnapshotFor(Image image) {
            byte[] bytes = imageToBytes(image);
            return new ContentSnapshot("[Image content provided out of band]", Set.of(), Set.of(), bytes, true);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            return derived("imageBytes", ContentSnapshot::imageBytes);
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }
    }

    public static class StacktraceFragment extends AbstractStaticFragment {
        private final String original;
        private final String exception;
        private final String code;

        public StacktraceFragment(
                IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STACKTRACE,
                            "stacktrace of " + exception,
                            original + "\n\nStacktrace methods in this project:\n\n" + code,
                            sources.isEmpty()
                                    ? SyntaxConstants.SYNTAX_STYLE_NONE
                                    : sources.iterator().next().source().getSyntaxStyle(),
                            StacktraceFragment.class.getName()),
                    contextManager,
                    sources,
                    original,
                    exception,
                    code);
        }

        public StacktraceFragment(
                String id,
                IContextManager contextManager,
                Set<CodeUnit> sources,
                String original,
                String exception,
                String code) {
            super(
                    id,
                    "stacktrace of " + exception,
                    "stacktrace of " + exception,
                    sources.isEmpty()
                            ? SyntaxConstants.SYNTAX_STYLE_NONE
                            : sources.iterator().next().source().getSyntaxStyle(),
                    ContentSnapshot.textSnapshot(
                            original + "\n\nStacktrace methods in this project:\n\n" + code,
                            sources,
                            sources.stream().map(CodeUnit::source).collect(Collectors.toSet())));
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STACKTRACE;
        }

        public String getOriginal() {
            return original;
        }

        public String getException() {
            return exception;
        }

        public String getCode() {
            return code;
        }
    }

    public static class UsageFragment extends AbstractComputedFragment {
        private final String targetIdentifier;
        private final boolean includeTestFiles;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            this(contextManager, targetIdentifier, true, null);
        }

        public UsageFragment(IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            this(contextManager, targetIdentifier, includeTestFiles, null);
        }

        public UsageFragment(
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText) {
            this(UUID.randomUUID().toString(), contextManager, targetIdentifier, includeTestFiles, snapshotText);
        }

        public UsageFragment(String id, IContextManager contextManager, String targetIdentifier) {
            this(id, contextManager, targetIdentifier, true, null);
        }

        public UsageFragment(
                String id, IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            this(id, contextManager, targetIdentifier, includeTestFiles, null);
        }

        public UsageFragment(
                String id,
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText) {
            super(
                    id,
                    contextManager,
                    "Uses of " + targetIdentifier,
                    "Uses of " + targetIdentifier,
                    SyntaxConstants.SYNTAX_STYLE_NONE, // Will be updated if we find files/units
                    snapshotText == null
                            ? null
                            : decodeFrozen(contextManager, snapshotText.getBytes(StandardCharsets.UTF_8)),
                    snapshotText == null
                            ? () -> computeSnapshotFor(targetIdentifier, includeTestFiles, contextManager)
                            : null);
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
        }

        private static ContentSnapshot decodeFrozen(IContextManager contextManager, byte[] bytes) {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            String text = new String(bytes, StandardCharsets.UTF_8);

            Set<ProjectFile> files = new LinkedHashSet<>();
            Set<CodeUnit> units = new LinkedHashSet<>();

            // Match each <methods ...>...</methods> block
            var blockMatcher =
                    Pattern.compile("(?is)<methods\\s+([^>]*)>(.*?)</methods>").matcher(text);

            // Support attributes with:
            // - standard quotes: key="value"
            // - single quotes: key='value'
            // - backslash-escaped quotes: key=\"value\"
            var attrPattern = Pattern.compile(
                    "(\\w+)\\s*=\\s*(?:\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'|([^\\s>]+))");

            while (blockMatcher.find()) {
                String attrs = blockMatcher.group(1);

                Map<String, String> attrMap = new HashMap<>();
                var attrMatcher = attrPattern.matcher(attrs);
                while (attrMatcher.find()) {
                    String key = attrMatcher.group(1);
                    String value = Stream.of(attrMatcher.group(2), attrMatcher.group(3), attrMatcher.group(4))
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    if (value != null) {
                        value = value.replace("\\\"", "\"").replace("\\'", "'");
                        if ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        attrMap.put(key, value);
                    }
                }

                String classFqn = Optional.ofNullable(attrMap.get("class"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .orElse(null);
                String fileRelPathRaw = Optional.ofNullable(attrMap.get("file"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .orElse(null);

                // Resolve file attribute if present, normalizing separators for cross-OS compatibility
                if (fileRelPathRaw != null) {
                    String fileRelPath = fileRelPathRaw.replace('\\', '/');
                    try {
                        ProjectFile pf = contextManager.toFile(fileRelPath);
                        files.add(pf);
                    } catch (Exception t) {
                        logger.warn("Unable to resolve ProjectFile for '{}'", fileRelPathRaw, t);
                    }
                }

                // Resolve class unit(s)
                if (classFqn != null) {
                    try {
                        units.addAll(analyzer.getDefinitions(classFqn));
                    } catch (Exception e) {
                        logger.warn("Unable to resolve class CodeUnit for '{}'", classFqn, e);
                    }
                }
            }

            // If no files were decoded explicitly, derive from resolved units
            if (files.isEmpty() && !units.isEmpty()) {
                files.addAll(units.stream().map(CodeUnit::source).collect(Collectors.toCollection(LinkedHashSet::new)));
            }

            return ContentSnapshot.textSnapshot(text, units, files);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        @Override
        public String repr() {
            return "SymbolUsages('%s', includeTestFiles=%s)".formatted(targetIdentifier, includeTestFiles);
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }

        public boolean includeTestFiles() {
            return includeTestFiles;
        }

        private static ContentSnapshot computeSnapshotFor(
                String targetIdentifier, boolean includeTestFiles, IContextManager contextManager) {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            FuzzyResult usageResult = FuzzyUsageFinder.create(contextManager).findUsages(targetIdentifier);
            var either = usageResult.toEither();

            String text;
            Set<CodeUnit> sources = Collections.emptySet();

            if (either.hasErrorMessage()) {
                text = either.getErrorMessage();
            } else {
                List<UsageHit> uses = either.getUsages().stream()
                        .sorted(Comparator.comparingDouble(UsageHit::confidence).reversed())
                        .toList();
                if (!includeTestFiles) {
                    uses = uses.stream()
                            .filter(cu -> !ContextManager.isTestFile(cu.file()))
                            .toList();
                }
                List<AnalyzerUtil.CodeWithSource> parts = AnalyzerUtil.processUsages(
                        analyzer, uses.stream().map(UsageHit::enclosing).toList());
                String formatted = AnalyzerUtil.CodeWithSource.text(parts);
                text = formatted.isEmpty() ? "No relevant usages found for symbol: " + targetIdentifier : formatted;
                sources =
                        parts.stream().map(AnalyzerUtil.CodeWithSource::source).collect(Collectors.toSet());
            }

            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());
            if (!includeTestFiles) {
                files = files.stream()
                        .filter(f -> !ContextManager.isTestFile(f))
                        .collect(Collectors.toSet());
            }

            // Validity based on whether definitions exist
            boolean valid = !analyzer.getDefinitions(targetIdentifier).isEmpty();
            return new ContentSnapshot(text, sources, files, (List<Byte>) null, valid);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new UsageFragment(id, contextManager, targetIdentifier, includeTestFiles, null);
        }
    }

    public static class CodeFragment extends AbstractComputedFragment {
        private final String fullyQualifiedName;

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName) {
            this(contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName, @Nullable String snapshotText) {
            this(UUID.randomUUID().toString(), contextManager, fullyQualifiedName, snapshotText);
        }

        public CodeFragment(String id, IContextManager contextManager, String fullyQualifiedName) {
            this(id, contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(
                String id, IContextManager contextManager, String fullyQualifiedName, @Nullable String snapshotText) {
            this(id, contextManager, fullyQualifiedName, snapshotText, null);
        }

        private CodeFragment(
                String id,
                IContextManager contextManager,
                String fullyQualifiedName,
                @Nullable String snapshotText,
                @Nullable CodeUnit eagerUnit) {
            super(
                    id,
                    contextManager,
                    "Source for " + fullyQualifiedName,
                    eagerUnit != null ? eagerUnit.shortName() : fullyQualifiedName,
                    eagerUnit != null ? eagerUnit.source().getSyntaxStyle() : SyntaxConstants.SYNTAX_STYLE_NONE,
                    snapshotText == null
                            ? null
                            : decodeFrozen(
                                    fullyQualifiedName,
                                    snapshotText.getBytes(StandardCharsets.UTF_8),
                                    contextManager.getAnalyzerUninterrupted()),
                    snapshotText == null
                            ? () -> computeSnapshotFor(fullyQualifiedName, contextManager, eagerUnit)
                            : null);
            this.fullyQualifiedName = fullyQualifiedName;
        }

        private static ContentSnapshot decodeFrozen(String fullyQualifiedName, byte[] bytes, IAnalyzer analyzer) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            Set<CodeUnit> units = Set.of();
            Set<ProjectFile> files = Set.of();
            try {
                var unit = analyzer.getDefinitions(fullyQualifiedName).stream()
                        .findFirst()
                        .orElseThrow();
                units = Set.of(unit);
                var file = unit.source();
                files = Set.of(file);
            } catch (Exception e) {
                logger.warn("Unable to resolve CodeUnit for fqName: {}", fullyQualifiedName);
            }

            return ContentSnapshot.textSnapshot(text, units, files);
        }

        public CodeFragment(IContextManager contextManager, CodeUnit unit) {
            this(UUID.randomUUID().toString(), contextManager, unit.fqName(), null, unit);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CODE;
        }

        @Override
        public String repr() {
            return "Method(['%s'])".formatted(fullyQualifiedName);
        }

        public String getFullyQualifiedName() {
            return fullyQualifiedName;
        }

        private static ContentSnapshot computeSnapshotFor(
                String fqName, IContextManager contextManager, @Nullable CodeUnit preResolvedUnit) {
            CodeUnit unit = preResolvedUnit;
            if (unit == null) {
                unit = contextManager.getAnalyzerUninterrupted().getDefinitions(fqName).stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unable to resolve CodeUnit for fqName: " + fqName));
            }

            String text;
            var analyzer = contextManager.getAnalyzerUninterrupted();
            var scpOpt = analyzer.as(SourceCodeProvider.class);
            boolean hasSourceCode = false;
            if (scpOpt.isEmpty()) {
                text = "Code Intelligence cannot extract source for: " + fqName;
            } else {
                var scp = scpOpt.get();
                if (unit.isFunction()) {
                    var codeOpt = scp.getMethodSource(unit, true);
                    if (codeOpt.isPresent()) {
                        text = new AnalyzerUtil.CodeWithSource(codeOpt.get(), unit).text();
                        hasSourceCode = true;
                    } else {
                        text = "No source found for method: " + fqName;
                    }
                } else {
                    var codeOpt = scp.getClassSource(unit, true);
                    if (codeOpt.isPresent()) {
                        text = new AnalyzerUtil.CodeWithSource(codeOpt.get(), unit).text();
                        hasSourceCode = true;
                    } else {
                        text = "No source found for class: " + fqName;
                    }
                }
            }

            return new ContentSnapshot(text, Set.of(unit), Set.of(unit.source()), (List<Byte>) null, hasSourceCode);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CodeFragment(id, contextManager, fullyQualifiedName);
        }
    }

    public static class CallGraphFragment extends AbstractComputedFragment {
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph;

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            this(UUID.randomUUID().toString(), contextManager, methodName, depth, isCalleeGraph);
        }

        public CallGraphFragment(
                String id, IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(
                    id,
                    contextManager,
                    "%s of %s (depth %d)".formatted(isCalleeGraph ? "Callees" : "Callers", methodName, depth),
                    "%s of %s (depth %d)".formatted(isCalleeGraph ? "Callees" : "Callers", methodName, depth),
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    null,
                    () -> computeSnapshotFor(methodName, depth, isCalleeGraph, contextManager));
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CALL_GRAPH;
        }

        public String getMethodName() {
            return methodName;
        }

        public int getDepth() {
            return depth;
        }

        public boolean isCalleeGraph() {
            return isCalleeGraph;
        }

        @Override
        public String repr() {
            return "CallGraph('%s', depth=%d, direction=%s)".formatted(methodName, depth, isCalleeGraph ? "OUT" : "IN");
        }

        private static ContentSnapshot computeSnapshotFor(
                String methodName, int depth, boolean isCalleeGraph, IContextManager contextManager) {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            var methodCodeUnit = analyzer.getDefinitions(methodName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();

            String text;
            Set<CodeUnit> sources = Set.of();
            if (methodCodeUnit.isPresent()) {
                sources = Set.of(methodCodeUnit.get());
                var cpgOpt = analyzer.as(CallGraphProvider.class);
                if (cpgOpt.isPresent()) {
                    var cpg = cpgOpt.get();
                    Map<String, List<CallSite>> graphData = isCalleeGraph
                            ? cpg.getCallgraphFrom(methodCodeUnit.get(), depth)
                            : cpg.getCallgraphTo(methodCodeUnit.get(), depth);

                    text = graphData.isEmpty()
                            ? "No call graph available for " + methodName
                            : AnalyzerUtil.formatCallGraph(graphData, methodName, !isCalleeGraph);
                } else {
                    text = "Code intelligence is not ready. Cannot generate call graph for " + methodName + ".";
                }
            } else {
                text = "Method not found: " + methodName;
            }

            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());

            boolean valid = analyzer.getDefinitions(methodName).stream().anyMatch(CodeUnit::isFunction);
            return new ContentSnapshot(text, sources, files, (List<Byte>) null, valid);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CallGraphFragment(id, contextManager, methodName, depth, isCalleeGraph);
        }
    }

    public static class SkeletonFragmentFormatter {
        public record Request(
                @Nullable CodeUnit primaryTarget,
                List<CodeUnit> ancestors,
                Map<CodeUnit, String> skeletons,
                ContextFragment.SummaryType summaryType) {}

        public String format(Request request) {
            if (request.summaryType() == ContextFragment.SummaryType.CODEUNIT_SKELETON
                    && request.primaryTarget() != null
                    && request.primaryTarget().isClass()) {
                return formatSummaryWithAncestors(request.primaryTarget(), request.ancestors(), request.skeletons());
            } else {
                return formatSkeletonsByPackage(request.skeletons());
            }
        }

        private String formatSummaryWithAncestors(
                CodeUnit cu, List<CodeUnit> ancestorList, Map<CodeUnit, String> skeletons) {
            var sb = new StringBuilder();

            boolean isCuAnonymous = cu.isAnonymous();

            if (!isCuAnonymous) {
                String primarySkeleton = skeletons.get(cu);
                if (primarySkeleton != null && !primarySkeleton.isEmpty()) {
                    Map<CodeUnit, String> primary = new LinkedHashMap<>();
                    primary.put(cu, primarySkeleton);
                    String primaryFormatted = formatSkeletonsByPackage(primary);
                    if (!primaryFormatted.isEmpty()) sb.append(primaryFormatted).append("\n\n");
                }
            }

            var filteredAncestors =
                    ancestorList.stream().filter(anc -> !anc.isAnonymous()).toList();

            if (!filteredAncestors.isEmpty()) {
                if (!isCuAnonymous) {
                    String ancestorNames =
                            filteredAncestors.stream().map(CodeUnit::shortName).collect(Collectors.joining(", "));
                    sb.append("// Direct ancestors of ")
                            .append(cu.shortName())
                            .append(": ")
                            .append(ancestorNames)
                            .append("\n\n");
                }
                Map<CodeUnit, String> ancestorsMap = new LinkedHashMap<>();
                filteredAncestors.forEach(anc -> {
                    String sk = skeletons.get(anc);
                    if (sk != null) ancestorsMap.put(anc, sk);
                });
                String ancestorsFormatted = formatSkeletonsByPackage(ancestorsMap);
                if (!ancestorsFormatted.isEmpty()) sb.append(ancestorsFormatted).append("\n\n");
            }
            return sb.toString().trim();
        }

        private String formatSkeletonsByPackage(Map<CodeUnit, String> skeletons) {
            if (skeletons.isEmpty()) return "";

            var skeletonsByPackage = skeletons.entrySet().stream()
                    .filter(e -> !e.getKey().isAnonymous())
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().packageName().isEmpty()
                                    ? "(default package)"
                                    : e.getKey().packageName(),
                            Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)));

            if (skeletonsByPackage.isEmpty()) return "";

            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> "package " + pkgEntry.getKey() + ";\n\n"
                            + String.join("\n\n", pkgEntry.getValue().values()))
                    .collect(Collectors.joining("\n\n"));
        }
    }

    public static class SummaryFragment extends AbstractComputedFragment {
        private final String targetIdentifier;
        private final SummaryType summaryType;

        private static String computeDescription(String targetIdentifier) {
            return "Summary of %s".formatted(targetIdentifier);
        }

        private static String computeShortDescription(
                String targetIdentifier, SummaryType summaryType, IContextManager contextManager) {
            if (summaryType == SummaryType.FILE_SKELETONS) {
                return "Summary of " + contextManager.toFile(targetIdentifier).getFileName();
            }
            var definitions = contextManager.getAnalyzerUninterrupted().getDefinitions(targetIdentifier);
            if (!definitions.isEmpty()) {
                return "Summary of " + definitions.getFirst().shortName();
            }
            return "Summary of " + targetIdentifier;
        }

        public SummaryFragment(IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            this(UUID.randomUUID().toString(), contextManager, targetIdentifier, summaryType);
        }

        public SummaryFragment(
                String id, IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            super(
                    id,
                    contextManager,
                    ComputedValue.completed("desc-" + id, computeDescription(targetIdentifier)),
                    new ComputedValue<>(
                            "short-" + id,
                            getFragmentExecutor()
                                    .submit(() ->
                                            computeShortDescription(targetIdentifier, summaryType, contextManager))),
                    ComputedValue.completed("syntax-" + id, SyntaxConstants.SYNTAX_STYLE_JAVA),
                    null,
                    () -> computeSnapshotFor(targetIdentifier, summaryType, contextManager));
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SKELETON;
        }

        @Override
        public String repr() {
            return (summaryType == SummaryType.CODEUNIT_SKELETON ? "ClassSummary('%s')" : "FileSummary('%s')")
                    .formatted(targetIdentifier);
        }

        public String getTargetIdentifier() {
            return targetIdentifier;
        }

        public SummaryType getSummaryType() {
            return summaryType;
        }

        private static ContentSnapshot computeSnapshotFor(
                String targetIdentifier, SummaryType summaryType, IContextManager contextManager) {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            Map<CodeUnit, String> skeletonsMap = new LinkedHashMap<>();
            var skeletonProviderOpt = analyzer.as(SkeletonProvider.class);
            Set<CodeUnit> primaryTargets =
                    resolvePrimaryTargets(targetIdentifier, summaryType, analyzer, contextManager);

            if (skeletonProviderOpt.isPresent()) {
                var skeletonProvider = skeletonProviderOpt.get();
                for (CodeUnit cu : primaryTargets) {
                    skeletonProvider.getSkeleton(cu).ifPresent(s -> skeletonsMap.put(cu, s));
                }
                var seenAncestors = new HashSet<String>();
                primaryTargets.stream()
                        .filter(CodeUnit::isClass)
                        .flatMap(cu -> analyzer.getDirectAncestors(cu).stream())
                        .filter(anc -> seenAncestors.add(anc.fqName()))
                        .forEach(anc -> skeletonProvider.getSkeleton(anc).ifPresent(s -> skeletonsMap.put(anc, s)));
            }

            String text;
            if (skeletonsMap.isEmpty()) {
                text = "No summary found for: " + targetIdentifier;
            } else {
                CodeUnit primaryTarget = null;
                List<CodeUnit> ancestors = List.of();
                if (summaryType == SummaryType.CODEUNIT_SKELETON) {
                    var maybeClassUnit =
                            primaryTargets.stream().filter(CodeUnit::isClass).findFirst();
                    if (maybeClassUnit.isPresent()) {
                        primaryTarget = maybeClassUnit.get();
                        ancestors = analyzer.getDirectAncestors(primaryTarget);
                    }
                }
                text = new SkeletonFragmentFormatter()
                        .format(new SkeletonFragmentFormatter.Request(
                                primaryTarget, ancestors, skeletonsMap, summaryType));
                if (text.isEmpty()) text = "No summary found for: " + targetIdentifier;
            }

            Set<CodeUnit> sources = skeletonsMap.keySet();
            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());
            boolean valid = !primaryTargets.isEmpty();

            return new ContentSnapshot(text, sources, files, (List<Byte>) null, valid);
        }

        public record CodeUnitSkeleton(CodeUnit codeUnit, String skeleton) {}

        @Blocking
        private Map<String, CodeUnitSkeleton> skeletonsByFqName() {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            var skeletonProviderOpt = analyzer.as(SkeletonProvider.class);
            if (skeletonProviderOpt.isEmpty()) {
                return Map.of();
            }
            SkeletonProvider skeletonProvider = skeletonProviderOpt.get();

            Map<String, CodeUnitSkeleton> out = new LinkedHashMap<>();
            for (CodeUnit cu : sources().join()) {
                if (cu.isAnonymous()) {
                    continue;
                }
                skeletonProvider
                        .getSkeleton(cu)
                        .filter(s -> !s.isBlank())
                        .ifPresent(skeleton -> out.putIfAbsent(cu.fqName(), new CodeUnitSkeleton(cu, skeleton)));
            }
            return out;
        }

        /**
         * Combines multiple summary fragments into a single text block.
         *
         * <p>Semantic aggregation:
         * - Union CodeUnits across fragments via sources()
         * - Deduplicate by CodeUnit.fqName() (stable first-seen)
         * - Exclude anonymous units
         * - Use each fragment's own analyzer via SkeletonProvider to obtain skeleton text
         * - Format via SkeletonFragmentFormatter in by-package mode
         */
        @Blocking
        public static String combinedText(List<SummaryFragment> fragments) {
            if (fragments.isEmpty()) {
                return "";
            }

            if (SwingUtilities.isEventDispatchThread()) {
                logger.error("combinedText is a blocking function and should not be called on the EDT!");
            }

            Map<String, CodeUnitSkeleton> deduped = new LinkedHashMap<>();
            for (SummaryFragment fragment : fragments) {
                fragment.skeletonsByFqName().forEach(deduped::putIfAbsent);
            }

            if (deduped.isEmpty()) {
                return "";
            }

            Map<CodeUnit, String> skeletonsMap = new LinkedHashMap<>();
            deduped.values().forEach(cus -> skeletonsMap.put(cus.codeUnit(), cus.skeleton()));

            return new SkeletonFragmentFormatter()
                    .format(new SkeletonFragmentFormatter.Request(
                            null, List.of(), skeletonsMap, SummaryType.FILE_SKELETONS));
        }

        private static Set<CodeUnit> resolvePrimaryTargets(
                String targetIdentifier, SummaryType summaryType, IAnalyzer analyzer, IContextManager contextManager) {
            if (summaryType == SummaryType.CODEUNIT_SKELETON) return analyzer.getDefinitions(targetIdentifier);
            if (summaryType == SummaryType.FILE_SKELETONS)
                return new LinkedHashSet<>(analyzer.getTopLevelDeclarations(contextManager.toFile(targetIdentifier)));
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public ContextFragment refreshCopy() {
            return new SummaryFragment(id, contextManager, targetIdentifier, summaryType);
        }
    }

    public static class HistoryFragment extends AbstractStaticFragment implements OutputFragment {
        private final List<TaskEntry> history;

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.HISTORY,
                            "Task History (" + history.size() + " task" + (history.size() > 1 ? "s" : "") + ")",
                            TaskEntry.formatMessages(history.stream()
                                    .flatMap(e -> e.isCompressed()
                                            ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                            : castNonNull(e.log()).messages().stream())
                                    .toList()),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            HistoryFragment.class.getName()),
                    contextManager,
                    history);
        }

        public HistoryFragment(String id, IContextManager contextManager, List<TaskEntry> history) {
            super(
                    id,
                    "Conversation (" + history.size() + " thread%s)".formatted(history.size() > 1 ? "s" : ""),
                    "Conversation (" + history.size() + " thread%s)".formatted(history.size() > 1 ? "s" : ""),
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    ContentSnapshot.textSnapshot(TaskEntry.formatMessages(history.stream()
                            .flatMap(e -> e.isCompressed()
                                    ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                    : castNonNull(e.log()).messages().stream())
                            .toList())));
            this.history = List.copyOf(history);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.HISTORY;
        }

        @Override
        public List<TaskEntry> entries() {
            return history;
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }
    }

    public static class TaskFragment extends AbstractStaticFragment implements OutputFragment {
        private final List<ChatMessage> messages;
        private final boolean escapeHtml;

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(contextManager, messages, description, true);
        }

        public TaskFragment(
                IContextManager contextManager, List<ChatMessage> messages, String description, boolean escapeHtml) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.TASK,
                            description,
                            TaskEntry.formatMessages(messages),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            TaskFragment.class.getName()),
                    contextManager,
                    messages,
                    description,
                    escapeHtml);
        }

        public TaskFragment(String id, IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(id, contextManager, messages, description, true);
        }

        public TaskFragment(
                String id,
                IContextManager contextManager,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            super(
                    id,
                    description,
                    description,
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    ContentSnapshot.textSnapshot(TaskEntry.formatMessages(messages)));
            this.messages = List.copyOf(messages);
            this.escapeHtml = escapeHtml;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.TASK;
        }

        @Override
        public boolean isEscapeHtml() {
            return escapeHtml;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }
    }
}
