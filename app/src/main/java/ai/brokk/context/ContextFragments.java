package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.analyzer.usages.UsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.git.GitRepo;
import ai.brokk.util.FragmentUtils;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Lines;
import ai.brokk.util.Messages;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.github.difflib.unifieddiff.UnifiedDiffParserException;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.function.Function;
import java.util.function.Predicate;
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

    /**
     * Resolves supporting summary fragments for the direct ancestors of the given code units.
     * Filters out anonymous units.
     */
    @Blocking
    public static Set<ContextFragment> resolveAncestorFragments(
            Collection<CodeUnit> units, IContextManager contextManager) {
        IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
        return units.stream()
                .filter(CodeUnit::isClass)
                .flatMap(cu ->
                        analyzer
                                .as(TypeHierarchyProvider.class)
                                .map(p -> p.getDirectAncestors(cu))
                                .orElse(List.of())
                                .stream())
                .filter(anc -> !anc.isAnonymous())
                .distinct()
                .map(anc -> new SummaryFragment(
                        contextManager, anc.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .collect(Collectors.toSet());
    }

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
        return ExecutorsUtil.newVirtualThreadExecutor("brokk-cf-", 1_000);
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
        // desc and shortDesc are broken out so that DiffService only has to block for the very few
        // fragments that don't know their description right away, instead of the many fragments that don't know their
        // text()
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

        private ComputedValue<Void> allReadyCv() {
            return ComputedValue.allOf(descriptionCv, shortDescriptionCv, syntaxStyleCv, snapshotCv);
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            return allReadyCv().await(timeout).isPresent();
        }

        @Override
        public ComputedValue.Subscription onComplete(Runnable runnable) {
            return allReadyCv().onComplete((v, ex) -> runnable.run());
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
        public ComputedValue<Set<ProjectFile>> referencedFiles() {
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
        public ComputedValue<Set<ProjectFile>> referencedFiles() {
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
        public ComputedValue<Set<ProjectFile>> sourceFiles() {
            return ComputedValue.completed(Set.of());
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
            return file.toString();
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
            if (file.getRelPath().normalize().getFileName() == null) {
                throw new IllegalArgumentException("ProjectPathFragment relPath must not be empty");
            }
            assert !file.isDirectory() : file; // assert so we don't do i/o here in prod
            this.file = file;
        }

        @Override
        public ProjectFile file() {
            return file;
        }

        @Override
        public ComputedValue<Set<ProjectFile>> sourceFiles() {
            return referencedFiles();
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

        @Override
        @Blocking
        public Set<ContextFragment> supportingFragments() {
            IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
            return resolveAncestorFragments(analyzer.getTopLevelDeclarations(file), contextManager);
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
            return "%s @%s".formatted(file.toString(), revision);
        }

        public GitFileFragment(ProjectFile file, String revision, String content, String id) {
            super(
                    id,
                    computeDescription(file, revision),
                    "%s @%s".formatted(file.getFileName(), revision),
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                    ContentSnapshot.textSnapshot(content, Set.of(), Set.of(file)));
            if (file.getRelPath().normalize().getFileName() == null) {
                throw new IllegalArgumentException("ProjectPathFragment relPath must not be empty");
            }
            assert !file.isDirectory() : file; // assert so we don't do i/o here in prod
            this.file = file;
            this.revision = revision;
            this.content = content;
        }

        public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
            return new GitFileFragment(file, revision, content, existingId);
        }

        /**
         * Create a GitFileFragment representing the content of the given file at the given revision.
         * This reads the file content via the provided GitRepo.
         */
        public static GitFileFragment fromCommit(ProjectFile file, String revision, GitRepo repo)
                throws GitAPIException {
            var content = repo.getFileContent(revision, file);
            return new GitFileFragment(file, revision, content);
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
            assert !file.isDirectory() : file; // assert so we don't do i/o here in prod
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
            assert !file.isDirectory() : file; // assert so we don't do i/o here in prod
            this.file = file;
        }

        private static String computeDescription(BrokkFile file) {
            return file.toString();
        }

        public static ImageFileFragment withId(BrokkFile file, String existingId, IContextManager contextManager) {
            return new ImageFileFragment(file, existingId, contextManager);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> sourceFiles() {
            return referencedFiles();
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
            } catch (IOException | UnifiedDiffParserException e) {
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

    public enum UsageMode {
        FULL, // Show all usages with full method sources
        SAMPLE // Show shortest calling method per overload, capped at 50
    }

    public static class UsageFragment extends AbstractComputedFragment {
        private final String targetIdentifier;
        private final boolean includeTestFiles;
        private final UsageMode mode;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            this(contextManager, targetIdentifier, true, null, UsageMode.FULL);
        }

        public UsageFragment(IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            this(contextManager, targetIdentifier, includeTestFiles, null, UsageMode.FULL);
        }

        public UsageFragment(
                IContextManager contextManager, String targetIdentifier, boolean includeTestFiles, UsageMode mode) {
            this(contextManager, targetIdentifier, includeTestFiles, null, mode);
        }

        public UsageFragment(
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText) {
            this(
                    UUID.randomUUID().toString(),
                    contextManager,
                    targetIdentifier,
                    includeTestFiles,
                    snapshotText,
                    snapshotText == null ? UsageMode.FULL : inferUsageModeFromFrozenText(snapshotText));
        }

        public UsageFragment(
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText,
                UsageMode mode) {
            this(UUID.randomUUID().toString(), contextManager, targetIdentifier, includeTestFiles, snapshotText, mode);
        }

        public UsageFragment(
                String id,
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText) {
            this(
                    id,
                    contextManager,
                    targetIdentifier,
                    includeTestFiles,
                    snapshotText,
                    snapshotText == null ? UsageMode.FULL : inferUsageModeFromFrozenText(snapshotText));
        }

        public UsageFragment(
                String id,
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText,
                UsageMode mode) {
            super(
                    id,
                    contextManager,
                    "Uses of " + targetIdentifier,
                    "Uses of " + targetIdentifier,
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    snapshotText == null
                            ? null
                            : decodeFrozen(contextManager, snapshotText.getBytes(StandardCharsets.UTF_8)),
                    snapshotText == null
                            ? () -> computeSnapshotFor(targetIdentifier, includeTestFiles, mode, contextManager)
                            : null);
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
            this.mode = mode;
        }

        private static UsageMode inferUsageModeFromFrozenText(String snapshotText) {
            return snapshotText.contains("Call sites (") ? UsageMode.SAMPLE : UsageMode.FULL;
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

            // SAMPLE-mode frozen text also contains a call-site list. Rebuild sources/files from that list as well.
            var callSiteMatcher = Pattern.compile("(?m)^-\\s*`([^`]+)`(?:\\s*\\(([^)]*)\\))?\\s*$")
                    .matcher(text);
            while (callSiteMatcher.find()) {
                String fqName = callSiteMatcher.group(1).trim();
                if (fqName.isEmpty()) {
                    continue;
                }

                try {
                    units.addAll(analyzer.getDefinitions(fqName));
                } catch (Exception e) {
                    logger.warn("Unable to resolve call site CodeUnit for '{}'", fqName, e);
                }

                String meta = callSiteMatcher.group(2);
                if (meta != null) {
                    String metaTrimmed = meta.trim();

                    String pathCandidate = null;
                    if (metaTrimmed.startsWith("path=")) {
                        int comma = metaTrimmed.indexOf(',');
                        pathCandidate = (comma >= 0
                                ? metaTrimmed.substring("path=".length(), comma)
                                : metaTrimmed.substring("path=".length()));
                    } else {
                        int lastColon = metaTrimmed.lastIndexOf(':');
                        if (lastColon > 0) {
                            pathCandidate = metaTrimmed.substring(0, lastColon);
                        }
                    }

                    if (pathCandidate != null) {
                        String normalized = pathCandidate.trim().replace('\\', '/');
                        if (normalized.contains("/")) {
                            try {
                                files.add(contextManager.toFile(normalized));
                            } catch (Exception t) {
                                logger.warn("Unable to resolve ProjectFile for call site path '{}'", normalized, t);
                            }
                        }
                    }
                }
            }

            // Derive files from resolved units (call-site lines may not carry file paths).
            files.addAll(units.stream().map(CodeUnit::source).collect(Collectors.toCollection(LinkedHashSet::new)));

            return ContentSnapshot.textSnapshot(text, units, files);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        @Override
        public String repr() {
            if (mode == UsageMode.FULL) {
                return "SymbolUsages('%s', includeTestFiles=%s)".formatted(targetIdentifier, includeTestFiles);
            }
            return "SymbolUsages('%s', includeTestFiles=%s, mode=%s)"
                    .formatted(targetIdentifier, includeTestFiles, mode);
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }

        public boolean includeTestFiles() {
            return includeTestFiles;
        }

        public UsageMode mode() {
            return mode;
        }

        private static ContentSnapshot computeSnapshotFor(
                String targetIdentifier, boolean includeTestFiles, UsageMode mode, IContextManager contextManager)
                throws InterruptedException {
            IAnalyzer analyzer = contextManager.getAnalyzer();
            Predicate<ProjectFile> fileFilter =
                    includeTestFiles ? null : file -> !ContextManager.isTestFile(file, analyzer);

            // UsageFinder is the new name for the unified usage discovery service
            FuzzyResult usageResult =
                    UsageFinder.create(contextManager, fileFilter).findUsages(targetIdentifier);
            var either = usageResult.toEither();

            var definitions = analyzer.getDefinitions(targetIdentifier);
            boolean valid = !definitions.isEmpty();
            Optional<CodeUnit> definingOwner = definitions.stream().findFirst().map(def -> analyzer.parentOf(def)
                    .orElse(def));

            if (either.hasErrorMessage()) {
                return new ContentSnapshot(either.getErrorMessage(), Set.of(), Set.of(), (List<Byte>) null, valid);
            }

            List<UsageHit> externalHits = externalUsages(analyzer, definingOwner, either.getUsages());

            if (mode == UsageMode.SAMPLE) {
                return computeSampleSnapshot(targetIdentifier, analyzer, externalHits, valid);
            }

            return computeFullSnapshot(targetIdentifier, analyzer, externalHits, valid);
        }

        private static List<UsageHit> externalUsages(
                IAnalyzer analyzer, Optional<CodeUnit> definingOwner, Collection<UsageHit> hits) {
            Comparator<UsageHit> stableOrder = Comparator.comparing(
                            (UsageHit h) -> h.file().toString())
                    .thenComparingInt(UsageHit::line)
                    .thenComparingInt(UsageHit::startOffset);

            return hits.stream()
                    .filter(hit -> isExternalUsage(analyzer, definingOwner, hit))
                    .sorted(stableOrder)
                    .toList();
        }

        private static boolean isExternalUsage(IAnalyzer analyzer, Optional<CodeUnit> definingOwner, UsageHit hit) {
            if (definingOwner.isEmpty()) {
                return true;
            }

            CodeUnit hitOwner = analyzer.parentOf(hit.enclosing()).orElse(hit.enclosing());
            return !hitOwner.equals(definingOwner.get());
        }

        private static ContentSnapshot computeFullSnapshot(
                String targetIdentifier, IAnalyzer analyzer, List<UsageHit> externalHits, boolean valid) {
            if (externalHits.isEmpty()) {
                return new ContentSnapshot(
                        "No relevant usages found for symbol: " + targetIdentifier,
                        Set.of(),
                        Set.of(),
                        (List<Byte>) null,
                        valid);
            }

            List<CodeUnit> enclosingMethods =
                    externalHits.stream().map(UsageHit::enclosing).distinct().toList();

            List<AnalyzerUtil.CodeWithSource> parts = enclosingMethods.stream()
                    .map(cu -> analyzer.getSource(cu, true).map(src -> new AnalyzerUtil.CodeWithSource(src, cu)))
                    .flatMap(Optional::stream)
                    .toList();

            String header = "# Usages of " + targetIdentifier + "\n\nFound " + externalHits.size() + " call sites.\n\n";
            String body = AnalyzerUtil.CodeWithSource.text(analyzer, parts);
            String text = (header + body).trim();

            Set<CodeUnit> sources = new LinkedHashSet<>(enclosingMethods);
            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());
            return new ContentSnapshot(text, sources, files, (List<Byte>) null, valid);
        }

        private static ContentSnapshot computeSampleSnapshot(
                String targetIdentifier, IAnalyzer analyzer, List<UsageHit> externalHits, boolean valid) {
            if (externalHits.isEmpty()) {
                return new ContentSnapshot(
                        "No relevant usages found for symbol: " + targetIdentifier,
                        Set.of(),
                        Set.of(),
                        (List<Byte>) null,
                        valid);
            }

            List<UsageHit> hitsLimited = externalHits.stream().limit(50).toList();

            StringBuilder sb =
                    new StringBuilder("# Usages of ").append(targetIdentifier).append("\n\n");

            sb.append("Call sites (")
                    .append(externalHits.size())
                    .append(externalHits.size() > 50 ? ", showing first 50" : "")
                    .append("):\n");

            hitsLimited.forEach(hit -> sb.append("- `")
                    .append(hit.enclosing().fqName())
                    .append("` (")
                    .append(hit.file().getFileName())
                    .append(":")
                    .append(hit.line())
                    .append(")\n"));

            List<CodeUnit> distinctEnclosing =
                    hitsLimited.stream().map(UsageHit::enclosing).distinct().toList();

            sb.append("\nExamples:\n\n");

            List<AnalyzerUtil.CodeWithSource> processed =
                    AnalyzerUtil.processUsages(analyzer, distinctEnclosing).stream()
                            .filter(p -> !p.code().isBlank())
                            .toList();

            List<AnalyzerUtil.CodeWithSource> shortestExamples = processed.stream()
                    .sorted(Comparator.comparingInt(p -> p.code().length()))
                    .limit(3)
                    .toList();

            if (shortestExamples.isEmpty()) {
                sb.append("(source unavailable)\n");
            } else {
                sb.append(AnalyzerUtil.CodeWithSource.text(analyzer, shortestExamples))
                        .append("\n");
            }

            String text = sb.toString().trim();

            Set<CodeUnit> allSources = new LinkedHashSet<>(distinctEnclosing);
            Set<ProjectFile> files = allSources.stream().map(CodeUnit::source).collect(Collectors.toSet());
            return new ContentSnapshot(text, allSources, files, (List<Byte>) null, valid);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new UsageFragment(id, contextManager, targetIdentifier, includeTestFiles, null, mode);
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
            this(
                    id,
                    contextManager,
                    fullyQualifiedName,
                    snapshotText,
                    eagerUnit,
                    getFragmentExecutor().submit(() -> resolveMetadata(fullyQualifiedName, contextManager, eagerUnit)));
        }

        private CodeFragment(
                String id,
                IContextManager contextManager,
                String fullyQualifiedName,
                @Nullable String snapshotText,
                @Nullable CodeUnit eagerUnit,
                CompletableFuture<CodeUnitMetadata> metadataFuture) {
            super(
                    id,
                    contextManager,
                    ComputedValue.completed("desc-" + id, "Source for " + fullyQualifiedName),
                    new ComputedValue<>("short-" + id, metadataFuture.thenApply(CodeUnitMetadata::shortDescription)),
                    new ComputedValue<>("syntax-" + id, metadataFuture.thenApply(CodeUnitMetadata::syntaxStyle)),
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

        private record CodeUnitMetadata(String shortDescription, String syntaxStyle) {}

        private static CodeUnitMetadata resolveMetadata(
                String fqName, IContextManager contextManager, @Nullable CodeUnit eagerUnit) {
            if (eagerUnit != null) {
                return new CodeUnitMetadata(
                        eagerUnit.shortName(), eagerUnit.source().getSyntaxStyle());
            }
            return contextManager.getAnalyzerUninterrupted().getDefinitions(fqName).stream()
                    .findFirst()
                    .map(cu -> new CodeUnitMetadata(cu.shortName(), cu.source().getSyntaxStyle()))
                    .orElseGet(() -> new CodeUnitMetadata(fqName, SyntaxConstants.SYNTAX_STYLE_NONE));
        }

        private static ContentSnapshot decodeFrozen(String fullyQualifiedName, byte[] bytes, IAnalyzer analyzer) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            Set<CodeUnit> units = analyzer.getDefinitions(fullyQualifiedName).stream()
                    .filter(cu -> cu.isClass() || cu.isFunction())
                    .collect(Collectors.toSet());
            Set<ProjectFile> files = units.stream().map(CodeUnit::source).collect(Collectors.toSet());
            if (units.isEmpty()) {
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
            var analyzer = contextManager.getAnalyzerUninterrupted();
            List<CodeUnit> units = preResolvedUnit != null
                    ? List.of(preResolvedUnit)
                    : analyzer.getDefinitions(fqName).stream()
                            .filter(cu -> cu.isClass() || cu.isFunction())
                            .toList();

            if (units.isEmpty()) {
                return new ContentSnapshot("", Set.of(), Set.of(), (byte[]) null, false);
            }

            Set<String> allImports = new LinkedHashSet<>();
            List<String> textBlocks = new ArrayList<>();
            boolean hasAnySourceCode = false;

            for (CodeUnit unit : units) {
                var codeOpt = analyzer.getSource(unit, true);
                if (codeOpt.isPresent()) {
                    textBlocks.add(new AnalyzerUtil.CodeWithSource(codeOpt.get(), unit).text(analyzer));
                    hasAnySourceCode = true;

                    analyzer.as(ImportAnalysisProvider.class)
                            .map(p -> (Collection<String>) p.relevantImportsFor(unit))
                            .orElseGet(() -> analyzer.importStatementsOf(unit.source()))
                            .forEach(allImports::add);
                } else {
                    textBlocks.add("No source found for %s: %s".formatted(unit.kind(), unit.fqName()));
                }
            }

            String body = String.join("\n\n", textBlocks);
            String text;
            if (!allImports.isEmpty()) {
                List<String> orderedImports = new ArrayList<>(allImports);
                Collections.sort(orderedImports);
                text = "<imports>\n" + String.join("\n", orderedImports) + "\n</imports>\n\n" + body;
            } else {
                text = body;
            }

            Set<ProjectFile> files = units.stream().map(CodeUnit::source).collect(Collectors.toSet());
            return new ContentSnapshot(text, Set.copyOf(units), files, (List<Byte>) null, hasAnySourceCode);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CodeFragment(id, contextManager, fullyQualifiedName);
        }

        @Override
        @Blocking
        public Set<ContextFragment> supportingFragments() {
            IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
            return resolveAncestorFragments(List.copyOf(analyzer.getDefinitions(fullyQualifiedName)), contextManager);
        }
    }

    public static class LineRangeFragment extends AbstractComputedFragment {
        private final ProjectFile file;
        private final int startLine;
        private final int endLine;

        public LineRangeFragment(IContextManager contextManager, ProjectFile file, int startLine, int endLine) {
            this(UUID.randomUUID().toString(), contextManager, file, startLine, endLine, null);
        }

        public LineRangeFragment(
                String id,
                IContextManager contextManager,
                ProjectFile file,
                int startLine,
                int endLine,
                @Nullable String snapshotText) {
            super(
                    id,
                    contextManager,
                    computeDescription(file, startLine, endLine),
                    computeShortDescription(file, startLine, endLine),
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()),
                    snapshotText == null ? null : ContentSnapshot.textSnapshot(snapshotText, Set.of(), Set.of(file)),
                    snapshotText == null ? () -> computeSnapshotFor(file, startLine, endLine) : null);
            if (startLine < 1) {
                throw new IllegalArgumentException("startLine must be >= 1");
            }
            if (endLine < startLine) {
                throw new IllegalArgumentException("endLine must be >= startLine");
            }
            this.file = file;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        private static String computeDescription(ProjectFile file, int startLine, int endLine) {
            return "Line range %s:%d-%d".formatted(file.toString(), startLine, endLine);
        }

        private static String computeShortDescription(ProjectFile file, int startLine, int endLine) {
            return "%s:%d-%d".formatted(file.getFileName(), startLine, endLine);
        }

        private static ContentSnapshot computeSnapshotFor(ProjectFile file, int startLine, int endLine) {
            if (startLine < 1 || endLine < startLine) {
                return new ContentSnapshot("", Set.of(), Set.of(file), (List<Byte>) null, false);
            }
            if (!file.exists()) {
                return new ContentSnapshot("", Set.of(), Set.of(file), (List<Byte>) null, false);
            }
            var contentOpt = file.read();
            if (contentOpt.isEmpty()) {
                return new ContentSnapshot("", Set.of(), Set.of(file), (List<Byte>) null, false);
            }
            var range = Lines.range(contentOpt.get(), startLine, endLine);
            if (range.lineCount() == 0) {
                return new ContentSnapshot(
                        "No lines found in range %d-%d for file %s".formatted(startLine, endLine, file.toString()),
                        Set.of(),
                        Set.of(file),
                        (List<Byte>) null,
                        true);
            }
            int actualEnd = startLine + range.lineCount() - 1;
            String text = "File: %s (lines %d-%d)\n%s".formatted(file.toString(), startLine, actualEnd, range.text());
            return new ContentSnapshot(text, Set.of(), Set.of(file), (List<Byte>) null, true);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.LINE_RANGE;
        }

        @Override
        public ComputedValue<Set<ProjectFile>> sourceFiles() {
            return referencedFiles();
        }

        @Override
        public String repr() {
            return "LineRange('%s', %d, %d)".formatted(file.toString(), startLine, endLine);
        }

        public ProjectFile file() {
            return file;
        }

        public int startLine() {
            return startLine;
        }

        public int endLine() {
            return endLine;
        }

        @Override
        public ContextFragment refreshCopy() {
            return new LineRangeFragment(id, contextManager, file, startLine, endLine, null);
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
            return contextManager.getAnalyzerUninterrupted().getDefinitions(targetIdentifier).stream()
                    .findAny()
                    .map(cu -> "Summary of " + cu.shortName())
                    .orElse("Summary of " + targetIdentifier);
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

        @Override
        @Blocking
        public Set<ContextFragment> supportingFragments() {
            IAnalyzer analyzer = contextManager.getAnalyzerUninterrupted();
            var codeUnits =
                    switch (summaryType) {
                        case CODEUNIT_SKELETON -> analyzer.getDefinitions(targetIdentifier);
                        case FILE_SKELETONS -> {
                            // In this case, targetIdentifier is a relative file path so we extract the TLDs first
                            var file = contextManager.toFile(targetIdentifier);
                            yield analyzer.getTopLevelDeclarations(file);
                        }
                    };
            return resolveAncestorFragments(codeUnits, contextManager);
        }

        private static ContentSnapshot computeSnapshotFor(
                String targetIdentifier, SummaryType summaryType, IContextManager contextManager) {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            Map<CodeUnit, String> skeletonsMap = new LinkedHashMap<>();
            Set<CodeUnit> primaryTargets =
                    resolvePrimaryTargets(targetIdentifier, summaryType, analyzer, contextManager);

            for (CodeUnit cu : primaryTargets) {
                analyzer.getSkeleton(cu).ifPresent(s -> skeletonsMap.put(cu, s));
            }

            String text;
            if (skeletonsMap.isEmpty()) {
                text = "No summary found for: " + targetIdentifier;
            } else {
                text = new SkeletonFragmentFormatter()
                        .format(new SkeletonFragmentFormatter.Request(null, List.of(), skeletonsMap, summaryType));
                if (text.isEmpty()) text = "No summary found for: " + targetIdentifier;
            }

            Set<CodeUnit> sources = skeletonsMap.keySet();
            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());
            boolean valid = !primaryTargets.isEmpty();

            return new ContentSnapshot(text, sources, files, (List<Byte>) null, valid);
        }

        public record CodeUnitSkeleton(CodeUnit codeUnit, String skeleton) {}

        /**
         * We map skeletons by code units as FQ names are not unique in the case of overloaded methods, for example.
         */
        @Blocking
        private Map<CodeUnit, CodeUnitSkeleton> skeletonsByCodeUnit() {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            Map<CodeUnit, CodeUnitSkeleton> out = new LinkedHashMap<>();
            for (CodeUnit cu : sources().join()) {
                if (cu.isAnonymous()) {
                    continue;
                }
                analyzer.getSkeleton(cu)
                        .filter(s -> !s.isBlank())
                        .ifPresent(skeleton -> out.putIfAbsent(cu, new CodeUnitSkeleton(cu, skeleton)));
            }
            return out;
        }

        private record CodeUnitKey(CodeUnitType kind, String fqName, @Nullable String signature) {
            static CodeUnitKey of(CodeUnit cu) {
                return new CodeUnitKey(cu.kind(), cu.fqName(), cu.signature());
            }
        }

        /**
         * Combines multiple summary fragments into a single text block.
         *
         * <p>Semantic aggregation:
         * - Union CodeUnits across fragments via sources()
         * - Deduplicate by CodeUnit semantic identity (kind, fqName, signature)
         * - Exclude anonymous units
         * - Use each fragment's own analyzer to obtain skeleton text
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

            Map<CodeUnitKey, CodeUnitSkeleton> deduped = new LinkedHashMap<>();
            for (SummaryFragment fragment : fragments) {
                fragment.skeletonsByCodeUnit()
                        .forEach((cu, skeleton) -> deduped.putIfAbsent(CodeUnitKey.of(cu), skeleton));
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

        /**
         * Returns true if this summary fragment is superseded by a full fragment in the given collection.
         *
         * <ul>
         *   <li>A FILE_SKELETONS summary is superseded when a {@link ProjectPathFragment} covering
         *       the same file is present.</li>
         *   <li>A CODEUNIT_SKELETON summary is superseded when a {@link ProjectPathFragment} or
         *       {@link CodeFragment} whose resolved sources include the target class is present.</li>
         * </ul>
         */
        @Blocking
        public boolean isSupersededBy(Collection<? extends ContextFragment> candidates) {
            return switch (summaryType) {
                case FILE_SKELETONS ->
                    candidates.stream()
                            .filter(c -> c instanceof ProjectPathFragment)
                            .map(c -> (ProjectPathFragment) c)
                            .anyMatch(ppf -> ppf.file().toString().equals(targetIdentifier));
                case CODEUNIT_SKELETON ->
                    candidates.stream()
                            .filter(c -> c instanceof ProjectPathFragment || c instanceof CodeFragment)
                            .anyMatch(c -> c.sources().join().stream()
                                    .anyMatch(cu -> cu.fqName().equals(targetIdentifier)));
            };
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
                            Messages.format(history.stream()
                                    .flatMap(e -> e.mopMessages().stream())
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
                    ContentSnapshot.textSnapshot(Messages.format(history.stream()
                            .flatMap(e -> e.isCompressed()
                                    ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                    : castNonNull(e.mopLog()).messages().stream())
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
        private final boolean escapeHtml; // TODO wire this up or delete it, currently used by GitIssuesTab

        /**
         * @param description the user instructions or action goal
         */
        public TaskFragment(List<ChatMessage> messages, String description) {
            this(messages, description, true);
        }

        public TaskFragment(List<ChatMessage> messages, String description, boolean escapeHtml) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.TASK,
                            description,
                            Messages.format(messages),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            TaskFragment.class.getName()),
                    messages,
                    description,
                    escapeHtml);
        }

        public TaskFragment(String id, List<ChatMessage> messages, String description) {
            this(id, messages, description, true);
        }

        public TaskFragment(IContextManager contextManager, String description) {
            this(contextManager.getIo().getLlmRawMessages(), description, true);
        }

        public TaskFragment(String id, List<ChatMessage> messages, String description, boolean escapeHtml) {
            super(
                    id,
                    description,
                    description,
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    ContentSnapshot.textSnapshot(Messages.format(messages)));
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
