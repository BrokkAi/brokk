package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public interface Language {
    Logger logger = LogManager.getLogger(Language.class);

    List<String> getExtensions();

    String name(); // Human-friendly

    String internalName(); // Filesystem-safe

    IAnalyzer createAnalyzer(IProject project);

    /**
     * ACHTUNG! LoadAnalyzer can throw if the file on disk is corrupt or simply an obsolete format, so never call it
     * outside of try/catch with recovery!
     */
    IAnalyzer loadAnalyzer(IProject project);

    /**
     * Get the path where the storage for this analyzer in the given project should be stored.
     *
     * @param project The project.
     * @return The path to the database file.
     */
    default Path getStoragePath(IProject project) {
        // Use oldName for storage path to ensure stable and filesystem-safe names
        return project.getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(internalName().toLowerCase(Locale.ROOT) + ".bin");
    }

    /** Whether this language's analyzer provides compact symbol summaries. */
    boolean providesSummaries();

    /** Whether this language's analyzer can fetch or reconstruct source code for code units. */
    boolean providesSourceCode();

    /** Whether this language's analyzer supports interprocedural analysis such as call graphs across files. */
    boolean providesInterproceduralAnalysis();

    default boolean shouldDisableLsp() {
        var raw = System.getenv("BRK_NO_LSP");
        if (raw == null) return false;
        var value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return true;
        return switch (value) {
            case "1", "true", "t", "yes", "y", "on" -> true;
            case "0", "false", "f", "no", "n", "off" -> false;
            default -> {
                logger.warn("Environment variable BRK_NO_LSP='" + raw
                        + "' is not a recognized boolean; defaulting to disabling LSP.");
                yield true;
            }
        };
    }

    default List<Path> getDependencyCandidates(IProject project) {
        return List.of();
    }

    /**
     * Indicates the level of dependency import support: - NONE: no import support - BASIC: import supported but without
     * dependency kind classification - FINE_GRAINED: import supported with accurate dependency kinds classification
     */
    default ImportSupport getDependencyImportSupport() {
        return ImportSupport.NONE;
    }

    // --- Unified dependency discovery/import ---

    enum ImportSupport {
        NONE,
        BASIC,
        FINE_GRAINED
    }

    /**
     * High-level classification of a dependency for unified display. NORMAL/BUILD/DEV/TEST mirror Cargo kinds;
     * TRANSITIVE is a catch-all for non-direct deps; UNKNOWN is fallback.
     */
    enum DependencyKind {
        NORMAL,
        BUILD,
        DEV,
        TEST,
        TRANSITIVE,
        UNKNOWN
    }

    /**
     * A normalized dependency row for ImportLanguagePanel.
     *
     * @param displayName name shown in UI (e.g. "guava-31.1" or "requests 2.32.3" or "serde 1.0.210")
     * @param sourcePath key path used by the language importer (e.g. jar path, dist-info dir, Cargo.toml path)
     * @param kind logical dependency kind
     * @param filesCount rough number of relevant source files to import
     */
    record DependencyCandidate(String displayName, Path sourcePath, DependencyKind kind, long filesCount) {}

    /**
     * Discover dependency packages for this language to be shown in the unified panel. Defaults to empty list for
     * languages without an importer.
     */
    default List<DependencyCandidate> listDependencyPackages(IProject project) {
        return List.of();
    }

    /**
     * Perform the actual import for a selected dependency package. Implementations should handle threading, logging,
     * and user notifications.
     *
     * @return true if an import was started, false otherwise.
     */
    default boolean importDependency(
            Chrome chrome, DependencyCandidate pkg, @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
        return false;
    }

    /**
     * Checks if the given path is likely already analyzed as part of the project's primary sources. This is used to
     * warn the user if they try to import a directory that might be redundant. The path provided is expected to be
     * absolute.
     *
     * @param project The current project.
     * @param path The absolute path to check.
     * @return {@code true} if the path is considered part of the project's analyzed sources, {@code false} otherwise.
     */
    default boolean isAnalyzed(IProject project, Path path) {
        assert path.isAbsolute() : "Path must be absolute for isAnalyzed check: " + path;
        return path.normalize().startsWith(project.getRoot());
    }

    /**
     * A composite {@link Language} implementation that delegates all operations to the wrapped set of concrete
     * languages and combines the results.
     *
     * <p>Only the operations that make sense for a multi‑language view are implemented. Methods tied to a
     * single‐language identity ‑ such as {@link #internalName()} or {@link #getStoragePath(IProject)} ‑ throw
     * {@link UnsupportedOperationException}.
     */
    class MultiLanguage implements Language {
        private final Set<Language> languages;

        public MultiLanguage(Set<Language> languages) {
            Objects.requireNonNull(languages, "languages set is null");
            if (languages.isEmpty()) throw new IllegalArgumentException("languages set must not be empty");
            if (languages.stream().anyMatch(l -> l instanceof MultiLanguage))
                throw new IllegalArgumentException("cannot nest MultiLanguage inside itself");
            // copy defensively to guarantee immutability and deterministic ordering
            this.languages =
                    languages.stream().filter(l -> l != Languages.NONE).collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public List<String> getExtensions() {
            return languages.stream()
                    .flatMap(l -> l.getExtensions().stream())
                    .distinct()
                    .toList();
        }

        @Override
        public String name() {
            return languages.stream().map(Language::name).collect(Collectors.joining("/"));
        }

        @Override
        public String internalName() {
            throw new UnsupportedOperationException("MultiLanguage has no single internalName()");
        }

        @Override
        public Path getStoragePath(IProject project) {
            throw new UnsupportedOperationException("MultiLanguage has no single CPG file");
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            var delegates = new HashMap<Language, IAnalyzer>();
            for (var lang : languages) {
                var analyzer = lang.createAnalyzer(project);
                if (!analyzer.isEmpty()) delegates.put(lang, analyzer);
            }
            return delegates.size() == 1 ? delegates.values().iterator().next() : new MultiAnalyzer(delegates);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            var delegates = new HashMap<Language, IAnalyzer>();
            for (var lang : languages) {
                // TODO handle partial failure without needing to rebuild everything?
                var analyzer = lang.loadAnalyzer(project);
                if (!analyzer.isEmpty()) delegates.put(lang, analyzer);
            }
            return delegates.size() == 1 ? delegates.values().iterator().next() : new MultiAnalyzer(delegates);
        }

        @Override
        public boolean providesSummaries() {
            return languages.stream().anyMatch(Language::providesSummaries);
        }

        @Override
        public boolean providesSourceCode() {
            return languages.stream().anyMatch(Language::providesSourceCode);
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return languages.stream().anyMatch(Language::providesInterproceduralAnalysis);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            throw new UnsupportedOperationException(); // should only be called on single languages
        }

        @Override
        public ImportSupport getDependencyImportSupport() {
            throw new UnsupportedOperationException(); // should only be called on single languages
        }

        @Override
        public boolean isAnalyzed(IProject project, Path path) {
            return languages.stream().anyMatch(l -> l.isAnalyzed(project, path));
        }

        @Override
        public String toString() {
            return "MultiLanguage" + languages;
        }
    }
}
