package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public interface Language {
    Logger logger = LogManager.getLogger(Language.class);

    List<String> getExtensions();

    String name(); // Human-friendly

    String internalName(); // Filesystem-safe

    IAnalyzer createAnalyzer(IProject project);

    IAnalyzer loadAnalyzer(IProject project);

    default IAnalyzer loadOrCreateAnalyzer(IProject project) {
        return loadAnalyzer(project);
    }

    /**
     * Get the path where the CPG for this language in the given project should be stored.
     *
     * @param project The project.
     * @return The path to the CPG file.
     */
    default Path getCpgPath(IProject project) {
        // Use oldName for CPG path to ensure stable and filesystem-safe names
        return project.getRoot().resolve(".brokk").resolve(internalName().toLowerCase(Locale.ROOT) + ".cpg");
    }

    default List<Path> getDependencyCandidates(IProject project) {
        return List.of();
    }

    /**
     * Checks if the given path is likely already analyzed as part of the project's primary sources.
     * This is used to warn the user if they try to import a directory that might be redundant.
     * The path provided is expected to be absolute.
     *
     * @param project The current project.
     * @param path    The absolute path to check.
     * @return {@code true} if the path is considered part of the project's analyzed sources, {@code false} otherwise.
     */
    default boolean isAnalyzed(IProject project, Path path) {
        assert path.isAbsolute() : "Path must be absolute for isAnalyzed check: " + path;
        return path.normalize().startsWith(project.getRoot());
    }

    default boolean isCpg() {
        return false;
    }

    // --- Concrete Language Instances ---

    Language C_SHARP = new Language() {
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
            return new CSharpAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }
    };

    Language JAVA = new CpgLanguage() {
        private final List<String> extensions = List.of("java");

        @Override
        public List<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "Java";
        }

        @Override
        public String internalName() {
            return "JAVA";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            var cpgPath = getCpgPath(project);
            return new JavaAnalyzer(project.getRoot(), project.loadBuildDetails().excludedDirectories(), cpgPath);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return new JavaAnalyzer(project.getRoot(), getCpgPath(project));
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            long startTime = System.currentTimeMillis();

            String userHome = System.getProperty("user.home");
            if (userHome == null) {
                logger.warn("Could not determine user home directory.");
                return List.of();
            }
            Path homePath = Path.of(userHome);

            List<Path> rootsToScan = new ArrayList<>();

            /* ---------- default locations that exist on all OSes ---------- */
            rootsToScan.add(homePath.resolve(".m2").resolve("repository"));
            rootsToScan.add(homePath.resolve(".gradle").resolve("caches")
                                    .resolve("modules-2").resolve("files-2.1"));
            rootsToScan.add(homePath.resolve(".ivy2").resolve("cache"));
            rootsToScan.add(homePath.resolve(".cache").resolve("coursier")
                                    .resolve("v1").resolve("https"));
            rootsToScan.add(homePath.resolve(".sbt"));

            /* ---------- honour user-supplied overrides ---------- */
            Optional.ofNullable(System.getenv("MAVEN_REPO"))
                    .map(Path::of)
                    .ifPresent(rootsToScan::add);

            Optional.ofNullable(System.getProperty("maven.repo.local"))
                    .map(Path::of)
                    .ifPresent(rootsToScan::add);

            Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                    .map(Path::of)
                    .map(p -> p.resolve("caches")
                            .resolve("modules-2").resolve("files-2.1"))
                    .ifPresent(rootsToScan::add);

            /* ---------- Windows-specific cache roots ---------- */
            if (Environment.isWindows()) {
                Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                    Path lad = Path.of(localAppData);
                    rootsToScan.add(lad.resolve("Coursier").resolve("cache")
                                            .resolve("v1").resolve("https"));
                    rootsToScan.add(lad.resolve("Gradle").resolve("caches")
                                            .resolve("modules-2").resolve("files-2.1"));
                });
            }

            /* ---------- macOS-specific cache roots ---------- */
            if (Environment.isMacOs()) {
                // Coursier on macOS defaults to ~/Library/Caches/Coursier/v1/https
                rootsToScan.add(homePath
                                        .resolve("Library").resolve("Caches")
                                        .resolve("Coursier").resolve("v1")
                                        .resolve("https"));
            }

            /* ---------- de-duplicate & scan ---------- */
            List<Path> uniqueRoots = rootsToScan.stream().distinct().toList();

            var jarFiles = uniqueRoots.parallelStream()
                    .filter(Files::isDirectory)
                    .peek(root -> logger.debug("Scanning for JARs under: {}", root))
                    .flatMap(root -> {
                        try {
                            return Files.walk(root, FileVisitOption.FOLLOW_LINKS);
                        } catch (IOException e) {
                            logger.warn("Error walking directory {}: {}", root, e.getMessage());
                            return Stream.empty();
                        } catch (SecurityException e) {
                            logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                            return Stream.empty();
                        }
                    })
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .toList();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Found {} JAR files in common dependency locations in {} ms",
                        jarFiles.size(), duration);

            return jarFiles;
        }

    };

    Pattern PY_SITE_PKGS = Pattern.compile("^python\\d+\\.\\d+$");

    Language JAVASCRIPT = new Language() {
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
            return new JavascriptAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return NodeJsDependencyHelper.getDependencyCandidates(project);
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };

    Language PYTHON = new Language() {
        private final List<String> extensions = List.of("py");

        @Override
        public List<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "Python";
        }

        @Override
        public String internalName() {
            return "PYTHON";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            return new PythonAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        private List<Path> findVirtualEnvs(@Nullable Path root) {
            if (root == null) return List.of();
            List<Path> envs = new ArrayList<>();
            for (String candidate : List.of(".venv", "venv", "env")) {
                Path p = root.resolve(candidate);
                if (Files.isDirectory(p)) {
                    logger.debug("Found virtual env at: {}", p);
                    envs.add(p);
                }
            }
            // also look one level down for monorepos with /backend/venv etc.
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path sub : ds) {
                    if (!Files.isDirectory(sub)) continue; // Skip files, only look in subdirectories
                    Path venv = sub.resolve(".venv");
                    if (Files.isDirectory(venv)) {
                        logger.debug("Found virtual env at: {}", venv);
                        envs.add(venv);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error scanning for virtual envs: {}", e.getMessage());
            }
            return envs;
        }

        private Path findSitePackagesInLibDir(Path libDir) {
            if (!Files.isDirectory(libDir)) {
                return Path.of("");
            }
            try (DirectoryStream<Path> pyVers = Files.newDirectoryStream(libDir)) {
                for (Path py : pyVers) {
                    if (Files.isDirectory(py) && PY_SITE_PKGS.matcher(py.getFileName().toString()).matches()) {
                        Path site = py.resolve("site-packages");
                        if (Files.isDirectory(site)) {
                            return site;
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Error scanning Python lib directory {}: {}", libDir, e.getMessage());
            }
            return Path.of("");
        }

        private Path sitePackagesDir(Path venv) {
            // Try "lib" first
            Path libDir = venv.resolve("lib");
            Path sitePackages = findSitePackagesInLibDir(libDir);
            if (Files.isDirectory(sitePackages)) { // Check if a non-empty and valid path was returned
                logger.debug("Found site-packages in: {}", sitePackages);
                return sitePackages;
            }

            // If not found in "lib", try "lib64"
            Path lib64Dir = venv.resolve("lib64");
            sitePackages = findSitePackagesInLibDir(lib64Dir);
            if (Files.isDirectory(sitePackages)) { // Check again
                logger.debug("Found site-packages in: {}", sitePackages);
                return sitePackages;
            }

            logger.debug("No site-packages found in {} or {}", libDir, lib64Dir);
            return Path.of(""); // Return empty path if not found in either
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            logger.debug("Scanning for Python dependency candidates in project: {}", project.getRoot());
            List<Path> results = new ArrayList<>();
            List<Path> venvs = findVirtualEnvs(project.getRoot());
            if (venvs.isEmpty()) {
                logger.debug("No virtual environments found for Python dependency scan.");
            }

            venvs.stream()
                    .map(this::sitePackagesDir)
                    .filter(Files::isDirectory) // Filter out empty paths returned by sitePackagesDir if not found
                    .forEach(dir -> {
                        logger.debug("Scanning site-packages directory: {}", dir);
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                            for (Path p : ds) {
                                String name = p.getFileName().toString();
                                if (name.endsWith(".dist-info") || name.endsWith(".egg-info") || name.startsWith("_")) {
                                    continue;
                                }
                                if (Files.isDirectory(p)) {
                                    logger.debug("Found Python dependency candidate: {}", p);
                                    results.add(p);
                                }
                            }
                        } catch (IOException e) {
                            logger.warn("Error scanning site-packages directory {}: {}", dir, e.getMessage());
                        }
                    });
            logger.debug("Found {} Python dependency candidates.", results.size());
            return results;
        }

        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
            Path projectRoot = project.getRoot();
            Path normalizedPathToImport = pathToImport.normalize();

            if (!normalizedPathToImport.startsWith(projectRoot)) {
                return false; // Not part of this project
            }

            // Check if the path is inside any known virtual environment locations.
            // findVirtualEnvs looks at projectRoot and one level down.
            List<Path> venvPaths = findVirtualEnvs(projectRoot).stream()
                    .map(Path::normalize)
                    .toList();
            for (Path venvPath : venvPaths) {
                if (normalizedPathToImport.startsWith(venvPath)) {
                    // Paths inside virtual environments are dependencies, not primary analyzed sources.
                    return false;
                }
            }
            return true; // It's under project root and not in a known venv.
        }
    };

    Language C_CPP = new CpgLanguage() {
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
            var cpgPath = getCpgPath(project);
            return new CppAnalyzer(project.getRoot(), project.loadBuildDetails().excludedDirectories(), cpgPath);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return new CppAnalyzer(project.getRoot(), getCpgPath(project));
        }
    };

    Language GO = new Language() {
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
            return new GoAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        // TODO
        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return List.of();
        }
    };

    Language RUST = new Language() {
        private final List<String> extensions = List.of("rs");

        @Override
        public List<String> getExtensions() {
            return extensions;
        }

        @Override
        public String name() {
            return "Rust";
        }

        @Override
        public String internalName() {
            return "RUST";
        }

        @Override
        public String toString() {
            return name();
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            return new RustAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
        }

        // TODO: Implement getDependencyCandidates for Rust (e.g. scan Cargo.lock, vendor dir)
        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return List.of();
        }

        // TODO: Refine isAnalyzed for Rust (e.g. target directory, .cargo, vendor)
        @Override
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
            Path projectRoot = project.getRoot();
            Path normalizedPathToImport = pathToImport.normalize();

            if (!normalizedPathToImport.startsWith(projectRoot)) {
                return false; // Not part of this project
            }
            // Example: exclude target directory
            Path targetDir = projectRoot.resolve("target");
            if (normalizedPathToImport.startsWith(targetDir)) {
                return false;
            }
            // Example: exclude .cargo directory if it exists
            Path cargoDir = projectRoot.resolve(".cargo");
            return !Files.isDirectory(cargoDir) || !normalizedPathToImport.startsWith(cargoDir);// Default: if under project root and not in typical build/dependency dirs
        }
    };

    Language NONE = new Language() {
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
    };

    // --- Infrastructure for fromExtension and enum-like static methods ---

    Language PHP = new Language() {
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
            return new PhpAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
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
            return !normalizedPathToImport.startsWith(vendorDir);// Default: if under project root and not in typical build/dependency dirs
        }
    };

    // --- Infrastructure for fromExtension and enum-like static methods ---
    // The ALL_LANGUAGES list is defined after all Language instances (including SQL) are defined.

    Language SQL = new Language() {
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
            var excludedDirStrings = project.loadBuildDetails().excludedDirectories();
            var excludedPaths = excludedDirStrings.stream().map(Path::of).collect(Collectors.toSet());
            return new SqlAnalyzer(project, excludedPaths);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            // SQLAnalyzer does not save/load state from disk beyond re-parsing
            return createAnalyzer(project);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return Language.super.getDependencyCandidates(project);
        }
    };

    Language TYPESCRIPT = new Language() {
        private final List<String> extensions = List.of("ts", "tsx"); // Including tsx for now, can be split later if needed

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
            return new TypescriptAnalyzer(project, project.loadBuildDetails().excludedDirectories());
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            return createAnalyzer(project);
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
        public boolean isAnalyzed(IProject project, Path pathToImport) {
            return NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
        }
    };

    List<Language> ALL_LANGUAGES = List.of(C_SHARP,
                                           JAVA,
                                           JAVASCRIPT,
                                           PYTHON,
                                           C_CPP,
                                           GO,
                                           RUST,
                                           PHP,
                                           TYPESCRIPT, // Now TYPESCRIPT is declared before this list
                                           SQL, // SQL is now defined and can be included
                                           NONE);

    /**
     * Returns the Language constant corresponding to the given file extension.
     * Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    static Language fromExtension(String extension) {
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
     * Returns an array containing all the defined Language constants, in the order
     * they are declared. This method is provided for compatibility with Enum.values().
     *
     * @return an array containing all the defined Language constants.
     */
    static Language[] values() {
        return ALL_LANGUAGES.toArray(new Language[0]);
    }

    /**
     * Returns the Language constant with the specified name.
     * The string must match exactly an identifier used to declare a Language constant.
     * (Extraneous whitespace characters are not permitted.)
     * This method is provided for compatibility with Enum.valueOf(String).
     *
     * @param name the name of the Language constant to be returned.
     * @return the Language constant with the specified name.
     * @throws IllegalArgumentException if this language type has no constant with the specified name.
     * @throws NullPointerException     if name is null.
     */
    static Language valueOf(String name) {
        Objects.requireNonNull(name, "Name is null");
        for (Language lang : ALL_LANGUAGES) {
            // Check current human-friendly name first, then old programmatic name for backward compatibility.
            if (lang.name().equals(name) || lang.internalName().equals(name)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("No language constant " + Language.class.getCanonicalName() + "." + name);
    }

    /**
     * A composite {@link Language} implementation that delegates all operations to the
     * wrapped set of concrete languages and combines the results.
     *
     * <p>Only the operations that make sense for a multi‑language view are implemented.
     * Methods tied to a single‐language identity ‑ such as {@link #internalName()} or
     * {@link #getCpgPath(IProject)} ‑ throw {@link UnsupportedOperationException}.</p>
     */
    class MultiLanguage implements Language {
        private final Set<Language> languages;

        public MultiLanguage(Set<Language> languages) {
            Objects.requireNonNull(languages, "languages set is null");
            if (languages.isEmpty())
                throw new IllegalArgumentException("languages set must not be empty");
            if (languages.stream().anyMatch(l -> l instanceof MultiLanguage))
                throw new IllegalArgumentException("cannot nest MultiLanguage inside itself");
            // copy defensively to guarantee immutability and deterministic ordering
            this.languages = languages.stream()
                    .filter(l -> l != Language.NONE)
                    .collect(Collectors.toUnmodifiableSet());
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
            return languages.stream().map(Language::name)
                    .collect(Collectors.joining("/"));
        }

        @Override
        public String internalName() {
            throw new UnsupportedOperationException("MultiLanguage has no single internalName()");
        }

        @Override
        public Path getCpgPath(IProject project) {
            throw new UnsupportedOperationException("MultiLanguage has no single CPG file");
        }

        @Override
        public boolean isCpg() {
            return languages.stream().anyMatch(Language::isCpg);
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project) {
            var delegates = new HashMap<Language, IAnalyzer>();
            for (var lang : languages) {
                var analyzer = lang.createAnalyzer(project);
                if (!analyzer.isEmpty())
                    delegates.put(lang, analyzer);
            }
            return delegates.size() == 1
                    ? delegates.values().iterator().next()
                    : new MultiAnalyzer(delegates);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project) {
            var delegates = new HashMap<Language, IAnalyzer>();
            for (var lang : languages) {
                var analyzer = lang.loadAnalyzer(project);
                if (!analyzer.isEmpty())
                    delegates.put(lang, analyzer);
            }
            return delegates.size() == 1
                    ? delegates.values().iterator().next()
                    : new MultiAnalyzer(delegates);
        }
        
        @Override
        public IAnalyzer loadOrCreateAnalyzer(IProject project) {
            var delegates = new HashMap<Language, IAnalyzer>();
            for (var lang : languages) {
                var analyzer = lang.loadOrCreateAnalyzer(project);
                if (!analyzer.isEmpty())
                    delegates.put(lang, analyzer);
            }
            return delegates.size() == 1
                    ? delegates.values().iterator().next()
                    : new MultiAnalyzer(delegates);
        }

        @Override
        public List<Path> getDependencyCandidates(IProject project) {
            return languages.stream()
                    .flatMap(l -> l.getDependencyCandidates(project).stream())
                    .toList();
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

    /**
     * A language frontend that makes use of a Joern/CPG-based analysis.
     */
    interface CpgLanguage extends Language {
        @Override
        default IAnalyzer loadOrCreateAnalyzer(IProject project) {
            var cpgPath = getCpgPath(project);
            if (Files.isRegularFile(cpgPath)) return loadAnalyzer(project);
            else return createAnalyzer(project);
        }

        @Override
        default boolean isCpg() {
            return true;
        }
    }
}
