package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public class Languages {
    public static final Language C_SHARP = new Language() {
        private final List<String> extensions = List.of("cs");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new CSharpAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }
    };
    public static final Language JAVA = new JavaLanguage();
    public static final Language JAVASCRIPT = new Language() {
        private final List<String> extensions = List.of("js", "mjs", "cjs", "jsx");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new JavascriptAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
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
        public boolean supportesDependencyKinds() {
            return NodeJsDependencyHelper.supportsDependencyKinds();
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };
    public static final Language PYTHON = new PythonLanguage();
    public static final Language C_CPP = new Language() {
        private final List<String> extensions = List.of("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new CppTreeSitterAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }
    };
    public static final Language GO = new Language() {
        private final List<String> extensions = List.of("go");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new GoAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }

        // TODO
        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return List.of();
        }
    };
    public static final Language CPP_TREESITTER = new Language() {
        private final List<String> extensions = List.of("cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "h++", "h");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new CppTreeSitterAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }
    };
    public static final Language RUST = new RustLanguage();
    public static final Language NONE = new Language() {
        private final List<String> extensions = Collections.emptyList();

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new DisabledAnalyzer();
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }
    };
    public static final Language PHP = new Language() {
        private final List<String> extensions = List.of("php", "phtml", "php3", "php4", "php5", "phps");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            return new PhpAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }

        // TODO: Implement getDependencyCandidates for PHP (e.g. composer's vendor directory)
        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return List.of();
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
        private final List<String> extensions = List.of("sql");

        @Override
        public List<String> getExtensions() {
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
        public IAnalyzer createAnalyzer(IProject project) {
            var excludedDirStrings = project.getExcludedDirectories();
            var excludedPaths = excludedDirStrings.stream().map(Path::of).collect(Collectors.toSet());
            return new SqlAnalyzer(project, excludedPaths);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            // SQLAnalyzer does not save/load state from disk beyond re-parsing
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }
    };
    public static final Language TYPESCRIPT = new Language() {
        private final List<String> extensions =
                List.of("ts", "tsx"); // Including tsx for now, can be split later if needed

        @Override
        public List<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "TYPESCRIPT";
        }

        @Override
        public String internalName() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            return new TypescriptAnalyzer(project, project.getExcludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public boolean providesSummaries() {
            return true;
        }

        @Override
        public boolean providesSourceCode() {
            return true;
        }

        @Override
        public boolean providesInterproceduralAnalysis() {
            return false;
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
        public boolean supportesDependencyKinds() {
            return NodeJsDependencyHelper.supportsDependencyKinds();
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };
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
        Objects.requireNonNull(name, "Name is null");
        for (Language lang : ALL_LANGUAGES) {
            // Check current human-friendly name first, then old programmatic name for backward compatibility.
            if (lang.name().equals(name) || lang.internalName().equals(name)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("No language constant " + Language.class.getCanonicalName() + "." + name);
    }
}
