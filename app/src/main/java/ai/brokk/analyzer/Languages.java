package ai.brokk.analyzer;

import ai.brokk.project.ICoreProject;
import ai.brokk.analyzer.scala.ScalaLanguage;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Blocking;
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new CSharpAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = CSharpAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public Set<String> getSearchPatterns(CodeUnitType type) {
            if (type == CodeUnitType.FUNCTION) {
                return Set.of(
                        "\\b$ident\\s*\\(", // method calls
                        "\\.$ident\\s*\\(" // method calls
                        );
            } else if (type == CodeUnitType.CLASS) {
                return Set.of(
                        "\\bnew\\s+$ident(?:<.+?>)?\\s*\\(", // constructor calls with optional generics
                        "\\bclass\\s+\\w+\\s*:\\s*$ident(?:<.+?>)?", // inheritance with optional generics
                        "\\b$ident(?:<.+?>)?\\s+\\w+\\s*[;=]", // variable declarations with optional generics
                        "<\\s*$ident\\s*>", // as generic type argument
                        "\\b$ident\\s*\\.", // static access
                        "\\busing\\s+.*\\.$ident\\b" // using directives
                        );
            }
            return Language.super.getSearchPatterns(type);
        }
    };
    public static final Language JAVA = new JavaLanguage();
    public static final Language JAVASCRIPT = new DependencyImportable() {
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new JavascriptAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = JavascriptAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public Set<String> getSearchPatterns(CodeUnitType type) {
            if (type == CodeUnitType.FUNCTION) {
                return Set.of(
                        "\\b$ident\\s*\\(", // function calls
                        "\\.$ident\\s*\\(" // method calls
                        );
            } else if (type == CodeUnitType.CLASS) {
                return Set.of(
                        "\\bnew\\s+$ident\\s*\\(", // constructor calls
                        "\\bclass\\s+\\w+\\s+extends\\s+$ident\\b", // class extends
                        "\\b$ident\\s*\\.", // static access
                        "\\bimport\\s+.*$ident", // import statements
                        "\\bfrom\\s+.*\\{.*$ident.*\\}" // named imports
                        );
            }
            return DependencyImportable.super.getSearchPatterns(type);
        }

        @Override
        public List<Path> getDependencyCandidates(ICoreProject project) {
            return NodeJsDependencyHelper.getDependencyCandidates(project);
        }

        @Override
        public List<Language.DependencyCandidate> listDependencyPackages(ICoreProject project) {
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
        public boolean isAnalyzed(ICoreProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };
    public static final Language PYTHON = new PythonLanguage();
    public static final Language C_CPP = new CppLanguage();
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new GoAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = GoAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public Set<String> getSearchPatterns(CodeUnitType type) {
            if (type == CodeUnitType.FUNCTION) {
                return Set.of(
                        "\\b$ident\\s*\\(", // function calls
                        "\\.$ident\\s*\\(" // method calls
                        );
            } else if (type == CodeUnitType.CLASS) {
                return Set.of(
                        "\\b$ident\\s*\\{", // struct initialization
                        "\\b$ident\\{", // compact struct init
                        "\\btype\\s+$ident\\s+struct", // struct definition
                        "\\*$ident", // pointer types
                        "\\bvar\\s+\\w+\\s+\\*?$ident\\b", // variable declarations
                        "\\[\\]\\*?$ident\\b", // slice types
                        "\\[\\d+\\]\\*?$ident\\b", // array types
                        "map\\[.+?\\]\\*?$ident\\b", // map value types
                        "\\)\\s+\\*?$ident\\b", // return types
                        "\\.\\(\\*?$ident\\)", // type assertions
                        "\\bfunc\\s+\\(\\w+\\s+\\*?$ident\\)", // method receiver
                        "\\bimport\\s+.*\".*/$ident\"" // import statements
                        );
            }
            return Language.super.getSearchPatterns(type);
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new DisabledAnalyzer(project);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new PhpAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = PhpAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public Set<String> getSearchPatterns(CodeUnitType type) {
            if (type == CodeUnitType.FUNCTION) {
                return Set.of(
                        "\\b$ident\\s*\\(", // function calls
                        "->\\s*$ident\\s*\\(" // method calls
                        );
            } else if (type == CodeUnitType.CLASS) {
                return Set.of(
                        "\\bnew\\s+$ident\\s*\\(", // constructor calls
                        "\\bclass\\s+\\w+\\s+extends\\s+$ident\\b", // inheritance
                        "\\bclass\\s+\\w+\\s+implements\\s+$ident\\b", // interface implementation
                        "\\b$ident\\s+\\$\\w+", // type hints
                        ":\\s*$ident\\b", // return type hints
                        "\\buse\\s+.*\\\\$ident\\b", // use statements
                        "$ident::" // static access
                        );
            }
            return Language.super.getSearchPatterns(type);
        }

        // TODO: Refine isAnalyzed for PHP (e.g. vendor directory)
        @Override
        public boolean isAnalyzed(ICoreProject project, Path pathToImport) {
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new SqlAnalyzer(project);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            // SQLAnalyzer does not save/load state from disk beyond re-parsing
            return createAnalyzer(project, listener);
        }
    };
    public static final Language TYPESCRIPT = new DependencyImportable() {
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
        public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            return new TypescriptAnalyzer(project, listener);
        }

        @Override
        public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
            var storage = getStoragePath(project);
            return TreeSitterStateIO.load(storage)
                    .map(state -> {
                        var analyzer = TypescriptAnalyzer.fromState(project, state, listener);
                        return (IAnalyzer) analyzer;
                    })
                    .orElseGet(() -> createAnalyzer(project, listener));
        }

        @Override
        public Set<String> getSearchPatterns(CodeUnitType type) {
            if (type == CodeUnitType.FUNCTION) {
                return Set.of(
                        "\\b$ident\\s*\\(", // function calls
                        "\\.$ident\\s*\\(" // method calls
                        );
            } else if (type == CodeUnitType.CLASS) {
                return Set.of(
                        "\\bnew\\s+$ident(?:<.+?>)?\\s*\\(", // constructor calls with optional generics
                        "\\bclass\\s+\\w+\\s+extends\\s+$ident(?:<.+?>)?", // class extends with optional generics
                        "\\bimplements\\s+$ident(?:<.+?>)?", // interface implementation with optional generics
                        "\\b$ident\\s*\\.", // static access
                        ":\\s*$ident(?:<.+?>)?", // type annotations with optional generics
                        "=>\\s*$ident(?:<.+?>)?", // arrow function return type with optional generics
                        "<\\s*$ident\\s*>", // as generic type argument
                        "\\bimport\\s+.*$ident", // import statements
                        "\\bfrom\\s+.*\\{.*$ident.*\\}" // named imports
                        );
            }
            return DependencyImportable.super.getSearchPatterns(type);
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public List<Path> getDependencyCandidates(ICoreProject project) {
            return NodeJsDependencyHelper.getDependencyCandidates(project);
        }

        @Override
        public List<Language.DependencyCandidate> listDependencyPackages(ICoreProject project) {
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
        public boolean isAnalyzed(ICoreProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };

    public static final Language SCALA = new ScalaLanguage();

    public static final List<Language> ALL_LANGUAGES =
            List.of(C_SHARP, JAVA, JAVASCRIPT, PYTHON, C_CPP, GO, RUST, PHP, TYPESCRIPT, SCALA, SQL, NONE);

    /**
     * Aggregates a set of languages into a single Language handle.
     * Handles empty, single-element, and multi-element sets appropriately.
     *
     * @param languages The set of languages to aggregate.
     * @return A single Language handle (NONE, a concrete language, or a MultiLanguage wrapper).
     */
    public static Language aggregate(Set<Language> languages) {
        Set<Language> filtered =
                languages.stream().filter(l -> l != Languages.NONE).collect(Collectors.toSet());

        if (filtered.isEmpty()) {
            return Languages.NONE;
        }
        if (filtered.size() == 1) {
            return filtered.iterator().next();
        }
        return new Language.MultiLanguage(filtered);
    }

    public static boolean isJvmLanguage(@Nullable Language language) {
        if (language == null || language == NONE) return false;

        if (language instanceof JvmLanguage) return true;

        if (language instanceof Language.MultiLanguage multi) {
            return multi.getLanguages().stream().anyMatch(Languages::isJvmLanguage);
        }

        return false;
    }

    /**
     * Scans the project files to detect which languages are present based on file extensions.
     * Uses tracked files if it's a git repo, otherwise scans all project files.
     */
    @Blocking
    public static List<Language> findLanguagesInProject(ICoreProject project) {
        Set<Language> langs = new HashSet<>();
        Set<ProjectFile> filesToScan = project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();
        for (var pf : filesToScan) {
            String extension = pf.extension();
            if (!extension.isEmpty()) {
                var lang = Languages.fromExtension(extension);
                if (lang != Languages.NONE) {
                    langs.add(lang);
                }
            }
        }
        return new ArrayList<>(langs);
    }

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
