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
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * ContextFragment methods do not throw checked exceptions, which make it difficult to use in Streams Instead, it throws
 * UncheckedIOException or CancellationException for IOException/InterruptedException, respectively; freeze() will throw
 * the checked variants at which point the caller should deal with the interruption or remove no-longer-valid Fragments
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

    static String describe(Stream<ContextFragment> fragments) {
        return fragments
                .map(ContextFragment::description)
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
                computeNThreads(), // core
                computeNThreads(), // max
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                daemonFactory);
        // Allow core threads to time out so the pool can shrink to zero when idle
        tpe.allowCoreThreadTimeOut(true);

        return new LoggingExecutorService(
                tpe, th -> logger.error("Uncaught exception in ContextFragment executor", th));
    }

    private static int computeNThreads() {
        int cpus = Runtime.getRuntime().availableProcessors();
        // Use at least 2 threads to avoid starvation on tiny runners; no cap required since
        // tasks are short-lived and the queue is unbounded.
        return Math.max(2, cpus);
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

    /** The type of this fragment. */
    FragmentType getType();

    /** short description in history */
    String shortDescription();

    /** longer description displayed in context table */
    String description();

    /** raw content for preview */
    String text() throws UncheckedIOException, CancellationException;

    /** content formatted for LLM */
    String format() throws UncheckedIOException, CancellationException;

    /** fragment toc entry, usually id + description */
    default String formatToc() {
        // ACHTUNG! if we ever start overriding this, we'll need to serialize it into FrozenFragment
        return """
                <fragment-toc description="%s" fragmentid="%s" />
                """
                .formatted(description(), id());
    }

    default boolean isText() {
        return true;
    }

    default Image image() throws UncheckedIOException {
        throw new UnsupportedOperationException();
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
    Set<CodeUnit> sources();

    /**
     * Returns all repo files referenced by this fragment. This is used when we *just* want to manipulate or show actual
     * files, rather than the code units themselves.
     */
    Set<ProjectFile> files();

    String syntaxStyle();

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
     *     available.
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
     * Non-breaking dynamic accessors for fragments that may compute values asynchronously.
     * Default adapters should provide completed values based on current state so legacy
     * call sites keep working without changes.
     */
    interface ComputedFragment extends ContextFragment {

        /**
         * Helper for thread-safe, lazy initialization of ComputedValue fields.
         * - Does not trigger computation during construction.
         * - Uses double-checked locking to avoid duplicate work.
         * - Caches the instance via setter on first initialization.
         *
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
         * Non-blocking accessor mirroring text().
         * Lazily produces a ComputedValue that computes text() on demand.
         */
        default ComputedValue<String> computedText() {
            return new ComputedValue<>("cf-text-" + id(), this::text, ContextFragment.getFragmentExecutor());
        }

        /**
         * Non-blocking accessor mirroring description().
         * Lazily produces a ComputedValue that computes description() on demand.
         */
        default ComputedValue<String> computedDescription() {
            return new ComputedValue<>(
                    "cf-description-" + id(), this::description, ContextFragment.getFragmentExecutor());
        }

        /**
         * Non-blocking accessor mirroring syntaxStyle().
         * Lazily produces a ComputedValue that computes syntaxStyle() on demand.
         */
        default ComputedValue<String> computedSyntaxStyle() {
            return new ComputedValue<>(
                    "cf-syntax-style-" + id(), this::syntaxStyle, ContextFragment.getFragmentExecutor());
        }

        /**
         * Non-blocking accessor mirroring files().
         * Lazily produces a ComputedValue that computes files() on demand.
         */
        default ComputedValue<Set<ProjectFile>> computedFiles() {
            return new ComputedValue<>("cf-files-" + id(), this::files, ContextFragment.getFragmentExecutor());
        }

        /**
         * Optionally provide computed image payload; default is null for non-image fragments.
         */
        default @Nullable ComputedValue<byte[]> computedImageBytes() {
            return null;
        }

        /**
         * Return a copy with cleared ComputedValues; identity (id) is preserved by default.
         * Implementations that track external state may override to trigger recomputation.
         */
        ContextFragment refreshCopy();

        /**
         * Bind this fragment's computed values to a Swing component, automatically managing subscriptions
         * and running UI updates on the EDT. Starts all relevant computed values (text, description, files)
         * and registers completion handlers that run uiUpdate on the EDT when any of them complete.
         * Subscriptions are automatically disposed when the owner component is removed from its parent.
         *
         * @param owner the Swing component that owns these subscriptions
         * @param uiUpdate a runnable to execute on the EDT when any computed value completes
         */
        default void bind(javax.swing.JComponent owner, java.lang.Runnable uiUpdate) {
            computedText().start();
            computedDescription().start();
            computedFiles().start();

            // Key for storing subscription list on the component
            final String CV_SUBS_KEY = "brokk.cv.subs";

            // Helper to register a subscription
            java.util.function.Consumer<ComputedValue.Subscription> registerSub = sub -> {
                @SuppressWarnings("unchecked")
                var existing = (java.util.List<ComputedValue.Subscription>) owner.getClientProperty(CV_SUBS_KEY);
                if (existing == null) {
                    existing = new java.util.ArrayList<>();
                    owner.putClientProperty(CV_SUBS_KEY, existing);
                }
                existing.add(sub);
            };

            // Helper to run UI update, coalesced onto EDT
            final boolean[] scheduled = {false};
            java.lang.Runnable scheduleUpdate = () -> {
                if (!scheduled[0]) {
                    scheduled[0] = true;
                    SwingUtilities.invokeLater(() -> {
                        scheduled[0] = false;
                        uiUpdate.run();
                    });
                }
            };

            // Subscribe to text completion
            var s1 = computedText().onComplete((v, ex) -> scheduleUpdate.run());
            registerSub.accept(s1);

            // Subscribe to description completion
            var s2 = computedDescription().onComplete((v, ex) -> scheduleUpdate.run());
            registerSub.accept(s2);

            // Subscribe to files completion
            var s3 = computedFiles().onComplete((v, ex) -> scheduleUpdate.run());
            registerSub.accept(s3);

            // Auto-dispose when owner is removed from parent
            owner.addAncestorListener(new javax.swing.event.AncestorListener() {
                private boolean disposed = false;

                @Override
                public void ancestorAdded(javax.swing.event.AncestorEvent e) {}

                @Override
                public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
                    if (!disposed) {
                        disposed = true;
                        @SuppressWarnings("unchecked")
                        var subs = (java.util.List<ComputedValue.Subscription>) owner.getClientProperty(CV_SUBS_KEY);
                        if (subs != null) {
                            for (var sub : subs) {
                                try {
                                    sub.dispose();
                                } catch (Exception ex) {
                                    // best-effort disposal
                                }
                            }
                            subs.clear();
                            owner.putClientProperty(CV_SUBS_KEY, null);
                        }
                        owner.removeAncestorListener(this);
                    }
                }

                @Override
                public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
            });
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
        @Override
        Image image() throws UncheckedIOException;

        /**
         * A stable, cached hash of the binary image content and relevant metadata.
         */
        String contentHash();
    }

    /**
     * Base class for dynamic virtual fragments. Uses numeric String IDs and supports async computation via
     * ComputedValue exposed by ComputedFragment.
     */
    abstract class ComputedVirtualFragment extends VirtualFragment implements ComputedFragment, DynamicIdentity {
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<String> descCv;
        private @Nullable ComputedValue<String> syntaxCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesCv;

        protected ComputedVirtualFragment(IContextManager contextManager) {
            super(contextManager);
        }

        protected ComputedVirtualFragment(String existingId, IContextManager contextManager) {
            super(existingId, contextManager);
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>("cvf-text-" + id(), this::text, getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> computedDescription() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>("cvf-desc-" + id(), this::description, getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("cvf-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> computedFiles() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>("cvf-files-" + id(), this::files, getFragmentExecutor()),
                    v -> filesCv = v);
        }
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
        default Set<ProjectFile> files() {
            BrokkFile bf = file();
            if (bf instanceof ProjectFile pf) {
                return Set.of(pf);
            }
            return Set.of();
        }

        @Override
        @Blocking
        default String text() throws UncheckedIOException {
            return file().read().orElse("");
        }

        @Override
        default String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
        default String format() {
            return """
                    <file path="%s" fragmentid="%s">
                    %s
                    </file>
                    """
                    .formatted(file().toString(), id(), text());
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

    final class ProjectPathFragment implements PathFragment, ComputedFragment {
        private final ProjectFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;

        // Primary constructor for new dynamic fragments
        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        private ProjectPathFragment(ProjectFile file, String id, IContextManager contextManager) {
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
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
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ProjectPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of(file);
        }

        @Override
        public String description() {
            if (file.getParent().equals(Path.of(""))) {
                return file.getFileName();
            }
            return "%s [%s]".formatted(file.getFileName(), file.getParent());
        }

        @Override
        public String repr() {
            return "File(['%s'])".formatted(file.toString());
        }

        @Override
        @Blocking
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer = getAnalyzer();
            return analyzer.getDeclarations(file);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(file);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public ContextFragment refreshCopy() {
            return ProjectPathFragment.withId(file, id, contextManager);
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>("ppf-text-" + id(), this::text, getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> computedDescription() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>("ppf-desc-" + id(), this::description, getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("ppf-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> computedFiles() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>("ppf-files-" + id(), this::files, getFragmentExecutor()),
                    v -> filesCv = v);
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
    }

    /** Represents a specific revision of a ProjectFile from Git history. This is non-dynamic. */
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
        public String shortDescription() {
            return "%s @%s".formatted(file().getFileName(), id);
        }

        @Override
        public String description() {
            var parentDir = file.getParent();
            return parentDir.equals(Path.of(""))
                    ? shortDescription()
                    : "%s [%s]".formatted(shortDescription(), parentDir);
        }

        @Override
        public Set<CodeUnit> sources() {
            // Treat historical content as potentially different from current; don't claim sources
            return Set.of();
        }

        @Override
        public String text() {
            return content;
        }

        @Override
        public String format() throws UncheckedIOException {
            // Note: fragmentid attribute is not typically added to GitFileFragment in this specific format,
            // but if it were, it should use %s for the ID.
            // Keeping existing format which doesn't include fragmentid.
            return """
                    <file path="%s" revision="%s">
                    %s
                    </file>
                    """
                    .formatted(file().toString(), revision(), text());
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
    }

    final class ExternalPathFragment implements PathFragment, ComputedFragment {
        private final ExternalFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;

        // Primary constructor for new dynamic fragments
        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        private ExternalPathFragment(ExternalFile file, String id, IContextManager contextManager) {
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
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
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ExternalPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return description();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public ContextFragment refreshCopy() {
            return ExternalPathFragment.withId(file, id, contextManager);
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>("epf-text-" + id(), this::text, getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> computedDescription() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>("epf-desc-" + id(), this::description, getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("epf-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> computedFiles() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>("epf-files-" + id(), this::files, getFragmentExecutor()),
                    v -> filesCv = v);
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
    }

    /** Represents an image file, either from the project or external. This is dynamic. */
    final class ImageFileFragment implements PathFragment, ImageFragment, ComputedFragment {
        private final BrokkFile file;
        private final String id;
        private final IContextManager contextManager;
        private transient @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<String> descCv;
        private transient @Nullable ComputedValue<String> syntaxCv;
        private transient @Nullable ComputedValue<Set<ProjectFile>> filesCv;
        private transient @Nullable ComputedValue<byte[]> imageBytesCv;

        // Primary constructor for new dynamic fragments
        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        private ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            this.file = file;
            this.id = id;
            this.contextManager = contextManager;
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
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public String description() {
            if (file instanceof ProjectFile pf && !pf.getParent().equals(Path.of(""))) {
                return "%s [%s]".formatted(file.getFileName(), pf.getParent());
            }
            return file.toString(); // For ExternalFile or root ProjectFile
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        @Blocking
        public Image image() throws UncheckedIOException {
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
                    // This can happen for unsupported formats, corrupted files, or non-image files
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
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files() {
            return (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of();
        }

        @Override
        public String format() {
            // Format for LLM, indicating image content (similar to PasteImageFragment)
            return """
                    <file path="%s" fragmentid="%s">
                    [Image content provided out of band]
                    </file>
                    """
                    .formatted(file().toString(), id());
        }

        @Override
        public ContextFragment refreshCopy() {
            return ImageFileFragment.withId(file, id, contextManager);
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv,
                    () -> textCv,
                    () -> new ComputedValue<>("iff-text-" + id(), this::text, getFragmentExecutor()),
                    v -> textCv = v);
        }

        @Override
        public ComputedValue<String> computedDescription() {
            return lazyInitCv(
                    descCv,
                    () -> descCv,
                    () -> new ComputedValue<>("iff-desc-" + id(), this::description, getFragmentExecutor()),
                    v -> descCv = v);
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("iff-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> computedFiles() {
            return lazyInitCv(
                    filesCv,
                    () -> filesCv,
                    () -> new ComputedValue<>("iff-files-" + id(), this::files, getFragmentExecutor()),
                    v -> filesCv = v);
        }

        @Override
        public @Nullable ComputedValue<byte[]> computedImageBytes() {
            return lazyInitCv(
                    imageBytesCv,
                    () -> imageBytesCv,
                    () -> new ComputedValue<>(
                            "iff-image-" + id(),
                            () -> {
                                try {
                                    return ImageUtil.imageToBytes(image());
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
        protected final String id; // Changed from int to String
        protected final transient IContextManager contextManager;

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
        public String format() {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            return description();
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            return parseProjectFiles(text(), contextManager.getProject());
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public abstract String text();

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
            // No need to call ContextFragment.setNextId() as hash IDs are not numeric.
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String syntaxStyle() {
            return syntaxStyle;
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

            // Special case: if both descriptions match the same StringFragmentTypes entry,
            // they have the same source regardless of text or syntax style.
            // This allows hardcoded system fragments (like BUILD_RESULTS, SEARCH_NOTES, etc.)
            // to be recognized as equivalent even if their content differs.
            StringFragmentType thisType = getStringFragmentType(this.description);
            StringFragmentType thatType = getStringFragmentType(that.description);

            if (thisType != null && thatType != null) {
                // Both descriptions map to StringFragmentTypes entries
                return Objects.equals(thisType, thatType);
            }

            // Default behavior: compare text and syntax style for non-system fragments
            return description.equals(that.description) && syntaxStyle.equals(that.syntaxStyle);
        }

        // Use identity-based equals (inherited from VirtualFragment)
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
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SEARCH;
        }

        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public Set<ProjectFile> files() {
            // SearchFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        // Use identity-based equals (inherited from VirtualFragment via TaskFragment)
    }

    abstract class PasteFragment extends ContextFragment.VirtualFragment implements ComputedFragment {
        protected transient Future<String> descriptionFuture;
        private @Nullable ComputedValue<String> descriptionCv;
        private @Nullable ComputedValue<String> syntaxCv;
        private @Nullable ComputedValue<Set<ProjectFile>> filesFuture;

        // PasteFragments are non-dynamic (content-hashed)
        // The hash will be based on the initial text/image data, not the future description.
        // Lazily initializes computed values on first access to avoid any background work during construction.
        public PasteFragment(String id, IContextManager contextManager, Future<String> descriptionFuture) {
            super(id, contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        @Blocking
        public String description() {
            return computedDescription().future().join();
        }

        @Override
        public ComputedValue<String> computedDescription() {
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
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("paste-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<Set<ProjectFile>> computedFiles() {
            return lazyInitCv(
                    filesFuture,
                    () -> filesFuture,
                    () -> new ComputedValue<>("paste-files-" + id(), this::files, getFragmentExecutor()),
                    v -> filesFuture = v);
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
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

        // Use identity-based equals (inherited from VirtualFragment)
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
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        public String syntaxStyle() {
            if (syntaxStyleFuture.isDone()) {
                try {
                    return syntaxStyleFuture.get();
                } catch (Exception e) {
                    return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
                }
            }
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            return lazyInitCv(
                    syntaxCv,
                    () -> syntaxCv,
                    () -> new ComputedValue<>("ptf-syntax-" + id(), this::syntaxStyle, getFragmentExecutor()),
                    v -> syntaxCv = v);
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv, () -> textCv, () -> ComputedValue.completed("ptf-text-" + id(), text), v -> textCv = v);
        }

        @Override
        public String text() {
            return text;
        }

        public Future<String> getSyntaxStyleFuture() {
            return syntaxStyleFuture;
        }

        @Override
        public String shortDescription() {
            return "pasted text";
        }

        @Override
        public boolean hasSameSource(ContextFragment other) {
            if (this == other) return true;
            if (!(other instanceof PasteTextFragment that)) {
                return false;
            }
            return text.equals(that.text);
        }
    }

    class AnonymousImageFragment extends PasteFragment implements ImageFragment { // Non-dynamic, content-hashed
        private final Image image;
        private @Nullable ComputedValue<String> textCv;
        private transient @Nullable ComputedValue<byte[]> imageBytesCv;

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
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public AnonymousImageFragment(
                String existingHashId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            this.image = image;
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
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        public ComputedValue<String> computedText() {
            return lazyInitCv(
                    textCv, () -> textCv, () -> ComputedValue.completed("aif-text-" + id(), text()), v -> textCv = v);
        }

        @Override
        public @Nullable ComputedValue<byte[]> computedImageBytes() {
            return lazyInitCv(
                    imageBytesCv,
                    () -> imageBytesCv,
                    () -> new ComputedValue<>("aif-image-" + id(), this::imageBytes, getFragmentExecutor()),
                    v -> imageBytesCv = v);
        }

        @Override
        public Image image() {
            return image;
        }

        @Override
        public String contentHash() {
            return id();
        }

        @Nullable
        @Blocking
        public byte[] imageBytes() {
            return imageToBytes(image);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }

        @Override
        public String format() {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .formatted(description(), id(), text());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public String shortDescription() {
            return "pasted image";
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
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STACKTRACE;
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public Set<ProjectFile> files() {
            // StacktraceFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public String syntaxStyle() {
            if (sources.isEmpty()) {
                return SyntaxConstants.SYNTAX_STYLE_NONE;
            }
            var firstClass = sources.iterator().next();
            return firstClass.source().getSyntaxStyle();
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

        // Use identity-based equals (inherited from VirtualFragment)
    }

    class UsageFragment extends ComputedVirtualFragment { // Dynamic, uses nextId
        private final String targetIdentifier;
        private final boolean includeTestFiles;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            this(contextManager, targetIdentifier, true);
        }

        public UsageFragment(IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public UsageFragment(String existingId, IContextManager contextManager, String targetIdentifier) {
            this(existingId, contextManager, targetIdentifier, true);
        }

        public UsageFragment(
                String existingId, IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        @Override
        @Blocking
        public String text() {
            var analyzer = getAnalyzer();
            if (analyzer.isEmpty()) {
                return "Code Intelligence cannot extract source for: " + targetIdentifier + ".";
            }
            FuzzyResult usageResult = FuzzyUsageFinder.create(contextManager).findUsages(targetIdentifier);

            var either = usageResult.toEither();
            if (either.hasErrorMessage()) {
                return either.getErrorMessage();
            }

            List<CodeWithSource> parts = processUsages(analyzer, either);
            var formatted = CodeWithSource.text(parts);
            return formatted.isEmpty() ? "No relevant usages found for symbol: " + targetIdentifier : formatted;
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
        @Blocking
        public Set<CodeUnit> sources() {
            if (SwingUtilities.isEventDispatchThread()) {
                logger.warn("Calling blocking UsageFragments.sources on EDT thread!");
            }
            var analyzer = getAnalyzer();
            if (analyzer.isEmpty()) {
                return Collections.emptySet();
            }
            FuzzyResult usageResult = FuzzyUsageFinder.create(contextManager).findUsages(targetIdentifier);

            var either = usageResult.toEither();
            if (either.hasErrorMessage()) {
                return Collections.emptySet();
            }

            List<CodeWithSource> parts = processUsages(analyzer, either);
            return parts.stream().map(AnalyzerUtil.CodeWithSource::source).collect(Collectors.toSet());
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            final var allSources = sources().stream().map(CodeUnit::source);
            if (!includeTestFiles) {
                return allSources
                        .filter(source -> !ContextManager.isTestFile(source))
                        .collect(Collectors.toSet());
            } else {
                return allSources.collect(Collectors.toSet());
            }
        }

        @Override
        public String repr() {
            return "SymbolUsages('%s', includeTestFiles=%s)".formatted(targetIdentifier, includeTestFiles);
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return sources().stream()
                    .findFirst()
                    .map(s -> s.source().getSyntaxStyle())
                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE);
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }

        public boolean includeTestFiles() {
            return includeTestFiles;
        }

        @Override
        public ContextFragment refreshCopy() {
            return new UsageFragment(id(), getContextManager(), targetIdentifier, includeTestFiles);
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }

    /** Dynamic fragment that wraps a single CodeUnit and renders the full source */
    class CodeFragment extends ComputedVirtualFragment { // Dynamic, uses nextId
        private final String fullyQualifiedName;
        private @Nullable ComputedValue<CodeUnit> unitCv;
        private @Nullable CodeUnit preResolvedUnit;

        public CodeFragment(IContextManager contextManager, String fullyQualifiedName) {
            super(contextManager);
            assert !fullyQualifiedName.isBlank();
            this.fullyQualifiedName = fullyQualifiedName;
        }

        public CodeFragment(String existingId, IContextManager contextManager, String fullyQualifiedName) {
            super(existingId, contextManager);
            assert !fullyQualifiedName.isBlank();
            this.fullyQualifiedName = fullyQualifiedName;
        }

        /**
         * A convenience constructor for if we already have our code unit, to avoid unnecessary re-computation.
         */
        public CodeFragment(IContextManager contextManager, CodeUnit unit) {
            super(contextManager);
            validateCodeUnit(unit);
            this.fullyQualifiedName = unit.fqName();
            this.preResolvedUnit = unit;
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

        private ComputedValue<CodeUnit> getComputedUnit() {
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
        public String description() {
            return "Source for " + fullyQualifiedName;
        }

        @Override
        public String shortDescription() {
            return fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.') + 1);
        }

        @Override
        @Blocking
        public String text() {
            var analyzer = getAnalyzer();
            var unit = getComputedUnit().future().join(); // block on future

            var maybeSourceCodeProvider = analyzer.as(SourceCodeProvider.class);
            if (maybeSourceCodeProvider.isEmpty()) {
                return "Code Intelligence cannot extract source for: " + fullyQualifiedName;
            }
            var scp = maybeSourceCodeProvider.get();

            if (unit.isFunction()) {
                var code = scp.getMethodSource(unit, true).orElse("");
                if (!code.isEmpty()) {
                    return new AnalyzerUtil.CodeWithSource(code, unit).text();
                }
                return "No source found for method: " + fullyQualifiedName;
            } else {
                var code = scp.getClassSource(unit, true).orElse("");
                if (!code.isEmpty()) {
                    return new AnalyzerUtil.CodeWithSource(code, unit).text();
                }
                return "No source found for class: " + fullyQualifiedName;
            }
        }

        @Override
        @Blocking
        public Set<CodeUnit> sources() {
            var unit = getComputedUnit().future().join();
            return Set.of(unit);
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String repr() {
            return "Method(['%s'])".formatted(fullyQualifiedName);
        }

        @Override
        @Blocking
        public String syntaxStyle() {
            var unit = getComputedUnit().future().join();
            return unit.source().getSyntaxStyle();
        }

        public String getFullyQualifiedName() {
            return fullyQualifiedName;
        }

        public ComputedValue<CodeUnit> computedUnit() {
            return getComputedUnit();
        }

        @Override
        public ContextFragment refreshCopy() {
            return new CodeFragment(id(), getContextManager(), fullyQualifiedName);
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }

    class CallGraphFragment extends ComputedVirtualFragment { // Dynamic, uses nextId
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
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
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CALL_GRAPH;
        }

        @Override
        @Blocking
        public String text() {
            var analyzer = getAnalyzer();
            var methodCodeUnit = analyzer.getDefinition(methodName).filter(CodeUnit::isFunction);

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
                return "Code intelligence is not ready. Cannot generate call graph for " + methodName + ".";
            }

            if (graphData.isEmpty()) {
                return "No call graph available for " + methodName;
            }
            return AnalyzerUtil.formatCallGraph(graphData, methodName, !isCalleeGraph);
        }

        @Override
        @Blocking
        public Set<CodeUnit> sources() {
            // FIXME this is broken, needs to include the actual call sites as well
            IAnalyzer analyzer = getAnalyzer();
            return analyzer.getDefinition(methodName).map(Set::of).orElse(Set.of());
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String repr() {
            String direction = isCalleeGraph ? "OUT" : "IN";
            return "CallGraph('%s', depth=%d, direction=%s)".formatted(methodName, depth, direction);
        }

        @Override
        public String description() {
            String type = isCalleeGraph ? "Callees" : "Callers";
            return "%s of %s (depth %d)".formatted(type, methodName, depth);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE; // Call graph is textual, not specific code language
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
        public ContextFragment refreshCopy() {
            return new CallGraphFragment(id(), getContextManager(), methodName, depth, isCalleeGraph);
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }

    enum SummaryType {
        CODEUNIT_SKELETON, // Summary for a single symbol
        FILE_SKELETONS // Summaries for all top-level declarations in a file
    }

    class SkeletonFragment extends ComputedVirtualFragment { // Dynamic composite wrapper around SummaryFragments
        private final List<SummaryFragment> summaries;

        public SkeletonFragment(
                IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager); // Assigns dynamic numeric String ID
            this.summaries = targetIdentifiers.stream()
                    .map(target -> new SummaryFragment(contextManager, target, summaryType))
                    .toList();
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
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SKELETON;
        }

        @Override
        @Blocking
        public String text() {
            return SummaryFragment.combinedText(summaries);
        }

        @Override
        @Blocking
        public Set<CodeUnit> sources() {
            return summaries.stream().flatMap(s -> s.sources().stream()).collect(Collectors.toSet());
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            return summaries.stream().flatMap(s -> s.files().stream()).collect(Collectors.toSet());
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
        public String description() {
            var targets = getTargetIdentifiers();
            return "Summary of %s".formatted(String.join(", ", targets));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            var targets = getTargetIdentifiers();
            var summaryType = getSummaryType();
            return """
                    <summary targets="%s" type="%s" fragmentid="%s">
                    %s
                    </summary>
                    """
                    .formatted(String.join(", ", targets), summaryType.name(), id(), text());
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
        public String syntaxStyle() {
            // Skeletons are usually in the language of the summarized code.
            // Default to Java or try to infer from a source CodeUnit if available.
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new SkeletonFragment(id(), getContextManager(), getTargetIdentifiers(), getSummaryType());
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }

    class SummaryFragment extends ComputedVirtualFragment { // Dynamic, single-target, uses nextId
        private final String targetIdentifier;
        private final SummaryType summaryType;

        public SummaryFragment(IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            super(contextManager);
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
        }

        // Constructor for DTOs/unfreezing where ID might be numeric (dynamic) or hash (if frozen)
        public SummaryFragment(
                String existingId, IContextManager contextManager, String targetIdentifier, SummaryType summaryType) {
            super(existingId, contextManager);
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.summaryType = summaryType;
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

        @Override
        @Blocking
        public String text() {
            Map<CodeUnit, String> skeletons = fetchSkeletons();
            if (skeletons.isEmpty()) {
                return "No summary found for: " + targetIdentifier;
            }
            return combinedText(List.of(this));
        }

        @Override
        @Blocking
        public Set<CodeUnit> sources() {
            return fetchSkeletons().keySet();
        }

        @Override
        @Blocking
        public Set<ProjectFile> files() {
            return switch (summaryType) {
                case CODEUNIT_SKELETON ->
                    sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
                case FILE_SKELETONS -> Set.of(contextManager.toFile(targetIdentifier));
            };
        }

        @Override
        public String repr() {
            return switch (summaryType) {
                case CODEUNIT_SKELETON -> "ClassSummary('%s')".formatted(targetIdentifier);
                case FILE_SKELETONS -> "FileSummary('%s')".formatted(targetIdentifier);
            };
        }

        @Override
        public String description() {
            return "Summary of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
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
            return "SummaryFragment('%s')".formatted(description());
        }

        @Override
        public ContextFragment refreshCopy() {
            return new SummaryFragment(id(), getContextManager(), targetIdentifier, summaryType);
        }

        // Use identity-based equals (inherited from VirtualFragment)

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
    }

    interface OutputFragment {
        List<TaskEntry> entries();

        /** Should raw HTML inside markdown be escaped before rendering? */
        default boolean isEscapeHtml() {
            return true;
        }
    }

    /** represents the entire Task History */
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
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public HistoryFragment(String existingHashId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
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
        public String text() {
            // FIXME the right thing to do here is probably to throw UncheckedIOException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(history.stream()
                    .flatMap(e -> e.isCompressed()
                            ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                            : castNonNull(e.log()).messages().stream())
                    .toList());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }

        @Override
        public String description() {
            return "Task History (" + history.size() + " task%s)".formatted(history.size() > 1 ? "s" : "");
        }

        @Override
        public String format() {
            return """
                    <taskhistory fragmentid="%s">
                    %s
                    </taskhistory>
                    """
                    .formatted(id(), text()); // Analyzer not used by its text()
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }

    /** represents a single session's Task History */
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
            // don't break sessions saved like this
            // assert !messages.isEmpty() : "No messages provided in the task fragment";
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
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
            // don't break sessions saved like this
            // assert !messages.isEmpty() : "No messages provided in the task fragment";
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
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
        public String description() {
            return description;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(messages);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }

        // Use identity-based equals (inherited from VirtualFragment)
    }
}
