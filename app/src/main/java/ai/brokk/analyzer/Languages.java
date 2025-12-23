package ai.brokk.analyzer;

import ai.brokk.analyzer.scala.ScalaLanguage;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class Languages {
    public static final Language C_SHARP = new Language() {
        private final Set<String> extensions = Set.of("cs");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "C#";
        }

        @Override
        public String internalName() {
            return "C_SHARP";
        }

        @Override
        public String toString() {
            return name();
        } // For compatibility

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new CSharpAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = CSharpAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }
    };
    public static final Language JAVA = new JavaLanguage();
    public static final Language JAVASCRIPT = new Language() {
        private final Set<String> extensions = Set.of("js", "mjs", "cjs", "jsx");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "JavaScript";
        }

        @Override
        public String internalName() {
            return "JAVASCRIPT";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new JavascriptAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = JavascriptAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return NodeJsDependencyHelper.getDependencyCandidates(project);
        }

        @Override
        public List<Language.DependencyCandidate> listDependencyPackages(IProject project) {
            return NodeJsDependencyHelper.listDependencyPackages(project);
        }

        @Override
        public boolean importDependency(
                Chrome chrome,
                Language.DependencyCandidate pkg,
                @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
            return NodeJsDependencyHelper.importDependency(chrome, pkg, lifecycle);
        }

        @Override
        public Language.ImportSupport getDependencyImportSupport() {
            return Language.ImportSupport.FINE_GRAINED;
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };
    public static final Language PYTHON = new PythonLanguage();
    public static final Language C_CPP = new Language() {
        private final Set<String> extensions = Set.of("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "C/C++";
        }

        @Override
        public String internalName() {
            return "C_CPP";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new CppAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> CppAnalyzer.fromState(project, state, listener))
                    .orElseGet(() -> (CppAnalyzer) createAnalyzer(project, listener));
        }
    };
    public static final Language GO = new Language() {
        private final Set<String> extensions = Set.of("go");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "Go";
        }

        @Override
        public String internalName() {
            return "GO";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new GoAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = GoAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }
    };
    public static final Language CPP_TREESITTER = new Language() {
        private final Set<String> extensions = Set.of("c", "cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "h++", "h");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "C++ (TreeSitter)";
        }

        @Override
        public String internalName() {
            return "CPP_TREESITTER";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new CppAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = CppAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }
    };
    public static final Language RUST = new RustLanguage();
    public static final Language NONE = new Language() {
        private final Set<String> extensions = Collections.emptySet();

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "None";
        }

        @Override
        public String internalName() {
            return "NONE";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new DisabledAnalyzer(project);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return createAnalyzer(project, listener);
        }
    };
    public static final Language PHP = new Language() {
        private final Set<String> extensions = Set.of("php", "phtml", "php3", "php4", "php5", "phps");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "PHP";
        }

        @Override
        public String internalName() {
            return "PHP";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new PhpAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = PhpAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        // TODO: Refine isAnalyzed for PHP (e.g. vendor directory)
        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
            Path projectRoot = project.getRoot();
            Path normalizedPathToImport = pathToImport.normalize();

            if (!normalizedPathToImport.startsWith(projectRoot)) {
                return false; // Not part of this project
            }
            // Example: exclude vendor directory
            Path vendorDir = projectRoot.resolve("vendor");
            return !normalizedPathToImport.startsWith(
                    vendorDir); // Default: if under project root and not in typical build/dependency dirs
        }
    };
    public static final Language SQL = new Language() {
        private final Set<String> extensions = Set.of("sql");

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "SQL";
        }

        @Override
        public String internalName() {
            return "SQL";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new SqlAnalyzer(project);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            // SQLAnalyzer does not save/load state from disk beyond re-parsing
            return createAnalyzer(project, listener);
        }
    };
    public static final Language TYPESCRIPT = new Language() {
        private final Set<String> extensions =
                Set.of("ts", "tsx"); // Including tsx for now, can be split later if needed

        @Override
        public Set<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "Typescript";
        }

        @Override
        public String internalName() {
            return "TYPESCRIPT";
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new TypescriptAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = TypescriptAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return NodeJsDependencyHelper.getDependencyCandidates(project);
        }

        @Override
        public List<Language.DependencyCandidate> listDependencyPackages(IProject project) {
            return NodeJsDependencyHelper.listDependencyPackages(project);
        }

        @Override
        public boolean importDependency(
                Chrome chrome,
                Language.DependencyCandidate pkg,
                @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
            return NodeJsDependencyHelper.importDependency(chrome, pkg, lifecycle);
        }

        @Override
        public Language.ImportSupport getDependencyImportSupport() {
            return Language.ImportSupport.FINE_GRAINED;
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };

    public static final Language SCALA = new ScalaLanguage();

    public static final List<Language> ALL_LANGUAGES = List.of(
            C_SHARP,
            JAVA,
            JAVASCRIPT,
            PYTHON,
            C_CPP,
            CPP_TREESITTER,
            GO,
            RUST,
            PHP,
            TYPESCRIPT, // Now TYPESCRIPT is declared before this list
            SCALA,
            SQL, // SQL is now defined and can be included
            NONE);

    /**
     * Returns the Language constant corresponding to the given file extension. Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    public static Language fromExtension(String extension) {
        if (extension.isEmpty()) {
            return NONE;
        }
        String lowerExt = extension.toLowerCase(Locale.ROOT);
        // Ensure the extension does not start with a dot for consistent matching.
        String normalizedExt = lowerExt.startsWith(".") ? lowerExt.substring(1) : lowerExt;

        for (Language lang : ALL_LANGUAGES) {
            for (String langExt : lang.getExtensions()) {
                if (langExt.equals(normalizedExt)) {
                    return lang;
                }
            }
        }
        return NONE;
    }

    /**
     * Returns an array containing all the defined Language constants, in the order they are declared. This method is
     * provided for compatibility with Enum.values().
     *
     * @return an array containing all the defined Language constants.
     */
    public static Language[] values() {
        return ALL_LANGUAGES.toArray(new Language[0]);
    }

    /**
     * Returns the Language constant with the specified name. The string must match exactly an identifier used to
     * declare a Language constant. (Extraneous whitespace characters are not permitted.) This method is provided for
     * compatibility with Enum.valueOf(String).
     *
     * @param name the name of the Language constant to be returned.
     * @return the Language constant with the specified name.
     * @throws IllegalArgumentException if this language type has no constant with the specified name.
     * @throws NullPointerException if name is null.
     */
    public static Language valueOf(String name) {
        for (Language lang : ALL_LANGUAGES) {
            // Check current human-friendly name first, then old programmatic name for backward compatibility.
            if (lang.name().equals(name) || lang.internalName().equals(name)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("No language constant " + Language.class.getCanonicalName() + "." + name);
    }
}
