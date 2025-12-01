package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.AnalyzerUtil.CodeWithSource;
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
import ai.brokk.util.*;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Asynchronous ContextFragment API.
 * <p>
 * All fragments return ComputedValue<T> for their public data surfaces to avoid implicit blocking.
 * Callers must explicitly block if they require synchronous behavior (e.g., via future().join()).
 * <p>
 * This is a breaking change; do not expect call sites to compile until they are updated.
 *
 * <p>ContextFragment MUST be kept in sync with FrozenFragment: any polymorphic methods added to CF must be serialized
 * into FF so they can be accurately represented as well. If you are tasked with adding such a method to CF without also
 * having FF available to edit, you MUST decline the assignment and explain the problem.
 */
public interface ContextFragment {
    /**
     * Replaces polymorphic methods or instanceof checks with something that can easily apply to FrozenFragments as well
     */
    enum FragmentType {
        PROJECT_PATH,
        GIT_FILE,
        EXTERNAL_PATH,
        IMAGE_FILE,

        STRING,
        SKELETON,
        USAGE,
        CODE,
        CALL_GRAPH,
        HISTORY,
        TASK,
        PASTE_TEXT,
        PASTE_IMAGE,
        STACKTRACE,
        BUILD_LOG;

        private static final EnumSet<FragmentType> PATH_TYPES =
                EnumSet.of(PROJECT_PATH, GIT_FILE, EXTERNAL_PATH, IMAGE_FILE);

        private static final EnumSet<FragmentType> OUTPUT_TYPES = EnumSet.of(HISTORY, TASK);

        private static final EnumSet<FragmentType> EDITABLE_TYPES = EnumSet.of(PROJECT_PATH, USAGE, CODE);

        public boolean isPath() {
            return PATH_TYPES.contains(this);
        }

        public boolean isOutput() {
            return OUTPUT_TYPES.contains(this);
        }

        public boolean isEditable() {
            return EDITABLE_TYPES.contains(this);
        }
    }

    record FragmentSnapshot(
            String description,
            String shortDescription,
            String text,
            String syntaxStyle,
            Set<CodeUnit> sources,
            Set<ProjectFile> files,
            @Nullable List<Byte> imageByteList
            // may not be a byte[] object as records require immutability which cannot be enforced by arrays
            ) {

        public FragmentSnapshot(
                String description,
                String shortDescription,
                String text,
                String syntaxStyle,
                Set<CodeUnit> sources,
                Set<ProjectFile> files,
                byte @Nullable [] imageBytes) {
            this(description, shortDescription, text, syntaxStyle, sources, files, convertToList(imageBytes));
        }

        public FragmentSnapshot(String description, String shortDescription, String text, String syntaxStyle) {
            this(description, shortDescription, text, syntaxStyle, Set.of(), Set.of(), (List<Byte>) null);
        }

        public byte @Nullable [] imageBytes() {
            return convertToByteArray(imageByteList);
        }

        public static FragmentSnapshot EMPTY =
                new FragmentSnapshot("", "", "", "", Set.of(), Set.of(), (List<Byte>) null);
    }

    private static byte @Nullable [] convertToByteArray(@Nullable List<Byte> imageBytes) {
        if (imageBytes == null) {
            return null;
        }

        byte[] result = new byte[imageBytes.size()];
        for (int i = 0; i < imageBytes.size(); i++) {
            result[i] = imageBytes.get(i);
        }
        return result;
    }

    private static @Nullable List<Byte> convertToList(byte @Nullable [] arr) {
        if (arr == null) return null;

        return IntStream.range(0, arr.length).mapToObj(i -> arr[i]).toList(); // Creates an unmodifiable list
    }

    static String describe(Collection<ContextFragment> fragments) {
        return describe(fragments.stream());
    }

    @Blocking
    static String describe(Stream<ContextFragment> fragments) {
        return fragments
                .map(cf -> cf.description().join())
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    // Static counter for dynamic fragments
    AtomicInteger nextId = new AtomicInteger(1);

    // Dedicated executor for ContextFragment async computations (separate from ContextManager backgroundTasks)
    Logger logger = LogManager.getLogger(ContextFragment.class);

    @VisibleForTesting
    static LoggingExecutorService getFragmentExecutor() {
        return FRAGMENT_EXECUTOR;
    }

    /**
     * Forcefully shuts down the dedicated ContextFragment executor. Safe to call multiple times.
     * Intended for application shutdown to ensure no lingering threads keep the JVM alive.
     */
    static void shutdownFragmentExecutor() {
        try {
            FRAGMENT_EXECUTOR.shutdownNow();
        } catch (Throwable t) {
            logger.warn("Error shutting down fragment executor", t);
        }
    }

    LoggingExecutorService FRAGMENT_EXECUTOR = createFragmentExecutor();

    private static LoggingExecutorService createFragmentExecutor() {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("brokk-cf-", 0) // Prefix and starting counter
                .factory();
        ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        return new LoggingExecutorService(
                virtualExecutor,
                th -> logger.error("Uncaught exception in ContextFragment Virtual Thread executor", th));
    }

    /**
     * Gets the current max integer fragment ID used for generating new dynamic fragment IDs. Note: This refers to the
     * numeric part of dynamic IDs.
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Sets the next integer fragment ID value, typically called during deserialization to ensure new dynamic fragment
     * IDs don't collide with loaded numeric IDs.
     */
    static void setMinimumId(int value) {
        nextId.accumulateAndGet(value, Math::max);
    }

    /**
     * Unique identifier for this fragment. Can be a numeric string for dynamic fragments or a hash string for
     * static/frozen fragments.
     */
    String id();

    /**
     * The type of this fragment.
     */
    FragmentType getType();

    /**
     * short description in history (defaults to description).
     */
    default ComputedValue<String> shortDescription() {
        return description();
    }

    /**
     * longer description displayed in context table
     */
    ComputedValue<String> description();

    /**
     * raw content for preview
     */
    ComputedValue<String> text();

    /**
     * content formatted for LLM
     */
    ComputedValue<String> format();

    /**
     * fragment toc entry, usually id + description
     */
    default String formatToc() {
        // Non-blocking best-effort rendering
        return """
                <fragment-toc description="%s" fragmentid="%s" />
                """
                .formatted(description().renderNowOr(""), id());
    }

    default boolean isText() {
        return true;
    }

    /**
     * Optionally provide computed image payload; default is null for non-image fragments.
     */
    default @Nullable ComputedValue<byte[]> imageBytes() {
        return null;
    }

    /**
     * Return a string that can be provided to the appropriate WorkspaceTools method to recreate this fragment. Returns
     * an empty string for fragments that cannot be re-added without serializing their entire contents.
     */
    default String repr() {
        return "";
    }

    /**
     * Code sources found in this fragment.
     */
    ComputedValue<Set<CodeUnit>> sources();

    /**
     * Returns all repo files referenced by this fragment. This is used when we *just* want to manipulate or show actual
     * files, rather than the code units themselves.
     */
    ComputedValue<Set<ProjectFile>> files();

    /**
     * Syntax highlight style.
     */
    ComputedValue<String> syntaxStyle();

    /**
     * Exposes the full fragment snapshot as a ComputedValue.
     * Static fragments are completed immediately; computed fragments complete when ready.
     */
    ComputedValue<FragmentSnapshot> snapshot();

    /**
     * Exposes the full fragment snapshot if ready, or returns the FragmentSnapshot.EMPTY instance.
     */
    default FragmentSnapshot snapshotNowOrEmpty() {
        return snapshot().renderNowOr(ContextFragment.FragmentSnapshot.EMPTY);
    }

    default List<TaskEntry> entries() {
        return List.of();
    }

    /**
     * If false, the classes returned by sources() will be pruned from AutoContext suggestions. (Corollary: if sources()
     * always returns empty, this doesn't matter.)
     */
    default boolean isEligibleForAutoContext() {
        return true;
    }

    /**
     * Retrieves the {@link IContextManager} associated with this fragment.
     *
     * @return The context manager instance, or {@code null} if not applicable or available.
     */
    @Nullable
    IContextManager getContextManager();

    /**
     * Convenience method to get the analyzer in a non-blocking way using the fragment's context manager.
     *
     * @return The IAnalyzer instance if available, or null if it's not ready yet or if the context manager is not
     * available.
     */
    default IAnalyzer getAnalyzer() {
        var cm = getContextManager();
        requireNonNull(cm);
        return cm.getAnalyzerUninterrupted();
    }

    /**
     * Compares whether two fragments originate from the same "source" (file/symbol/session),
     * ignoring view parameters and content differences when that makes sense.
     */
    boolean hasSameSource(ContextFragment other);

    /**
     * Return a copy with cleared ComputedValues; identity (id) is preserved by default.
     * Implementations that track external state may override to trigger recomputation.
     */
    ContextFragment refreshCopy();

    /**
     * Marker for fragments whose identity is dynamic (numeric, session-local).
     * Such fragments must use numeric IDs; content-hash IDs are reserved for non-dynamic fragments.
     */
    interface DynamicIdentity {}

    /**
     * Marker interface for fragments that provide image content.
     * Implementations must provide a stable content hash for equality checks.
     */
    interface ImageFragment extends ContextFragment {
        /**
         * A stable, cached hash of the binary image content and relevant metadata.
         */
        String contentHash();
    }

    static void validateNumericId(String existingId) {
        try {
            int numericId = Integer.parseInt(existingId);
            setMinimumId(numericId + 1);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
        }
    }

    // Base implementation for fragments with async computation
    abstract class AbstractComputedFragment implements ContextFragment {
        protected final String id;
        protected final IContextManager contextManager;
        private final ComputedValue<FragmentSnapshot> snapshotCv;
        private final ConcurrentMap<String, ComputedValue<?>> derivedCvs = new ConcurrentHashMap<>();

        protected AbstractComputedFragment(String id, IContextManager contextManager) {
            this(id, contextManager, null);
        }

        protected AbstractComputedFragment(
                String id, IContextManager contextManager, @Nullable FragmentSnapshot initialSnapshot) {
            this.id = id;
            this.contextManager = contextManager;
            this.snapshotCv = initialSnapshot != null
                    ? ComputedValue.completed("snap-" + id, initialSnapshot)
                    : new ComputedValue<>("snap-" + id, getFragmentExecutor().submit(this::computeSnapshot));
        }

        public void await(Duration timeout) throws InterruptedException {
            snapshotCv.await(timeout);
        }

        @Override
        public ComputedValue<FragmentSnapshot> snapshot() {
            return snapshotCv;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        protected abstract FragmentSnapshot computeSnapshot();

        protected <T> ComputedValue<T> derived(String key, Function<FragmentSnapshot, T> extractor) {
            @SuppressWarnings("unchecked")
            ComputedValue<T> cv = (ComputedValue<T>) derivedCvs.computeIfAbsent(key, k -> snapshotCv.map(extractor));
            return cv;
        }

        @Override
        public ComputedValue<String> text() {
            return derived("text", FragmentSnapshot::text);
        }

        @Override
        public ComputedValue<String> description() {
            return derived("desc", FragmentSnapshot::description);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return derived("shortDesc", FragmentSnapshot::shortDescription);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return derived("syntax", FragmentSnapshot::syntaxStyle);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return derived("sources", FragmentSnapshot::sources);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return derived("files", FragmentSnapshot::files);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            // Can be overridden by image fragments
            return null;
        }

        @Override
        public ComputedValue<String> format() {
            return derived("format", this::formatTemplate);
        }

        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .formatted(s.description(), id(), s.text());
        }

        // Common hasSameSource implementation (identity for dynamic, override for others)
        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (this.getClass() != other.getClass()) return false;
            // Dynamic fragments use ID or repr
            if (this instanceof DynamicIdentity) {
                return (this.repr().equals(other.repr()) && !this.repr().isEmpty())
                        || this.id().equals(other.id());
            }
            // Content hashed fragments (if any computed ones are)
            return this.id().equals(other.id());
        }
    }

    // Base implementation for fragments with static/known content
    abstract class AbstractStaticFragment implements ContextFragment {
        protected final String id;
        protected final IContextManager contextManager;
        protected final FragmentSnapshot snapshot;

        protected AbstractStaticFragment(String id, IContextManager contextManager, FragmentSnapshot snapshot) {
            this.id = id;
            this.contextManager = contextManager;
            this.snapshot = snapshot;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("text-" + id, snapshot.text());
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed("desc-" + id, snapshot.description());
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("short-" + id, snapshot.shortDescription());
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("syntax-" + id, snapshot.syntaxStyle());
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
        public ComputedValue<String> format() {
            return ComputedValue.completed("format-" + id, formatTemplate(snapshot));
        }

        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .formatted(s.description(), id(), s.text());
        }

        @Override
        public ComputedValue<FragmentSnapshot> snapshot() {
            return ComputedValue.completed("snap-" + id, snapshot);
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
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment {
        BrokkFile file();

        @Override
        default boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof PathFragment op)) return false;
            return this.file().absPath().normalize().equals(op.file().absPath().normalize());
        }

        static String formatSummary(BrokkFile file) {
            return "<file source=\"%s\" />".formatted(file);
        }
    }

    final class ProjectPathFragment extends AbstractComputedFragment implements PathFragment, DynamicIdentity {
        private final ProjectFile file;

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null);
        }

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, snapshotText);
        }

        public static ProjectPathFragment withId(ProjectFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ProjectPathFragment withId(
                ProjectFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            validateNumericId(existingId);
            return new ProjectPathFragment(file, existingId, contextManager, snapshotText);
        }

        private static FragmentSnapshot decodeFrozen(ProjectFile file, IContextManager contextManager, byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String name = file.getFileName();
            String desc = file.getParent().equals(Path.of("")) ? name : "%s [%s]".formatted(name, file.getParent());
            String syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            Set<CodeUnit> sources;
            try {
                sources = contextManager.getAnalyzerUninterrupted().getDeclarations(file);
            } catch (Throwable t) {
                logger.error("Failed to analyze declarations for file {}, sources will be empty", name, t);
                sources = Set.of();
            }
            return new FragmentSnapshot(desc, name, text, syntax, sources, Set.of(file), (List<Byte>) null);
        }

        private ProjectPathFragment(
                ProjectFile file, String id, IContextManager contextManager, @Nullable String snapshotText) {
            this.file = file;
            super(
                    id,
                    contextManager,
                    snapshotText == null
                            ? null
                            : decodeFrozen(file, contextManager, snapshotText.getBytes(StandardCharsets.UTF_8)));
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
        protected FragmentSnapshot computeSnapshot() {
            String text = file.read().orElse("");
            String name = file.getFileName();
            String desc = file.getParent().equals(Path.of("")) ? name : "%s [%s]".formatted(name, file.getParent());
            String syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            Set<CodeUnit> sources = Set.of();
            try {
                sources = getAnalyzer().getDeclarations(file);
            } catch (Exception e) {
                logger.error("Failed to analyze declarations for file {}, sources will be empty", name, e);
            }

            return new FragmentSnapshot(desc, name, text, syntax, sources, Set.of(file), (List<Byte>) null);
        }

        @Override
        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <file path="%s" fragmentid="%s">
                    %s
                    </file>
                    """
                    .formatted(file.toString(), id(), s.text());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ProjectPathFragment(
                    file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            return PathFragment.super.hasSameSource(other);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(description().renderNowOr(file.toString()));
        }
    }

    record GitFileFragment(ProjectFile file, String revision, String content, String id) implements PathFragment {
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

        public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
            return new GitFileFragment(file, revision, content, existingId);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.GIT_FILE;
        }

        @Override
        public @Nullable IContextManager getContextManager() {
            return null;
        }

        // Manually implementing CV methods as this is a record and cannot extend AbstractStaticFragment
        @Override
        public ComputedValue<String> description() {
            var parentDir = file.getParent();
            var shortDesc = "%s @%s".formatted(file.getFileName(), revision);
            return ComputedValue.completed(
                    "gff-desc-" + id,
                    parentDir.equals(Path.of("")) ? shortDesc : "%s [%s]".formatted(shortDesc, parentDir));
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("gff-short-" + id, "%s @%s".formatted(file.getFileName(), revision));
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("gff-text-" + id, content);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            // Treat historical content as potentially different from current; don't claim sources
            return ComputedValue.completed(Set.of());
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed(Set.of(file));
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed(
                    FileTypeUtil.get().guessContentType(file.absPath().toFile()));
        }

        @Override
        public ComputedValue<String> format() {
            return ComputedValue.completed(
                    """
                            <file path="%s" revision="%s">
                            %s
                            </file>
                            """
                            .formatted(file.toString(), revision, content));
        }

        @Override
        public ComputedValue<FragmentSnapshot> snapshot() {
            var parentDir = file.getParent();
            var shortDesc = "%s @%s".formatted(file.getFileName(), revision);
            var desc = parentDir.equals(Path.of("")) ? shortDesc : "%s [%s]".formatted(shortDesc, parentDir);
            var syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            var snapshot =
                    new FragmentSnapshot(desc, shortDesc, content, syntax, Set.of(), Set.of(file), (List<Byte>) null);
            return ComputedValue.completed("gff-snap-" + id, snapshot);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof GitFileFragment that)) return false;
            return this.file()
                            .absPath()
                            .normalize()
                            .equals(that.file().absPath().normalize())
                    && this.revision().equals(that.revision());
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }
    }

    final class ExternalPathFragment extends AbstractComputedFragment implements PathFragment {
        private final ExternalFile file;

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null);
        }

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, snapshotText);
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            validateNumericId(existingId);
            return new ExternalPathFragment(file, existingId, contextManager, snapshotText);
        }

        private static FragmentSnapshot decodeFrozen(ExternalFile file, byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String name = file.toString();
            String syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            return new FragmentSnapshot(name, name, text, syntax);
        }

        private ExternalPathFragment(
                ExternalFile file, String id, IContextManager contextManager, @Nullable String snapshotText) {
            this.file = file;
            super(
                    id,
                    contextManager,
                    snapshotText != null ? decodeFrozen(file, snapshotText.getBytes(StandardCharsets.UTF_8)) : null);
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
        protected FragmentSnapshot computeSnapshot() {
            String text = file.read().orElse("");
            String name = file.toString();
            String syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            return new FragmentSnapshot(name, name, text, syntax);
        }

        @Override
        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <file path="%s" fragmentid="%s">
                    %s
                    </file>
                    """
                    .formatted(file.toString(), id(), s.text());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ExternalPathFragment(
                    file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            return PathFragment.super.hasSameSource(other);
        }
    }

    final class ImageFileFragment extends AbstractComputedFragment implements PathFragment, ImageFragment {
        private final BrokkFile file;

        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        private ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) {
            this.file = file;
            super(id, contextManager);
        }

        public static ImageFileFragment withId(BrokkFile file, String existingId, IContextManager contextManager) {
            validateNumericId(existingId);
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

        @Override
        protected FragmentSnapshot computeSnapshot() {
            String desc = file.toString();
            if (file instanceof ProjectFile pf && !pf.getParent().equals(Path.of(""))) {
                desc = "%s [%s]".formatted(file.getFileName(), pf.getParent());
            }

            byte[] bytes = null;
            try {
                var imageFile = file.absPath().toFile();
                if (imageFile.exists() && imageFile.canRead()) {
                    Image img = ImageIO.read(imageFile);
                    if (img != null) bytes = ImageUtil.imageToBytes(img);
                }
            } catch (IOException e) {
                // ignore
            }

            return new FragmentSnapshot(
                    desc,
                    file.getFileName(),
                    "[Image content provided out of band]",
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    Set.of(),
                    (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of(),
                    bytes);
        }

        @Override
        public ComputedValue<byte[]> imageBytes() {
            return derived("imageBytes", FragmentSnapshot::imageBytes);
        }

        @Override
        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <file path="%s" fragmentid="%s">
                    [Image content provided out of band]
                    </file>
                    """
                    .formatted(file.toString(), id());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new ImageFileFragment(
                    file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            return PathFragment.super.hasSameSource(other);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) return new ProjectPathFragment(pf, contextManager);
            if (bf instanceof ExternalFile ext) return new ExternalPathFragment(ext, contextManager);
        } else {
            return new ImageFileFragment(bf, contextManager);
        }
        throw new IllegalArgumentException(
                "Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    // StringFragmentType and getStringFragmentType helper
    record StringFragmentType(String description, String syntaxStyle) {}

    StringFragmentType BUILD_RESULTS =
            new StringFragmentType("Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
    StringFragmentType SEARCH_NOTES = new StringFragmentType("Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    StringFragmentType DISCARDED_CONTEXT =
            new StringFragmentType("Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);

    static @Nullable StringFragmentType getStringFragmentType(String description) {
        if (description.isBlank()) return null;
        if (BUILD_RESULTS.description().equals(description)) return BUILD_RESULTS;
        if (SEARCH_NOTES.description().equals(description)) return SEARCH_NOTES;
        if (DISCARDED_CONTEXT.description().equals(description)) return DISCARDED_CONTEXT;
        return null;
    }

    class StringFragment extends AbstractStaticFragment {
        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            this(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STRING, description, text, syntaxStyle, StringFragment.class.getName()),
                    contextManager,
                    text,
                    description,
                    syntaxStyle);
        }

        public StringFragment(
                String id, IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(id, contextManager, new FragmentSnapshot(description, description, text, syntaxStyle));
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        public Optional<SpecialTextType> specialType() {
            return SpecialTextType.fromDescription(snapshot.description());
        }

        public String previewSyntaxStyle() {
            return specialType().map(SpecialTextType::previewSyntaxStyle).orElse(snapshot.syntaxStyle());
        }

        public String previewText() {
            return specialType()
                    .map(st -> st.previewRenderer().apply(snapshot.text()))
                    .orElse(snapshot.text());
        }

        public String textForAgent(ViewingPolicy viewPolicy) {
            var st = specialType();
            if (st.isEmpty()) return snapshot.text();
            if (!st.get().canViewContent().test(viewPolicy)) {
                return "[%s content hidden for %s]"
                        .formatted(snapshot.description(), viewPolicy.taskType().name());
            }
            return snapshot.text();
        }

        public boolean droppable() {
            return specialType().map(SpecialTextType::droppable).orElse(true);
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(snapshot.description());
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (!(other instanceof StringFragment that)) return false;
            StringFragmentType thisType = getStringFragmentType(this.snapshot.description());
            StringFragmentType thatType = getStringFragmentType(that.snapshot.description());
            if (thisType != null && thatType != null) return Objects.equals(thisType, thatType);
            return this.snapshot.description().equals(that.snapshot.description())
                    && this.snapshot.syntaxStyle().equals(that.snapshot.syntaxStyle());
        }
    }

    class PasteTextFragment extends AbstractComputedFragment {
        private final String text;
        private final Future<String> descriptionFuture;
        private final Future<String> syntaxStyleFuture;

        public PasteTextFragment(
                IContextManager contextManager,
                String text,
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
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
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
            this.text = text;
            this.descriptionFuture = descriptionFuture;
            this.syntaxStyleFuture = syntaxStyleFuture;
            super(id, contextManager);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        protected FragmentSnapshot computeSnapshot() {
            String desc = "Paste of pasted content";
            String syntax = SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            try {
                String d = descriptionFuture.get(Context.CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                desc = "Paste of " + d;
            } catch (Exception e) {
                logger.error("Unable to compute PasteTextFragment description within specified timeout period", e);
            }
            try {
                if (syntaxStyleFuture.isDone()) syntax = syntaxStyleFuture.get();
            } catch (Exception e) {
                logger.error("Unable to compute PasteTextFragment syntax style within specified timeout period", e);
            }

            return new FragmentSnapshot(desc, desc, text, syntax);
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }

        public Future<String> getDescriptionFuture() {
            return descriptionFuture;
        }

        public Future<String> getSyntaxStyleFuture() {
            return syntaxStyleFuture;
        }
    }

    class AnonymousImageFragment extends AbstractComputedFragment implements ImageFragment {
        private final Image image;
        final Future<String> descriptionFuture;

        public AnonymousImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
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
                String id, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            this.image = image;
            this.descriptionFuture = descriptionFuture;
            super(id, contextManager);
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

        @Override
        protected FragmentSnapshot computeSnapshot() {
            String desc = "(Error summarizing paste)";
            try {
                desc = descriptionFuture.get();
            } catch (Exception e) {
                logger.error("Unable to compute AnonymousImageFragment description", e);
            }
            byte[] bytes = imageToBytes(image);
            return new FragmentSnapshot(
                    desc,
                    desc,
                    "[Image content provided out of band]",
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    Set.of(),
                    Set.of(),
                    bytes);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            return derived("imageBytes", FragmentSnapshot::imageBytes);
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }

        public Future<String> getDescriptionFuture() {
            return descriptionFuture;
        }
    }

    class StacktraceFragment extends AbstractStaticFragment {
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
            this.original = original;
            this.exception = exception;
            this.code = code;
            super(
                    id,
                    contextManager,
                    new FragmentSnapshot(
                            "stacktrace of " + exception,
                            "stacktrace of " + exception,
                            original + "\n\nStacktrace methods in this project:\n\n" + code,
                            sources.isEmpty()
                                    ? SyntaxConstants.SYNTAX_STYLE_NONE
                                    : sources.iterator().next().source().getSyntaxStyle(),
                            sources,
                            sources.stream().map(CodeUnit::source).collect(Collectors.toSet()),
                            (List<Byte>) null));
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

    class UsageFragment extends AbstractComputedFragment implements DynamicIdentity {
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
            this(
                    String.valueOf(ContextFragment.nextId.getAndIncrement()),
                    contextManager,
                    targetIdentifier,
                    includeTestFiles,
                    snapshotText);
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
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
            super(
                    id,
                    contextManager,
                    snapshotText != null
                            ? decodeFrozen(targetIdentifier, snapshotText.getBytes(StandardCharsets.UTF_8))
                            : null);
        }

        private static FragmentSnapshot decodeFrozen(String targetIdentifier, byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String desc = "Uses of " + targetIdentifier;
            return new FragmentSnapshot(desc, desc, text, SyntaxConstants.SYNTAX_STYLE_NONE);
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

        @Override
        protected FragmentSnapshot computeSnapshot() {
            var analyzer = getAnalyzer();
            FuzzyResult usageResult =
                    FuzzyUsageFinder.create(getContextManager()).findUsages(targetIdentifier);
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
                List<CodeWithSource> parts = AnalyzerUtil.processUsages(
                        analyzer, uses.stream().map(UsageHit::enclosing).toList());
                String formatted = CodeWithSource.text(parts);
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

            String syntax = sources.stream()
                    .findFirst()
                    .map(s -> s.source().getSyntaxStyle())
                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE);

            return new FragmentSnapshot(
                    "Uses of " + targetIdentifier, "Uses of " + targetIdentifier, text, syntax, sources, files, (List<
                                    Byte>)
                            null);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new UsageFragment(id, contextManager, targetIdentifier, includeTestFiles, null);
        }
    }

    class CodeFragment extends AbstractComputedFragment implements DynamicIdentity {
        private final String fullyQualifiedName;
        private @Nullable CodeUnit preResolvedUnit;

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName) {
            this(contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName, @Nullable String snapshotText) {
            this(
                    String.valueOf(ContextFragment.nextId.getAndIncrement()),
                    contextManager,
                    fullyQualifiedName,
                    snapshotText);
        }

        public CodeFragment(String id, IContextManager contextManager, String fullyQualifiedName) {
            this(id, contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(
                String id, IContextManager contextManager, String fullyQualifiedName, @Nullable String snapshotText) {
            this.fullyQualifiedName = fullyQualifiedName;
            super(
                    id,
                    contextManager,
                    snapshotText != null
                            ? decodeFrozen(
                                    fullyQualifiedName,
                                    snapshotText.getBytes(StandardCharsets.UTF_8),
                                    contextManager.getAnalyzerUninterrupted())
                            : null);
        }

        private static FragmentSnapshot decodeFrozen(String fullyQualifiedName, byte[] bytes, IAnalyzer analyzer) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String desc = "Source for " + fullyQualifiedName;
            String syntax = SyntaxConstants.SYNTAX_STYLE_NONE;
            Set<CodeUnit> units = Set.of();
            Set<ProjectFile> files = Set.of();
            try {
                var unit = analyzer.getDefinitions(fullyQualifiedName).stream()
                        .findFirst()
                        .orElseThrow();
                units = Set.of(unit);
                var file = unit.source();
                files = Set.of(file);
                syntax = FileTypeUtil.get().guessContentType(file.absPath().toFile());
            } catch (IllegalArgumentException e) {
                logger.warn("Unable to resolve CodeUnit for fqName: {}", fullyQualifiedName);
            }

            return new FragmentSnapshot(desc, fullyQualifiedName, text, syntax, units, files, (List<Byte>) null);
        }

        public CodeFragment(IContextManager contextManager, CodeUnit unit) {
            this.fullyQualifiedName = unit.fqName();
            this.preResolvedUnit = unit;
            super(String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
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

        @Override
        protected FragmentSnapshot computeSnapshot() {
            CodeUnit unit = preResolvedUnit;
            if (unit == null) {
                unit = getAnalyzer().getDefinitions(fullyQualifiedName).stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unable to resolve CodeUnit for fqName: " + fullyQualifiedName));
            }

            String text;
            var scpOpt = getAnalyzer().as(SourceCodeProvider.class);
            if (scpOpt.isEmpty()) {
                text = "Code Intelligence cannot extract source for: " + fullyQualifiedName;
            } else {
                var scp = scpOpt.get();
                if (unit.isFunction()) {
                    String code = scp.getMethodSource(unit, true).orElse("");
                    text = !code.isEmpty()
                            ? new AnalyzerUtil.CodeWithSource(code, unit).text()
                            : "No source found for method: " + fullyQualifiedName;
                } else {
                    String code = scp.getClassSource(unit, true).orElse("");
                    text = !code.isEmpty()
                            ? new AnalyzerUtil.CodeWithSource(code, unit).text()
                            : "No source found for class: " + fullyQualifiedName;
                }
            }

            return new FragmentSnapshot(
                    "Source for " + unit.fqName(),
                    unit.fqName(),
                    text,
                    unit.source().getSyntaxStyle(),
                    Set.of(unit),
                    Set.of(unit.source()),
                    (List<Byte>) null);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CodeFragment(id, contextManager, fullyQualifiedName);
        }
    }

    class CallGraphFragment extends AbstractComputedFragment implements DynamicIdentity {
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph;

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            this(
                    String.valueOf(ContextFragment.nextId.getAndIncrement()),
                    contextManager,
                    methodName,
                    depth,
                    isCalleeGraph);
        }

        public CallGraphFragment(
                String id, IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
            super(id, contextManager);
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

        @Override
        protected FragmentSnapshot computeSnapshot() {
            var analyzer = getAnalyzer();
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

            String type = isCalleeGraph ? "Callees" : "Callers";
            String desc = "%s of %s (depth %d)".formatted(type, methodName, depth);
            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());

            return new FragmentSnapshot(
                    desc, desc, text, SyntaxConstants.SYNTAX_STYLE_NONE, sources, files, (List<Byte>) null);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CallGraphFragment(id, contextManager, methodName, depth, isCalleeGraph);
        }
    }

    enum SummaryType {
        CODEUNIT_SKELETON,
        FILE_SKELETONS
    }

    // Formatter class kept for logic reuse
    class SkeletonFragmentFormatter {
        public record Request(
                @Nullable CodeUnit primaryTarget,
                List<CodeUnit> ancestors,
                Map<CodeUnit, String> skeletons,
                SummaryType summaryType) {}

        public String format(Request request) {
            if (request.summaryType() == SummaryType.CODEUNIT_SKELETON
                    && request.primaryTarget() != null
                    && request.primaryTarget().isClass()) {
                return formatSummaryWithAncestors(request.primaryTarget(), request.ancestors(), request.skeletons());
            } else {
                return formatSkeletonsByPackage(request.skeletons());
            }
        }

        private String formatSummaryWithAncestors(
                CodeUnit cu, List<CodeUnit> ancestorList, Map<CodeUnit, String> skeletons) {
            Map<CodeUnit, String> primary = new LinkedHashMap<>();
            skeletons.forEach((k, v) -> {
                if (k.fqName().equals(cu.fqName())) primary.put(k, v);
            });
            var sb = new StringBuilder();
            String primaryFormatted = formatSkeletonsByPackage(primary);
            if (!primaryFormatted.isEmpty()) sb.append(primaryFormatted).append("\n\n");
            if (!ancestorList.isEmpty()) {
                String ancestorNames =
                        ancestorList.stream().map(CodeUnit::shortName).collect(Collectors.joining(", "));
                sb.append("// Direct ancestors of ")
                        .append(cu.shortName())
                        .append(": ")
                        .append(ancestorNames)
                        .append("\n\n");
                Map<CodeUnit, String> ancestorsMap = new LinkedHashMap<>();
                ancestorList.forEach(anc -> {
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
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().packageName().isEmpty()
                                    ? "(default package)"
                                    : e.getKey().packageName(),
                            Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)));
            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> "package " + pkgEntry.getKey() + ";\n\n"
                            + String.join("\n\n", pkgEntry.getValue().values()))
                    .collect(Collectors.joining("\n\n"));
        }
    }

    class SummaryFragment extends AbstractComputedFragment implements DynamicIdentity {
        private final String targetIdentifier;
        private final SummaryType summaryType;

        public SummaryFragment(IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            this(
                    String.valueOf(ContextFragment.nextId.getAndIncrement()),
                    contextManager,
                    targetIdentifier,
                    summaryType);
        }

        public SummaryFragment(
                String id, IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
            super(id, contextManager);
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
        protected FragmentSnapshot computeSnapshot() {
            var analyzer = getAnalyzer();
            Map<CodeUnit, String> skeletonsMap = new LinkedHashMap<>();
            var skeletonProviderOpt = analyzer.as(SkeletonProvider.class);
            Set<CodeUnit> primaryTargets = resolvePrimaryTargets(analyzer);

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

            String desc = "Summary of %s".formatted(targetIdentifier);
            Set<CodeUnit> sources = skeletonsMap.keySet();
            Set<ProjectFile> files = sources.stream().map(CodeUnit::source).collect(Collectors.toSet());

            return new FragmentSnapshot(
                    desc, desc, text, SyntaxConstants.SYNTAX_STYLE_JAVA, sources, files, (List<Byte>) null);
        }

        public static String combinedText(List<SummaryFragment> fragments) {
            return fragments.stream()
                    .map(sf -> sf.text().join())
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining("\n\n"));
        }

        private Set<CodeUnit> resolvePrimaryTargets(IAnalyzer analyzer) {
            if (summaryType == SummaryType.CODEUNIT_SKELETON) return analyzer.getDefinitions(targetIdentifier);
            if (summaryType == SummaryType.FILE_SKELETONS)
                return new LinkedHashSet<>(
                        analyzer.getTopLevelDeclarations(getContextManager().toFile(targetIdentifier)));
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

    interface OutputFragment {
        List<TaskEntry> entries();

        default boolean isEscapeHtml() {
            return true;
        }
    }

    class HistoryFragment extends AbstractStaticFragment implements OutputFragment {
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
            this.history = List.copyOf(history);
            super(
                    id,
                    contextManager,
                    new FragmentSnapshot(
                            "Conversation (" + history.size() + " thread%s)".formatted(history.size() > 1 ? "s" : ""),
                            "Conversation (" + history.size() + " thread%s)".formatted(history.size() > 1 ? "s" : ""),
                            TaskEntry.formatMessages(history.stream()
                                    .flatMap(e -> e.isCompressed()
                                            ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                            : castNonNull(e.log()).messages().stream())
                                    .toList()),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN));
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

        @Override
        protected String formatTemplate(FragmentSnapshot s) {
            return """
                    <taskhistory fragmentid="%s">
                    %s
                    </taskhistory>
                    """
                    .formatted(id(), s.text());
        }
    }

    class TaskFragment extends AbstractStaticFragment implements OutputFragment {
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
            this.messages = List.copyOf(messages);
            this.escapeHtml = escapeHtml;
            super(
                    id,
                    contextManager,
                    new FragmentSnapshot(
                            description,
                            description,
                            TaskEntry.formatMessages(messages),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN));
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
