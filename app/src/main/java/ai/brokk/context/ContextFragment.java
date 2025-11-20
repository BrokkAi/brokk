package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AnalyzerUtil;
import ai.brokk.AnalyzerUtil.CodeWithSource;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.IProject;
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
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.util.*;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
        SEARCH,
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

        private static final EnumSet<FragmentType> VIRTUAL_TYPES = EnumSet.of(
                STRING,
                SEARCH,
                SKELETON,
                USAGE,
                CODE,
                CALL_GRAPH,
                HISTORY,
                TASK,
                PASTE_TEXT,
                PASTE_IMAGE,
                STACKTRACE,
                BUILD_LOG);

        private static final EnumSet<FragmentType> OUTPUT_TYPES = EnumSet.of(SEARCH, HISTORY, TASK);

        private static final EnumSet<FragmentType> EDITABLE_TYPES = EnumSet.of(PROJECT_PATH, USAGE, CODE);

        public boolean isPath() {
            return PATH_TYPES.contains(this);
        }

        public boolean isVirtual() {
            return VIRTUAL_TYPES.contains(this);
        }

        public boolean isOutput() {
            return OUTPUT_TYPES.contains(this);
        }

        public boolean isEditable() {
            return EDITABLE_TYPES.contains(this);
        }
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
    public static void shutdownFragmentExecutor() {
        try {
            FRAGMENT_EXECUTOR.shutdownNow();
        } catch (Throwable t) {
            logger.warn("Error shutting down fragment executor", t);
        }
    }

    // IMPORTANT: Keep corePoolSize <= maximumPoolSize on low-core CI runners.
    // We once saw macOS CI with 2 vCPUs blow up during static init with
    // IllegalArgumentException("corePoolSize > maximumPoolSize"). To make this robust,
    // we pick a safe parallelism and set core == max. With an unbounded queue, core==max
    // is the correct configuration to avoid IllegalArgumentException and unexpected scaling.
    // Additionally: use daemon threads and allow core thread timeout so the JVM can exit cleanly without explicit
    // shutdown.
    LoggingExecutorService FRAGMENT_EXECUTOR = createFragmentExecutor();

    private static LoggingExecutorService createFragmentExecutor() {
        // Build a daemon thread factory with helpful names
        ThreadFactory baseFactory = Executors.defaultThreadFactory();
        ThreadFactory daemonFactory = r -> {
            var t = baseFactory.newThread(r);
            t.setDaemon(true);
            t.setName("brokk-cf-" + t.threadId());
            return t;
        };

        var tpe = new ThreadPoolExecutor(
                0,
                Runtime.getRuntime().availableProcessors() * 3,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                daemonFactory);
        return new LoggingExecutorService(
                tpe, th -> logger.error("Uncaught exception in ContextFragment executor", th));
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
    default ComputedValue<String> format() {
        return new ComputedValue<>(
                "cf-format-" + id(),
                () ->
                        """
                                <fragment description="%s" fragmentid="%s">
                                %s
                                </fragment>
                                """
                                .formatted(
                                        description().future().join(),
                                        id(),
                                        text().future().join()),
                getFragmentExecutor());
    }

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
     * Retrieves the frozen contents, if any. Returns <code>null</code> if none is persisted.
     */
    byte @Nullable [] getFrozenContentBytes();

    /**
     * Retrieves the frozen contents, if any as a UTF-8 string. Returns <code>null</code> if none is persisted.
     */
    default @Nullable String getSnapshotTextOrNull() {
        var bytes = getFrozenContentBytes();
        if (bytes != null) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Sets the persisted immutable snapshot content for this fragment.
     * Implementations should treat this as write-once and ignore subsequent calls when already set.
     * Default implementation is a no-op.
     */
    default void setFrozenContentBytes(byte[] bytes) {
        // no-op by default
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
     * Helper for thread-safe, lazy initialization of ComputedValue fields.
     * - Does not trigger computation during construction.
     * - Uses double-checked locking to avoid duplicate work.
     * - Caches the instance via setter on first initialization.
     * <p>
     * Note: We pass a currentSupplier to re-read the latest field value inside the synchronized block.
     */
    default <T> ComputedValue<T> lazyInitCv(
            @Nullable ComputedValue<T> current,
            Supplier<@Nullable ComputedValue<T>> currentSupplier,
            Supplier<ComputedValue<T>> factory,
            Consumer<ComputedValue<T>> setter) {
        var local = current;
        if (local == null) {
            synchronized (this) {
                local = currentSupplier.get();
                if (local == null) {
                    local = factory.get();
                    setter.accept(local);
                }
            }
        }
        return local;
    }

    /**
     * Eagerly start async computations for this fragment. Non-blocking.
     * Safe to call multiple times; ComputedValue ensures single evaluation.
     */
    default void primeComputations() {
        var envVar = System.getenv("BRK_EAGER_FRAGMENTS");
        var eagerFragmentsEnabled = envVar != null && (envVar.isBlank() || Boolean.parseBoolean(envVar));
        if (eagerFragmentsEnabled) {
            shortDescription().start();
            description().start();
            text().start();
            syntaxStyle().start();
            sources().start();
            files().start();
            format().start();
            var ib = imageBytes();
            if (ib != null) {
                ib.start();
            }
            // Prime additional surfaces for specific fragment types
            if (this instanceof ContextFragment.CodeFragment cf) {
                cf.computedUnit().start();
            }
        }
    }

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

    static Set<ProjectFile> parseProjectFiles(String text, IProject project) {
        var exactMatches = project.getAllFiles().stream()
                .parallel()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        return project.getAllFiles().stream()
                .parallel()
                .filter(f -> text.contains(f.getFileName()))
                .collect(Collectors.toSet());
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment {
        BrokkFile file();

        @Override
        default ComputedValue<Set<ProjectFile>> files() {
            BrokkFile bf = file();
            if (bf instanceof ProjectFile pf) {
                return ComputedValue.completed("pf-files-" + id(), Set.of(pf));
            }
            return ComputedValue.completed("pf-files-" + id(), Set.of());
        }

        @Override
        default ComputedValue<String> text() {
            return new ComputedValue<>("pf-text-" + id(), () -> file().read().orElse(""), getFragmentExecutor());
        }

        @Override
        default ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed(
                    "pf-syntax-" + id(),
                    FileTypeUtil.get().guessContentType(file().absPath().toFile()));
        }

        @Override
        default ComputedValue<String> format() {
            return new ComputedValue<>(
                    "pf-format-" + id(),
                    () ->
                            """
                                    <file path="%s" fragmentid="%s">
                                    %s
                                    </file>
                                    """
                                    .formatted(
                                            file().toString(),
                                            id(),
                                            text().future().join()),
                    getFragmentExecutor());
        }

        @Override
        default boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof PathFragment op)) {
                return false;
            }
            var pa = this.file().absPath().normalize();
            var pb = op.file().absPath().normalize();
            return pa.equals(pb);
        }

        static String formatSummary(BrokkFile file) {
            return "<file source=\"%s\" />".formatted(file);
        }
    }

    final class ProjectPathFragment implements PathFragment {
        private final ProjectFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable String frozenContent;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private transient @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private transient @Nullable ComputedValue<String> formatCv;

        // Primary constructor for new dynamic fragments (no snapshot)
        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null, true);
        }

        // Primary constructor for new dynamic fragments (with optional snapshot)
        public ProjectPathFragment(ProjectFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, snapshotText, true);
        }

        private ProjectPathFragment(
                ProjectFile file,
                String id,
                IContextManager contextManager,
                @Nullable String snapshotText,
                boolean eagerPrime) {
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
            this.frozenContent = snapshotText;
            if (eagerPrime) {
                this.primeComputations();
            }
        }

        @Override
        public String id() {
            return id;
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
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ProjectPathFragment withId(ProjectFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ProjectPathFragment withId(
                ProjectFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ProjectPathFragment(file, existingId, contextManager, snapshotText, true);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("ppf-short-" + id(), file().getFileName());
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> ComputedValue.completed("ppf-files-" + id(), Set.of(file)),
                    v -> filesCv = v);
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>(
                            "ppf-desc-" + id(),
                            () -> {
                                if (file.getParent().equals(Path.of(""))) {
                                    return file.getFileName();
                                }
                                return "%s [%s]".formatted(file.getFileName(), file.getParent());
                            },
                            getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public String repr() {
            return "File(['%s'])".formatted(file.toString());
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> new ComputedValue<>(
                            "ppf-sources-" + id(), () -> getAnalyzer().getDeclarations(file), getFragmentExecutor()),
                    v -> sourcesCv = v);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(description().renderNowOr(file.toString()));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public ContextFragment refreshCopy() {
            // Generate a new dynamic fragment with no eager priming to avoid populating snapshot during refresh.
            return new ProjectPathFragment(
                    file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null, false);
        }

        @Override
        public ComputedValue<String> text() {
            if (frozenContent != null) {
                return ComputedValue.completed("ppf-text-" + id(), frozenContent);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "ppf-text-" + id(),
                            () -> {
                                String s = file.read().orElse("");
                                // capture snapshot bytes so serialization can persist stable content
                                if (this.frozenContent == null) {
                                    this.frozenContent = s;
                                }
                                return s;
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> ComputedValue.completed(
                            "ppf-syntax-" + id(),
                            FileTypeUtil.get().guessContentType(file.absPath().toFile())),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<String> format() {
            return lazyInitCv(
                    formatCv,
                    () -> formatCv,
                    () -> new ComputedValue<>(
                            "ppf-format-" + id(),
                            () ->
                                    """
                                            <file path="%s" fragmentid="%s">
                                            %s
                                            </file>
                                            """
                                            .formatted(
                                                    file().toString(),
                                                    id(),
                                                    this.text().future().join()),
                            getFragmentExecutor()),
                    v -> formatCv = v);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof PathFragment op)) {
                return false;
            }
            var pa = this.file().absPath().normalize();
            var pb = op.file().absPath().normalize();
            return pa.equals(pb);
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            if (this.frozenContent == null) {
                this.frozenContent = new String(bytes, StandardCharsets.UTF_8);
            }
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var content = this.frozenContent;
            if (content == null) {
                return null;
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Represents a specific revision of a ProjectFile from Git history. This is non-dynamic.
     */
    record GitFileFragment(ProjectFile file, String revision, String content, String id) implements PathFragment {
        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(
                    file,
                    revision,
                    content,
                    FragmentUtils.calculateContentHash(
                            FragmentType.GIT_FILE,
                            String.format("%s @%s", file.getFileName(), revision),
                            content, // text content for hash
                            FileTypeUtil.get().guessContentType(file.absPath().toFile()), // syntax style for hash
                            GitFileFragment.class.getName() // original class name for hash
                            ));
        }

        @Override
        public FragmentType getType() {
            return FragmentType.GIT_FILE;
        }

        @Override
        public @Nullable IContextManager getContextManager() {
            return null; // GitFileFragment does not have a context manager
        }

        // Constructor for use with DTOs where ID is already known (expected to be a hash)
        public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
            // For GitFileFragment, existingId is expected to be the content hash.
            // No need to update ContextFragment.nextId.
            return new GitFileFragment(file, revision, content, existingId);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("gff-short-" + id, "%s @%s".formatted(file().getFileName(), id));
        }

        @Override
        public ComputedValue<String> description() {
            var parentDir = file.getParent();
            var shortDesc = "%s @%s".formatted(file().getFileName(), id);
            var desc = parentDir.equals(Path.of("")) ? shortDesc : "%s [%s]".formatted(shortDesc, parentDir);
            return ComputedValue.completed("gff-desc-" + id, desc);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            // Treat historical content as potentially different from current; don't claim sources
            return ComputedValue.completed("gff-sources-" + id, Set.of());
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("gff-text-" + id, content);
        }

        @Override
        public ComputedValue<String> format() {
            return ComputedValue.completed(
                    "gff-format-" + id,
                    """
                            <file path="%s" revision="%s">
                            %s
                            </file>
                            """
                            .formatted(file().toString(), revision(), content));
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            // GitFileFragment content is immutable; ignore setter
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof GitFileFragment that)) {
                return false;
            }
            var pa = this.file().absPath().normalize();
            var pb = that.file().absPath().normalize();
            return pa.equals(pb) && this.revision().equals(that.revision());
        }

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, id);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }
    }

    final class ExternalPathFragment implements PathFragment {
        private final ExternalFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable String frozenContent;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private transient @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private transient @Nullable ComputedValue<String> formatCv;

        // Primary constructor for new dynamic fragments
        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null, true);
        }

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager, @Nullable String snapshotText) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, snapshotText, true);
        }

        private ExternalPathFragment(
                ExternalFile file,
                String id,
                IContextManager contextManager,
                @Nullable String snapshotText,
                boolean eagerPrime) {
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
            this.frozenContent = snapshotText;

            if (eagerPrime) {
                this.primeComputations();
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BrokkFile file() {
            return file;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.EXTERNAL_PATH;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager) {
            return withId(file, existingId, contextManager, null);
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager, @Nullable String snapshotText) {
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ExternalPathFragment(file, existingId, contextManager, snapshotText, true);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return description();
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> ComputedValue.completed("epf-desc-" + id(), file.toString()),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> ComputedValue.completed("epf-src-" + id(), Set.of()),
                    v -> sourcesCv = v);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Generate a new dynamic fragment with no eager priming to avoid populating snapshot during refresh.
            return new ExternalPathFragment(
                    file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager, null, false);
        }

        @Override
        public ComputedValue<String> text() {
            var snapshotText = getSnapshotTextOrNull();
            if (snapshotText != null) {
                return ComputedValue.completed("epf-text-" + id(), snapshotText);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "epf-text-" + id(),
                            () -> {
                                String s = file.read().orElse("");
                                // capture snapshot bytes so serialization can persist stable content
                                if (this.frozenContent == null) {
                                    this.frozenContent = s;
                                }
                                return s;
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> ComputedValue.completed(
                            "epf-syntax-" + id(),
                            FileTypeUtil.get().guessContentType(file.absPath().toFile())),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> ComputedValue.completed("epf-files-" + id(), Set.of()),
                    v -> filesCv = v);
        }

        @Override
        public ComputedValue<String> format() {
            return lazyInitCv(
                    formatCv,
                    () -> formatCv,
                    () -> new ComputedValue<>(
                            "epf-format-" + id(),
                            () ->
                                    """
                                            <file path="%s" fragmentid="%s">
                                            %s
                                            </file>
                                            """
                                            .formatted(
                                                    file().toString(),
                                                    id(),
                                                    this.text().future().join()),
                            getFragmentExecutor()),
                    v -> formatCv = v);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof PathFragment op)) {
                return false;
            }
            var pa = this.file().absPath().normalize();
            var pb = op.file().absPath().normalize();
            return pa.equals(pb);
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            if (this.frozenContent == null) {
                this.frozenContent = new String(bytes, StandardCharsets.UTF_8);
            }
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var content = this.frozenContent;
            if (content == null) {
                return null;
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Represents an image file, either from the project or external. This is dynamic.
     */
    final class ImageFileFragment implements PathFragment, ImageFragment {
        private final BrokkFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private transient @Nullable ComputedValue<byte[]> imageBytesCv;
        private transient byte @Nullable [] frozenContent;
        private transient @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private transient @Nullable ComputedValue<String> formatCv;

        // Primary constructor for new dynamic fragments
        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        private ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
            this.primeComputations();
        }

        @Override
        public String id() {
            return id;
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
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ImageFileFragment withId(BrokkFile file, String existingId, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ImageFileFragment(file, existingId, contextManager);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("iff-short-" + id, file().getFileName());
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>(
                            "iff-desc-" + id(),
                            () -> {
                                if (file instanceof ProjectFile pf
                                        && !pf.getParent().equals(Path.of(""))) {
                                    return "%s [%s]".formatted(file.getFileName(), pf.getParent());
                                }
                                return file.toString(); // For ExternalFile or root ProjectFile
                            },
                            getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public ComputedValue<String> text() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> ComputedValue.completed("iff-text-" + id(), "[Image content provided out of band]"),
                    v -> textCv = v);
        }

        @Blocking
        private Image readImage() throws UncheckedIOException {
            try {
                var imageFile = file.absPath().toFile();
                if (!imageFile.exists()) {
                    throw new UncheckedIOException(new IOException("Image file does not exist: " + file.absPath()));
                }
                if (!imageFile.canRead()) {
                    throw new UncheckedIOException(
                            new IOException("Cannot read image file (permission denied): " + file.absPath()));
                }

                Image result = ImageIO.read(imageFile);
                if (result == null) {
                    // ImageIO.read() returns null if no registered ImageReader can read the file
                    throw new UncheckedIOException(new IOException(
                            "Unable to read image file (unsupported format or corrupted): " + file.absPath()));
                }
                return result;
            } catch (IOException e) {
                throw new UncheckedIOException(new IOException("Failed to read image file: " + file.absPath(), e));
            }
        }

        @Override
        public String contentHash() {
            return id;
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> ComputedValue.completed("iff-src-" + id(), Set.of()),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> ComputedValue.completed(
                            "iff-files-" + id(), (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of()),
                    v -> filesCv = v);
        }

        @Override
        public ComputedValue<String> format() {
            return lazyInitCv(
                    formatCv,
                    () -> formatCv,
                    () -> new ComputedValue<>(
                            "iff-format-" + id(),
                            () ->
                                    """
                                            <file path="%s" fragmentid="%s">
                                            [Image content provided out of band]
                                            </file>
                                            """
                                            .formatted(file().toString(), id()),
                            getFragmentExecutor()),
                    v -> formatCv = v);
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            var currImageBytesCv = this.imageBytesCv;
            if (currImageBytesCv == null || currImageBytesCv.tryGet().isEmpty()) {
                if (currImageBytesCv != null) {
                    currImageBytesCv.future().cancel(true);
                }
                this.imageBytesCv = ComputedValue.completed("iff-image-" + id(), bytes);
            }
            // also persist to the snapshot field for symmetry with getFrozenContentBytes()
            this.frozenContent = bytes;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return frozenContent;
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> ComputedValue.completed("iff-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_NONE),
                    v -> syntaxCv = v);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            return lazyInitCv(
                    imageBytesCv,
                    () -> imageBytesCv,
                    () -> new ComputedValue<>(
                            "iff-image-" + id(),
                            () -> {
                                try {
                                    byte[] data = ImageUtil.imageToBytes(readImage());
                                    if (this.frozenContent == null) {
                                        this.frozenContent = data;
                                    }
                                    return data;
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            },
                            getFragmentExecutor()),
                    v -> imageBytesCv = v);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (!(other instanceof PathFragment op)) {
                return false;
            }
            var pa = this.file().absPath().normalize();
            var pb = op.file().absPath().normalize();
            return pa.equals(pb);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }

        @Override
        public ContextFragment refreshCopy() {
            return ImageFileFragment.withId(file, id, contextManager);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new ProjectPathFragment(pf, contextManager); // Dynamic ID
            } else if (bf instanceof ExternalFile ext) {
                return new ExternalPathFragment(ext, contextManager); // Dynamic ID
            }
        } else {
            // If it's not text, treat it as an image
            return new ImageFileFragment(bf, contextManager); // Dynamic ID
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException(
                "Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        protected final String id; // numeric or content hash
        protected final transient IContextManager contextManager;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private transient @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private transient @Nullable ComputedValue<String> formatCv;

        // Constructor for dynamic VirtualFragments that use nextId
        public VirtualFragment(IContextManager contextManager) {
            this.id = String.valueOf(ContextFragment.nextId.getAndIncrement());
            this.contextManager = contextManager;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        // Constructor for VirtualFragments with a pre-determined ID (e.g., hash or from DTO)
        protected VirtualFragment(String existingId, IContextManager contextManager) {
            this.id = existingId;
            this.contextManager = contextManager;
            // If the existingId is numeric (from a dynamic fragment that was frozen/unfrozen or loaded),
            // ensure nextId is updated for future dynamic fragments.
            try {
                int numericId = Integer.parseInt(existingId);
                ContextFragment.setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                // Allow non-numeric IDs for non-dynamic fragments (content-hashed).
                // Enforce numeric IDs only for dynamic-identity fragments.
                if (this instanceof DynamicIdentity) {
                    throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
                }
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ComputedValue<String> format() {
            return lazyInitCv(
                    formatCv,
                    () -> formatCv,
                    () -> new ComputedValue<>(
                            "vf-format-" + id(),
                            () ->
                                    """
                                            <fragment description="%s" fragmentid="%s">
                                            %s
                                            </fragment>
                                            """
                                            .formatted(
                                                    description().future().join(),
                                                    id(),
                                                    text().future().join()),
                            getFragmentExecutor()),
                    v -> formatCv = v);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            var cd = description();
            return ComputedValue.completed("vf-short-" + id(), cd.renderNowOr(""));
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>(
                            "vf-files-" + id(),
                            () -> parseProjectFiles(text().future().join(), contextManager.getProject()),
                            getFragmentExecutor()),
                    v -> filesCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> ComputedValue.completed("vf-sources-" + id(), Set.of()),
                    v -> sourcesCv = v);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;

            if (this.getClass() != other.getClass()) {
                return false;
            }

            var thisIsDynamic = this instanceof DynamicIdentity;
            var otherIsDynamic = other instanceof DynamicIdentity;

            // Non-dynamic (content-hashed) fragments: stable identity via ID
            if (!thisIsDynamic && !otherIsDynamic) {
                return this.id().equals(other.id());
            }

            // Dynamic fragments: use repr() for semantic equivalence
            if (thisIsDynamic && otherIsDynamic) {
                var ra = this.repr();
                var rb = other.repr();
                // Empty repr means fragment doesn't support semantic deduplication; fall back to identity
                if (ra.isEmpty() || rb.isEmpty()) {
                    return this.id().equals(other.id());
                }
                return ra.equals(rb);
            }

            // Images: compare stable content identity
            if (this instanceof ImageFragment ai && other instanceof ImageFragment bi) {
                return ai.contentHash().equals(bi.contentHash());
            }

            return false;
        }

        // Use identity-based equals (default Object behavior)
        // Explicit content-equality checks will use hasSameSource() or dedicated methods
    }

    record StringFragmentType(String description, String syntaxStyle) {}

    StringFragmentType BUILD_RESULTS =
            new StringFragmentType("Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
    StringFragmentType SEARCH_NOTES = new StringFragmentType("Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    StringFragmentType DISCARDED_CONTEXT =
            new StringFragmentType("Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);

    /**
     * Maps a description string to its corresponding StringFragmentType if it matches one of the
     * hardcoded StringFragmentTypes (BUILD_RESULTS, SEARCH_NOTES, DISCARDED_CONTEXT).
     *
     * @param description the description to match
     * @return the matching StringFragmentType, or null if no match found
     */
    static @Nullable StringFragmentType getStringFragmentType(String description) {
        if (description.isBlank()) {
            return null;
        }
        if (BUILD_RESULTS.description().equals(description)) {
            return BUILD_RESULTS;
        }
        if (SEARCH_NOTES.description().equals(description)) {
            return SEARCH_NOTES;
        }
        if (DISCARDED_CONTEXT.description().equals(description)) {
            return DISCARDED_CONTEXT;
        }
        return null;
    }

    class StringFragment extends VirtualFragment { // Non-dynamic, uses content hash
        private final String text;
        private final String description;
        private final String syntaxStyle;

        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STRING, description, text, syntaxStyle, StringFragment.class.getName()),
                    contextManager);
            this.syntaxStyle = syntaxStyle;
            this.text = text;
            this.description = description;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public StringFragment(
                String existingHashId,
                IContextManager contextManager,
                String text,
                String description,
                String syntaxStyle) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.syntaxStyle = syntaxStyle;
            this.text = text;
            this.description = description;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("sf-text-" + id, text);
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed("sf-desc-" + id, description);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("sf-syntax-" + id, syntaxStyle);
        }

        /**
         * Returns the SpecialTextType for this fragment if the description matches a registered special type.
         */
        public Optional<SpecialTextType> specialType() {
            return SpecialTextType.fromDescription(description);
        }

        /**
         * Returns the syntax style to use when rendering the preview. Delegates to SpecialTextType when present;
         * falls back to this fragment's internal syntaxStyle() otherwise.
         */
        public String previewSyntaxStyle() {
            var st = specialType();
            return st.map(SpecialTextType::previewSyntaxStyle).orElse(syntaxStyle);
        }

        /**
         * Returns a UI-friendly preview of the text using the SpecialTextType's previewRenderer when available.
         * Falls back to the raw text for non-special fragments.
         */
        public String previewText() {
            var st = specialType();
            return st.map(specialTextType -> specialTextType.previewRenderer().apply(text))
                    .orElse(text);
        }

        /**
         * Returns text according to the viewing policy. If the SpecialTextType denies viewing
         * content for the provided policy, a generic placeholder is returned. Otherwise the raw
         * text is returned. For non-special fragments, returns raw text.
         */
        public String textForAgent(ViewingPolicy viewPolicy) {
            var st = specialType();
            if (st.isEmpty()) {
                return text;
            }
            if (!st.get().canViewContent().test(viewPolicy)) {
                return "[%s content hidden for %s]"
                        .formatted(description, viewPolicy.taskType().name());
            }
            return text;
        }

        /**
         * Returns whether this fragment is droppable according to its SpecialTextType policy.
         * Non-special fragments default to droppable.
         */
        public boolean droppable() {
            return specialType().map(SpecialTextType::droppable).orElse(true);
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (!(other instanceof StringFragment that)) {
                return false;
            }

            StringFragmentType thisType = getStringFragmentType(this.description);
            StringFragmentType thatType = getStringFragmentType(that.description);

            if (thisType != null && thatType != null) {
                // Both descriptions map to StringFragmentTypes entries
                return Objects.equals(thisType, thatType);
            }

            // Default behavior: compare text and syntax style for non-system fragments
            return description.equals(that.description) && syntaxStyle.equals(that.syntaxStyle);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    // FIXME SearchFragment does not preserve the tool calls output that the user sees during
    // the search, I think we need to add a messages parameter and pass them to super();
    // then we'd also want to override format() to keep it out of what the LLM sees
    class SearchFragment extends TaskFragment { // Non-dynamic (content-hashed via TaskFragment)
        private final Set<CodeUnit> sources; // This is pre-computed, so SearchFragment is not dynamic in content

        public SearchFragment(
                IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            // The ID (hash) is calculated by the TaskFragment constructor based on sessionName and messages.
            super(contextManager, messages, sessionName, true);
            this.sources = sources;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public SearchFragment(
                String existingHashId,
                IContextManager contextManager,
                String sessionName,
                List<ChatMessage> messages,
                Set<CodeUnit> sources) {
            super(
                    existingHashId,
                    contextManager,
                    EditBlockParser.instance,
                    messages,
                    sessionName,
                    true); // existingHashId is expected to be a content hash
            this.sources = sources;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SEARCH;
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return ComputedValue.completed("sf-sources-" + id(), sources);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed(
                    "sf-files-" + id(), sources.stream().map(CodeUnit::source).collect(Collectors.toSet()));
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }
    }

    abstract class PasteFragment extends ContextFragment.VirtualFragment {
        protected transient Future<String> descriptionFuture;
        private @Nullable ComputedValue<String> descriptionCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesFuture;

        // PasteFragments are non-dynamic (content-hashed)
        // The hash will be based on the initial text/image data, not the future description.
        // Lazily initializes computed values on first access to avoid any background work during construction.
        public PasteFragment(String id, IContextManager contextManager, Future<String> descriptionFuture) {
            super(id, contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descriptionCv,
                    () -> descriptionCv,
                    () -> new ComputedValue<>(
                            "paste-desc-" + id(),
                            () -> {
                                try {
                                    return "Paste of " + descriptionFuture.get();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            getFragmentExecutor()),
                    v -> descriptionCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesFuture,
                    () -> filesFuture,
                    () -> ComputedValue.completed("paste-files-" + id(), Set.of()),
                    v -> filesFuture = v);
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description().renderNowOr("(paste)"));
        }

        public Future<String> getDescriptionFuture() {
            return descriptionFuture;
        }

        @Override
        public ContextFragment refreshCopy() {
            // Paste fragments are static; we don't need to recompute or clone.
            // Keeping the same instance preserves the content-hash id and ComputedValues.
            return this;
        }
    }

    class PasteTextFragment extends PasteFragment { // Non-dynamic, content-hashed
        private final String text;
        protected transient Future<String> syntaxStyleFuture;
        private @Nullable ComputedValue<String> syntaxCv;
        private @Nullable ComputedValue<String> textCv;

        public PasteTextFragment(
                IContextManager contextManager,
                String text,
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_TEXT,
                            "(Pasting text)", // Initial description for hashing before future completes
                            text,
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // Default syntax style for hashing
                            PasteTextFragment.class.getName()),
                    contextManager,
                    descriptionFuture);
            this.text = text;
            this.syntaxStyleFuture = syntaxStyleFuture;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public PasteTextFragment(
                String existingHashId, IContextManager contextManager, String text, Future<String> descriptionFuture) {
            this(
                    existingHashId,
                    contextManager,
                    text,
                    descriptionFuture,
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_MARKDOWN));
        }

        public PasteTextFragment(
                String existingHashId,
                IContextManager contextManager,
                String text,
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            this.text = text;
            this.syntaxStyleFuture = syntaxStyleFuture;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>(
                            "ptf-syntax-" + id(),
                            () -> {
                                if (syntaxStyleFuture.isDone()) {
                                    try {
                                        return syntaxStyleFuture.get();
                                    } catch (Exception e) {
                                        return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
                                    }
                                }
                                return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
                            },
                            getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<String> text() {
            return lazyInitCv(
                    textCv, () -> textCv, () -> ComputedValue.completed("ptf-text-" + id(), text), v -> textCv = v);
        }

        @Override
        public ComputedValue<String> description() {
            return super.description();
        }

        public Future<String> getSyntaxStyleFuture() {
            return syntaxStyleFuture;
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("ptf-short-" + id(), "pasted text");
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (!(other instanceof PasteTextFragment that)) {
                return false;
            }
            return text.equals(that.text);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    class AnonymousImageFragment extends PasteFragment implements ImageFragment { // Non-dynamic, content-hashed
        private final Image image;
        private @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<byte[]> imageBytesCv;
        private transient byte @Nullable [] frozenContent;

        // Helper to get image bytes, might throw UncheckedIOException
        @Nullable
        private static byte[] imageToBytes(@Nullable Image image) {
            try {
                return ImageUtil.imageToBytes(image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public AnonymousImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_IMAGE,
                            "(Pasting image)", // Initial description for hashing
                            null, // No text content for image
                            imageToBytes(image), // image bytes for hashing
                            false, // isTextFragment = false
                            SyntaxConstants.SYNTAX_STYLE_NONE,
                            Set.of(), // No project files
                            AnonymousImageFragment.class.getName(),
                            Map.of()), // No specific meta for hashing
                    contextManager,
                    descriptionFuture);
            this.image = image;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public AnonymousImageFragment(
                String existingHashId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            this.image = image;
            this.primeComputations();
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
        public ComputedValue<String> text() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> ComputedValue.completed("aif-text-" + id(), "[Image content provided out of band]"),
                    v -> textCv = v);
        }

        @Override
        public @Nullable ComputedValue<byte[]> imageBytes() {
            return lazyInitCv(
                    imageBytesCv,
                    () -> imageBytesCv,
                    () -> new ComputedValue<>(
                            "aif-image-" + id(),
                            () -> {
                                byte[] data = imageToBytes(image);
                                if (this.frozenContent == null) {
                                    this.frozenContent = data;
                                }
                                return data;
                            },
                            getFragmentExecutor()),
                    v -> imageBytesCv = v);
        }

        @Override
        public String contentHash() {
            return id();
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            var currImageBytesCv = this.imageBytesCv;
            if (currImageBytesCv == null || currImageBytesCv.tryGet().isEmpty()) {
                if (currImageBytesCv != null) {
                    currImageBytesCv.future().cancel(true);
                }
                this.imageBytesCv = ComputedValue.completed("aif-image-" + id(), bytes);
            }
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return frozenContent;
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("aif-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_NONE);
        }

        @Override
        public ComputedValue<String> format() {
            return new ComputedValue<>(
                    "aif-format-" + id(),
                    () ->
                            """
                                    <fragment description="%s" fragmentid="%s">
                                    %s
                                    </fragment>
                                    """
                                    .formatted(
                                            description().future().join(),
                                            id(),
                                            text().future().join()),
                    getFragmentExecutor());
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed("aif-files-" + id(), Set.of());
        }

        @Override
        public ComputedValue<String> description() {
            return new ComputedValue<>(
                    "aif-desc-" + id(),
                    () -> {
                        try {
                            return getDescriptionFuture().get();
                        } catch (Exception e) {
                            return "(Error summarizing paste)";
                        }
                    },
                    getFragmentExecutor());
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return ComputedValue.completed("aif-short-" + id(), "pasted image");
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }
    }

    class StacktraceFragment extends VirtualFragment { // Non-dynamic, content-hashed
        private final Set<CodeUnit> sources; // Pre-computed, so not dynamic in content
        private final String original;
        private final String exception;
        private final String code; // Pre-computed code parts

        public StacktraceFragment(
                IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STACKTRACE,
                            "stacktrace of " + exception,
                            original + "\n\nStacktrace methods in this project:\n\n" + code, // Full text for hash
                            sources.isEmpty()
                                    ? SyntaxConstants.SYNTAX_STYLE_NONE
                                    : sources.iterator().next().source().getSyntaxStyle(),
                            StacktraceFragment.class.getName()),
                    contextManager);
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public StacktraceFragment(
                String existingHashId,
                IContextManager contextManager,
                Set<CodeUnit> sources,
                String original,
                String exception,
                String code) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STACKTRACE;
        }

        private String internalText() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public ComputedValue<String> text() {
            return ComputedValue.completed("stf-text-" + id(), internalText());
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return ComputedValue.completed("stf-sources-" + id(), sources);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed(
                    "stf-files-" + id(), sources.stream().map(CodeUnit::source).collect(Collectors.toSet()));
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed("stf-desc-" + id(), "stacktrace of " + exception);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            if (sources.isEmpty()) {
                return ComputedValue.completed("stf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_NONE);
            }
            var firstClass = sources.iterator().next();
            return ComputedValue.completed(
                    "stf-syntax-" + id(), firstClass.source().getSyntaxStyle());
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

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            return internalText().getBytes(StandardCharsets.UTF_8);
        }
    }

    class UsageFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String targetIdentifier;
        private final boolean includeTestFiles;
        private volatile @Nullable String snapshotText;
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private @Nullable ComputedValue<String> syntaxCv;
        private @Nullable ComputedValue<String> descCv;

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
            super(contextManager); // Assigns dynamic numeric String ID
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
            this.snapshotText = snapshotText;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public UsageFragment(String existingId, IContextManager contextManager, String targetIdentifier) {
            this(existingId, contextManager, targetIdentifier, true, null);
        }

        public UsageFragment(
                String existingId, IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            this(existingId, contextManager, targetIdentifier, includeTestFiles, null);
        }

        public UsageFragment(
                String existingId,
                IContextManager contextManager,
                String targetIdentifier,
                boolean includeTestFiles,
                @Nullable String snapshotText) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
            this.snapshotText = snapshotText;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        public ComputedValue<String> text() {
            // Prefer frozen snapshot if available
            var snap = getSnapshotTextOrNull();
            if (snap != null) {
                return ComputedValue.completed("usg-text-" + id(), snap);
            }
            if (snapshotText != null) {
                return ComputedValue.completed("usg-text-" + id(), snapshotText);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "usg-text-" + id(),
                            () -> {
                                var analyzer = getAnalyzer();
                                FuzzyResult usageResult = FuzzyUsageFinder.create(getContextManager())
                                        .findUsages(targetIdentifier);

                                var either = usageResult.toEither();
                                if (either.hasErrorMessage()) {
                                    String err = either.getErrorMessage();
                                    snapshotText = err;
                                    return err;
                                }

                                List<CodeWithSource> parts = processUsages(analyzer, either);
                                String formatted = CodeWithSource.text(parts);
                                String result = formatted.isEmpty()
                                        ? "No relevant usages found for symbol: " + targetIdentifier
                                        : formatted;
                                // capture snapshot so serialization can persist stable content
                                if (!result.isBlank()) {
                                    snapshotText = result;
                                }
                                return result;
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        private List<CodeWithSource> processUsages(IAnalyzer analyzer, FuzzyResult.EitherUsagesOrError either) {
            List<UsageHit> uses = either.getUsages().stream()
                    .sorted(Comparator.comparingDouble(UsageHit::confidence).reversed())
                    .toList();
            if (!includeTestFiles) {
                uses = uses.stream()
                        .filter(cu -> !ContextManager.isTestFile(cu.file()))
                        .toList();
            }
            return AnalyzerUtil.processUsages(
                    analyzer, uses.stream().map(UsageHit::enclosing).toList());
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> new ComputedValue<>(
                            "usg-sources-" + id(),
                            () -> {
                                var analyzer = getAnalyzer();
                                if (analyzer.isEmpty()) {
                                    return Collections.emptySet();
                                }
                                FuzzyResult usageResult = FuzzyUsageFinder.create(getContextManager())
                                        .findUsages(targetIdentifier);
                                var either = usageResult.toEither();
                                if (either.hasErrorMessage()) {
                                    return Collections.emptySet();
                                }
                                List<CodeWithSource> parts = processUsages(analyzer, either);
                                return parts.stream()
                                        .map(AnalyzerUtil.CodeWithSource::source)
                                        .collect(Collectors.toSet());
                            },
                            getFragmentExecutor()),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>(
                            "usg-files-" + id(),
                            () -> {
                                final var allSources =
                                        this.sources().future().join().stream().map(CodeUnit::source);
                                if (!includeTestFiles) {
                                    return allSources
                                            .filter(source -> !ContextManager.isTestFile(source))
                                            .collect(Collectors.toSet());
                                } else {
                                    return allSources.collect(Collectors.toSet());
                                }
                            },
                            getFragmentExecutor()),
                    v -> filesCv = v);
        }

        @Override
        public String repr() {
            return "SymbolUsages('%s', includeTestFiles=%s)".formatted(targetIdentifier, includeTestFiles);
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> ComputedValue.completed("usg-desc-" + id(), "Uses of %s".formatted(targetIdentifier)),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>(
                            "usg-syntax-" + id(),
                            () -> this.sources().future().join().stream()
                                    .findFirst()
                                    .map(s -> s.source().getSyntaxStyle())
                                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE),
                            getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }

        public boolean includeTestFiles() {
            return includeTestFiles;
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            if (this.snapshotText == null) {
                this.snapshotText = new String(bytes, StandardCharsets.UTF_8);
            }
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var content = this.snapshotText;
            if (content == null) {
                return null;
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Create a new instance without snapshot so text recomputes from live data
            return new UsageFragment(id(), getContextManager(), targetIdentifier, includeTestFiles);
        }
    }

    class CodeFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String fullyQualifiedName;
        private volatile @Nullable String snapshotText;
        private @Nullable ComputedValue<CodeUnit> unitCv;
        private @Nullable CodeUnit preResolvedUnit;
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<String> descCv;
        private @Nullable ComputedValue<String> shortCv;
        private @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private @Nullable ComputedValue<String> syntaxCv;

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName) {
            this(contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName, @Nullable String snapshotText) {
            super(contextManager);
            assert !fullyQualifiedName.isBlank();
            this.fullyQualifiedName = fullyQualifiedName;
            this.snapshotText = snapshotText;
            this.primeComputations();
        }

        public CodeFragment(String existingId, IContextManager contextManager, String fullyQualifiedName) {
            this(existingId, contextManager, fullyQualifiedName, null);
        }

        public CodeFragment(
                String existingId,
                IContextManager contextManager,
                String fullyQualifiedName,
                @Nullable String snapshotText) {
            super(existingId, contextManager);
            assert !fullyQualifiedName.isBlank();
            this.fullyQualifiedName = fullyQualifiedName;
            this.snapshotText = snapshotText;
            this.primeComputations();
        }

        /**
         * A convenience constructor for if we already have our code unit, to avoid unnecessary re-computation.
         */
        public CodeFragment(IContextManager contextManager, CodeUnit unit) {
            super(contextManager);
            validateCodeUnit(unit);
            this.fullyQualifiedName = unit.fqName();
            this.preResolvedUnit = unit;
            this.snapshotText = null;
            this.primeComputations();
        }

        private static void validateCodeUnit(CodeUnit unit) {
            if (!(unit.isClass() || unit.isFunction())) {
                throw new IllegalArgumentException(unit.toString());
            }
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CODE;
        }

        public ComputedValue<CodeUnit> computedUnit() {
            return lazyInitCv(
                    unitCv,
                    () -> unitCv,
                    () -> {
                        var pr = preResolvedUnit;
                        if (pr != null) {
                            return ComputedValue.completed("cf-unit-" + id(), pr);
                        }
                        return new ComputedValue<>(
                                "cf-unit-" + id(),
                                () -> {
                                    var analyzer = getAnalyzer();
                                    return analyzer.getDefinition(fullyQualifiedName)
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    "Unable to resolve CodeUnit for fqName: " + fullyQualifiedName));
                                },
                                getFragmentExecutor());
                    },
                    v -> unitCv = v);
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> computedUnit()
                            .future()
                            .thenApply(CodeUnit::shortName)
                            .thenApply(shortName -> "Source for " + shortName)
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> shortDescription() {
            return lazyInitCv(
                    shortCv,
                    () -> shortCv,
                    () -> computedUnit()
                            .future()
                            .thenApply(CodeUnit::shortName)
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> shortCv = v);
        }

        public ComputedValue<String> text() {
            // Prefer frozen snapshot if available
            var snap = getSnapshotTextOrNull();
            if (snap != null) {
                return ComputedValue.completed("cf-text-" + id(), snap);
            }
            if (snapshotText != null) {
                return ComputedValue.completed("cf-text-" + id(), snapshotText);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "cf-text-" + id(),
                            () -> {
                                var analyzer = getAnalyzer();
                                var unit = computedUnit().future().join();
                                var maybeSourceCodeProvider = analyzer.as(SourceCodeProvider.class);

                                String result;
                                if (maybeSourceCodeProvider.isEmpty()) {
                                    result = "Code Intelligence cannot extract source for: " + fullyQualifiedName;
                                } else {
                                    var scp = maybeSourceCodeProvider.get();
                                    if (unit.isFunction()) {
                                        var code =
                                                scp.getMethodSource(unit, true).orElse("");
                                        if (!code.isEmpty()) {
                                            result = new AnalyzerUtil.CodeWithSource(code, unit).text();
                                        } else {
                                            result = "No source found for method: " + fullyQualifiedName;
                                        }
                                    } else {
                                        var code =
                                                scp.getClassSource(unit, true).orElse("");
                                        if (!code.isEmpty()) {
                                            result = new AnalyzerUtil.CodeWithSource(code, unit).text();
                                        } else {
                                            result = "No source found for class: " + fullyQualifiedName;
                                        }
                                    }
                                }
                                // capture snapshot so serialization can persist stable content
                                if (!result.isBlank()) {
                                    snapshotText = result;
                                }
                                return result;
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> computedUnit()
                            .future()
                            .thenApply(u -> Set.of(u))
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> this.sources()
                            .future()
                            .thenApply(set -> set.stream().map(CodeUnit::source).collect(Collectors.toSet()))
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> filesCv = v);
        }

        @Override
        public String repr() {
            return "Method(['%s'])".formatted(fullyQualifiedName);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> computedUnit()
                            .future()
                            .thenApply(u -> u.source().getSyntaxStyle())
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> syntaxCv = v);
        }

        public String getFullyQualifiedName() {
            return fullyQualifiedName;
        }

        @Override
        public void setFrozenContentBytes(byte[] bytes) {
            if (this.snapshotText == null) {
                this.snapshotText = new String(bytes, StandardCharsets.UTF_8);
            }
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var content = this.snapshotText;
            if (content == null) {
                return null;
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Clear snapshot on refresh so subsequent text() calls recompute from live content
            return new CodeFragment(id(), getContextManager(), fullyQualifiedName);
        }
    }

    class CallGraphFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public CallGraphFragment(
                String existingId,
                IContextManager contextManager,
                String methodName,
                int depth,
                boolean isCalleeGraph) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CALL_GRAPH;
        }

        public ComputedValue<String> text() {
            // Prefer frozen snapshot if available
            var snap = getSnapshotTextOrNull();
            if (snap != null) {
                return ComputedValue.completed("cgf-text-" + id(), snap);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "cgf-text-" + id(),
                            () -> {
                                var analyzer = getAnalyzer();
                                var methodCodeUnit =
                                        analyzer.getDefinition(methodName).filter(CodeUnit::isFunction);

                                if (methodCodeUnit.isEmpty()) {
                                    return "Method not found: " + methodName;
                                }

                                final Map<String, List<CallSite>> graphData = new HashMap<>();
                                final var maybeCallGraphProvider = analyzer.as(CallGraphProvider.class);

                                if (maybeCallGraphProvider.isPresent()) {
                                    var cpg = maybeCallGraphProvider.get();
                                    if (isCalleeGraph) {
                                        graphData.putAll(cpg.getCallgraphFrom(methodCodeUnit.get(), depth));
                                    } else {
                                        graphData.putAll(cpg.getCallgraphTo(methodCodeUnit.get(), depth));
                                    }
                                } else {
                                    return "Code intelligence is not ready. Cannot generate call graph for "
                                            + methodName + ".";
                                }

                                if (graphData.isEmpty()) {
                                    return "No call graph available for " + methodName;
                                }
                                return AnalyzerUtil.formatCallGraph(graphData, methodName, !isCalleeGraph);
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> new ComputedValue<>(
                            "cgf-sources-" + id(),
                            () -> getAnalyzer()
                                    .getDefinition(methodName)
                                    .map(Set::of)
                                    .orElse(Set.of()),
                            getFragmentExecutor()),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> this.sources()
                            .future()
                            .thenApply(set -> set.stream().map(CodeUnit::source).collect(Collectors.toSet()))
                            .thenApply(ComputedValue::completed)
                            .join(),
                    v -> filesCv = v);
        }

        @Override
        public String repr() {
            String direction = isCalleeGraph ? "OUT" : "IN";
            return "CallGraph('%s', depth=%d, direction=%s)".formatted(methodName, depth, direction);
        }

        @Override
        public ComputedValue<String> description() {
            String type = isCalleeGraph ? "Callees" : "Callers";
            return ComputedValue.completed(
                    "cgf-desc-" + id(), "%s of %s (depth %d)".formatted(type, methodName, depth));
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("cgf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_NONE);
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
        public byte @Nullable [] getFrozenContentBytes() {
            if (textCv == null) {
                return null;
            }
            var text = textCv.renderNowOrNull();
            if (text == null) {
                return null;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CallGraphFragment(id(), getContextManager(), methodName, depth, isCalleeGraph);
        }
    }

    enum SummaryType {
        CODEUNIT_SKELETON, // Summary for a single symbol
        FILE_SKELETONS // Summaries for all top-level declarations in a file
    }

    class SkeletonFragment extends VirtualFragment { // Dynamic composite wrapper around SummaryFragments
        private final List<SummaryFragment> summaries;
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private @Nullable ComputedValue<String> descCv;
        private @Nullable ComputedValue<String> formatCv;

        public SkeletonFragment(
                IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager); // Assigns dynamic numeric String ID
            this.summaries = targetIdentifiers.stream()
                    .map(target -> new SummaryFragment(contextManager, target, summaryType))
                    .toList();
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public SkeletonFragment(
                String existingId,
                IContextManager contextManager,
                List<String> targetIdentifiers,
                SummaryType summaryType) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !targetIdentifiers.isEmpty();
            this.summaries = targetIdentifiers.stream()
                    .map(target -> new SummaryFragment(contextManager, target, summaryType))
                    .toList();
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SKELETON;
        }

        public ComputedValue<String> text() {
            // Prefer frozen snapshot if available
            var snap = getSnapshotTextOrNull();
            if (snap != null) {
                return ComputedValue.completed("skf-text-" + id(), snap);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "skf-text-" + id(), () -> SummaryFragment.combinedText(summaries), getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> new ComputedValue<>(
                            "skf-sources-" + id(),
                            () -> summaries.stream()
                                    .flatMap(s -> s.sources().future().join().stream())
                                    .collect(Collectors.toSet()),
                            getFragmentExecutor()),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>(
                            "skf-files-" + id(),
                            () -> summaries.stream()
                                    .flatMap(s -> s.files().future().join().stream())
                                    .collect(Collectors.toSet()),
                            getFragmentExecutor()),
                    v -> filesCv = v);
        }

        @Override
        public String repr() {
            var targets = getTargetIdentifiers();
            var summaryType = getSummaryType();
            return switch (summaryType) {
                case CODEUNIT_SKELETON ->
                    "ClassSummaries([%s])"
                            .formatted(targets.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")));
                case FILE_SKELETONS ->
                    "FileSummaries([%s])"
                            .formatted(targets.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")));
            };
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> ComputedValue.completed(
                            "skf-desc-" + id(), "Summary of %s".formatted(String.join(", ", getTargetIdentifiers()))),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> format() {
            return lazyInitCv(
                    formatCv,
                    () -> formatCv,
                    () -> new ComputedValue<>(
                            "skf-format-" + id(),
                            () ->
                                    """
                                            <summary targets="%s" type="%s" fragmentid="%s">
                                            %s
                                            </summary>
                                            """
                                            .formatted(
                                                    String.join(", ", getTargetIdentifiers()),
                                                    getSummaryType().name(),
                                                    id(),
                                                    text().future().join()),
                            getFragmentExecutor()),
                    v -> formatCv = v);
        }

        public List<String> getTargetIdentifiers() {
            return summaries.stream().map(SummaryFragment::getTargetIdentifier).toList();
        }

        public SummaryType getSummaryType() {
            // All wrapped SummaryFragments have the same type; return the first one's
            return summaries.isEmpty()
                    ? SummaryType.CODEUNIT_SKELETON
                    : summaries.getFirst().getSummaryType();
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            // Skeletons are usually in the language of the summarized code.
            return ComputedValue.completed("skf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_JAVA);
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(this.description().renderNowOr(""));
        }

        @Override
        public ContextFragment refreshCopy() {
            return new SkeletonFragment(id(), getContextManager(), getTargetIdentifiers(), getSummaryType());
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            if (textCv == null) {
                return null;
            }
            var text = textCv.renderNowOrNull();
            if (text == null) {
                return null;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    class SummaryFragment extends VirtualFragment { // Dynamic, single-target, uses nextId
        private final String targetIdentifier;
        private final SummaryType summaryType;
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<Set<CodeUnit>> sourcesCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private @Nullable ComputedValue<String> descCv;

        public SummaryFragment(IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            super(contextManager);
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID might be numeric (dynamic) or hash (if frozen)
        public SummaryFragment(
                String existingId, IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            super(existingId, contextManager);
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
            this.primeComputations();
        }

        @Override
        public FragmentType getType() {
            // Keep semantics aligned with Skeleton for downstream consumers
            return FragmentType.SKELETON;
        }

        private Map<CodeUnit, String> fetchSkeletons() {
            IAnalyzer analyzer = getAnalyzer();
            Map<CodeUnit, String> skeletonsMap = new HashMap<>();
            analyzer.as(SkeletonProvider.class).ifPresent(skeletonProvider -> {
                switch (summaryType) {
                    case CODEUNIT_SKELETON -> {
                        analyzer.getDefinition(targetIdentifier).ifPresent(cu -> {
                            skeletonProvider.getSkeleton(cu).ifPresent(s -> skeletonsMap.put(cu, s));
                        });
                    }
                    case FILE_SKELETONS -> {
                        IContextManager cm = getContextManager();
                        ProjectFile projectFile = cm.toFile(targetIdentifier);
                        skeletonsMap.putAll(skeletonProvider.getSkeletons(projectFile));
                    }
                }
            });
            return skeletonsMap;
        }

        public ComputedValue<String> text() {
            // Prefer frozen snapshot if available
            var snap = getSnapshotTextOrNull();
            if (snap != null) {
                return ComputedValue.completed("sumf-text-" + id(), snap);
            }
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>(
                            "sumf-text-" + id(),
                            () -> {
                                Map<CodeUnit, String> skeletons = fetchSkeletons();
                                if (skeletons.isEmpty()) {
                                    return "No summary found for: " + targetIdentifier;
                                }
                                return combinedText(List.of(this));
                            },
                            getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<Set<CodeUnit>> sources() {
            return lazyInitCv(
                    sourcesCv,
                    () -> sourcesCv,
                    () -> new ComputedValue<>(
                            "sumf-src-" + id(), () -> fetchSkeletons().keySet(), getFragmentExecutor()),
                    v -> sourcesCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>(
                            "sumf-files-" + id(),
                            () -> switch (summaryType) {
                                case CODEUNIT_SKELETON ->
                                    this.sources().future().join().stream()
                                            .map(CodeUnit::source)
                                            .collect(Collectors.toSet());
                                case FILE_SKELETONS ->
                                    Set.of(getContextManager().toFile(targetIdentifier));
                            },
                            getFragmentExecutor()),
                    v -> filesCv = v);
        }

        @Override
        public String repr() {
            return switch (summaryType) {
                case CODEUNIT_SKELETON -> "ClassSummary('%s')".formatted(targetIdentifier);
                case FILE_SKELETONS -> "FileSummary('%s')".formatted(targetIdentifier);
            };
        }

        @Override
        public ComputedValue<String> description() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> ComputedValue.completed("sumf-desc-" + id(), "Summary of %s".formatted(targetIdentifier)),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("sumf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_JAVA);
        }

        public String getTargetIdentifier() {
            return targetIdentifier;
        }

        public List<String> getTargetIdentifiers() {
            return List.of(targetIdentifier);
        }

        public SummaryType getSummaryType() {
            return summaryType;
        }

        @Override
        public String toString() {
            return "SummaryFragment('%s')".formatted(this.description().renderNowOr(""));
        }

        @Override
        public ContextFragment refreshCopy() {
            return new SummaryFragment(id(), getContextManager(), targetIdentifier, summaryType);
        }

        public static String combinedText(Collection<SummaryFragment> fragments) {
            if (fragments.isEmpty()) {
                return "No summaries available";
            }

            // Collect all skeletons from all fragments
            Map<CodeUnit, String> allSkeletons = fragments.stream()
                    .flatMap(f -> f.fetchSkeletons().entrySet().stream())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (v1, v2) -> v1, // Keep first value if duplicates
                            LinkedHashMap::new));

            if (allSkeletons.isEmpty()) {
                return "No summaries available";
            }

            // Group by package, then format
            var skeletonsByPackage = allSkeletons.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().packageName().isEmpty()
                                    ? "(default package)"
                                    : e.getKey().packageName(),
                            Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)));

            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> {
                        String packageHeader = "package " + pkgEntry.getKey() + ";";
                        String pkgCode = String.join("\n\n", pkgEntry.getValue().values());
                        return packageHeader + "\n\n" + pkgCode;
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            if (textCv == null) {
                return null;
            }
            var text = textCv.renderNowOrNull();
            if (text == null) {
                return null;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    interface OutputFragment {
        List<TaskEntry> entries();

        /**
         * Should raw HTML inside markdown be escaped before rendering?
         */
        default boolean isEscapeHtml() {
            return true;
        }
    }

    /**
     * represents the entire Task History
     */
    class HistoryFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final List<TaskEntry> history; // Content is fixed once created

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            super(
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
                    contextManager);
            this.history = List.copyOf(history);
            this.primeComputations();
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public HistoryFragment(String existingHashId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.history = List.copyOf(history);
            this.primeComputations();
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
        public ComputedValue<String> text() {
            return new ComputedValue<>(
                    "hf-text-" + id(),
                    () -> TaskEntry.formatMessages(history.stream()
                            .flatMap(e -> e.isCompressed()
                                    ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                    : castNonNull(e.log()).messages().stream())
                            .toList()),
                    getFragmentExecutor());
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed("hf-files-" + id(), Set.of());
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed(
                    "hf-desc-" + id(),
                    "Task History (" + history.size() + " task%s)".formatted(history.size() > 1 ? "s" : ""));
        }

        @Override
        public ComputedValue<String> format() {
            return new ComputedValue<>(
                    "hf-format-" + id(),
                    () ->
                            """
                                    <taskhistory fragmentid="%s">
                                    %s
                                    </taskhistory>
                                    """
                                    .formatted(id(), text().future().join()),
                    getFragmentExecutor());
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("hf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var textCv = this.text();
            var text = textCv.renderNowOrNull();
            if (text == null) {
                return null;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * represents a single session's Task History
     */
    class TaskFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final List<ChatMessage> messages; // Content is fixed once created

        @SuppressWarnings({"unused", "UnusedVariable"})
        private final EditBlockParser parser;

        private final String description;
        private final boolean escapeHtml;

        private static String calculateId(String sessionName, List<ChatMessage> messages) {
            return FragmentUtils.calculateContentHash(
                    FragmentType.TASK, // Or SEARCH if SearchFragment calls this path
                    sessionName,
                    TaskEntry.formatMessages(messages),
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    TaskFragment.class
                            .getName() // Note: SearchFragment might want its own class name if it were hashing
                    // independently
                    );
        }

        public TaskFragment(
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            super(calculateId(description, messages), contextManager); // ID is content hash
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
            this.primeComputations();
        }

        public TaskFragment(
                IContextManager contextManager, List<ChatMessage> messages, String description, boolean escapeHtml) {
            this(contextManager, EditBlockParser.instance, messages, description, escapeHtml);
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(contextManager, EditBlockParser.instance, messages, description, true);
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
            this.primeComputations();
        }

        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description) {
            this(existingHashId, contextManager, parser, messages, description, true);
        }

        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, description, escapeHtml);
        }

        public TaskFragment(
                String existingHashId, IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, description, true);
        }

        @Override
        public boolean isEscapeHtml() {
            return escapeHtml;
        }

        @Override
        public FragmentType getType() {
            // SearchFragment overrides this to return FragmentType.SEARCH
            return FragmentType.TASK;
        }

        @Override
        public ComputedValue<String> description() {
            return ComputedValue.completed("tf-desc-" + id(), description);
        }

        @Override
        public ComputedValue<String> text() {
            return new ComputedValue<>(
                    "tf-text-" + id(), () -> TaskEntry.formatMessages(messages), getFragmentExecutor());
        }

        @Override
        public ComputedValue<String> syntaxStyle() {
            return ComputedValue.completed("tf-syntax-" + id(), SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }

        @Override
        public ComputedValue<Set<ProjectFile>> files() {
            return ComputedValue.completed("tf-files-" + id(), Set.of());
        }

        @Override
        public ContextFragment refreshCopy() {
            // Stable, hashed identity; copy can safely return this
            return this;
        }

        @Override
        public byte @Nullable [] getFrozenContentBytes() {
            var textCv = this.text();
            var text = textCv.renderNowOrNull();
            if (text == null) {
                return null;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }
}
