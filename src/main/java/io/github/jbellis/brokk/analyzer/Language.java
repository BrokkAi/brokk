package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.util.regex.Pattern;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;


public interface Language {
    Logger logger = LogManager.getLogger(Language.class);

    List<String> getExtensions();
    String name(); // Human-friendly
    String internalName(); // Filesystem-safe
    IAnalyzer createAnalyzer(Project project);
    IAnalyzer loadAnalyzer(Project project);

    /**
     * Get the path where the CPG for this language in the given project should be stored.
     * @param project The project.
     * @return The path to the CPG file.
     */
    default Path getCpgPath(Project project) {
        // Use oldName for CPG path to ensure stable and filesystem-safe names
        return project.getRoot().resolve(".brokk").resolve(internalName().toLowerCase() + ".cpg");
    }

    default List<Path> getDependencyCandidates(Project project) {
        return List.of();
    }

    /**
     * Checks if the given path is likely already analyzed as part of the project's primary sources.
     * This is used to warn the user if they try to import a directory that might be redundant.
     * The path provided is expected to be absolute.
     *
     * @param project The current project.
     * @param path The absolute path to check.
     * @return {@code true} if the path is considered part of the project's analyzed sources, {@code false} otherwise.
     */
    default boolean isAnalyzed(Project project, Path path) {
        assert path.isAbsolute() : "Path must be absolute for isAnalyzed check: " + path;
        return path.normalize().startsWith(project.getRoot());
    }

    default boolean isCpg() {
        return false;
    }

    // --- Concrete Language Instances ---

    Language C_SHARP = new Language() {
        private final List<String> extensions = List.of("cs");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "C#"; }
        @Override public String internalName() { return "C_SHARP"; }
        @Override public String toString() { return name(); } // For compatibility
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new CSharpAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    Language JAVA = new Language() {
        private final List<String> extensions = List.of("java");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "Java"; }
        @Override public String internalName() { return "JAVA"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            var analyzer = new JavaAnalyzer(project.getRoot(), project.getBuildDetails().excludedDirectories());
            analyzer.writeCpg(getCpgPath(project));
            return analyzer;
        }

        @Override public JavaAnalyzer loadAnalyzer(Project project) {
            return new JavaAnalyzer(project.getRoot(), getCpgPath(project));
        }

        @Override
        public List<Path> getDependencyCandidates(Project project) {
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
                        String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
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

        @Override
        public boolean isCpg() { return true; }
    };

    Pattern PY_SITE_PKGS = Pattern.compile("^python\\d+\\.\\d+$");

    Language JAVASCRIPT = new Language() {
        private final List<String> extensions = List.of("js", "mjs", "cjs", "jsx");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "JavaScript"; }
        @Override public String internalName() { return "JAVASCRIPT"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new JavascriptAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}

        @Override
        public List<Path> getDependencyCandidates(Project project) {
            logger.debug("Scanning for JavaScript dependency candidates in project: {}", project.getRoot());
            var results = new ArrayList<Path>();
            Path nodeModules = project.getRoot().resolve("node_modules");

            if (Files.isDirectory(nodeModules)) {
                logger.debug("Scanning node_modules directory: {}", nodeModules);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodeModules)) {
                    for (Path entry : ds) {
                        String name = entry.getFileName().toString();
                        if (name.equals(".bin")) continue;  // skip executables
                        if (name.startsWith("@")) {        // scoped pkgs
                            logger.debug("Found scoped package directory: {}", entry);
                            try (DirectoryStream<Path> scoped = Files.newDirectoryStream(entry)) {
                                for (Path scopedPkg : scoped) {
                                    if (Files.isDirectory(scopedPkg)) {
                                        logger.debug("Found JS dependency candidate (scoped): {}", scopedPkg);
                                        results.add(scopedPkg);
                                    }
                                }
                            }
                        } else if (Files.isDirectory(entry)) {
                            logger.debug("Found JS dependency candidate: {}", entry);
                            results.add(entry);
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Error scanning node_modules directory {}: {}", nodeModules, e.getMessage());
                }
            } else {
                logger.debug("node_modules directory not found at: {}", nodeModules);
            }

            logger.debug("Found {} JavaScript dependency candidates.", results.size());
            return results;
        }

        @Override
        public boolean isAnalyzed(Project project, Path pathToImport) {
            assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
            Path projectRoot = project.getRoot();
            Path normalizedPathToImport = pathToImport.normalize();

            if (!normalizedPathToImport.startsWith(projectRoot)) {
                return false; // Not part of this project
            }

            // Check if the path is node_modules or inside node_modules directly under project root
            Path nodeModulesPath = projectRoot.resolve("node_modules");
            return !normalizedPathToImport.startsWith(nodeModulesPath);
        }
    };

    Language PYTHON = new Language() {
        private final List<String> extensions = List.of("py");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "Python"; }
        @Override public String internalName() { return "PYTHON"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new PythonAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}

        private List<Path> findVirtualEnvs(Path root) {
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
        public List<Path> getDependencyCandidates(Project project) {
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
        public boolean isAnalyzed(Project project, Path pathToImport) {
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

    Language C_CPP = new Language() {
        private final List<String> extensions = List.of("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "C/C++"; }
        @Override public String internalName() { return "C_CPP"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            var analyzer = new CppAnalyzer(project.getRoot(), project.getBuildDetails().excludedDirectories());
            analyzer.writeCpg(getCpgPath(project));
            return analyzer;
        }

        @Override public CppAnalyzer loadAnalyzer(Project project) {
            return new CppAnalyzer(project.getRoot(), getCpgPath(project));
        }
        @Override
        public boolean isCpg() { return true; }
    };

    Language GO = new Language() {
        private final List<String> extensions = List.of("go");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "Go"; }
        @Override public String internalName() { return "GO"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new GoAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
        @Override public List<Path> getDependencyCandidates(Project project) { return List.of(); }
    };

    Language RUST = new Language() {
        private final List<String> extensions = List.of("rs");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "Rust"; }
        @Override public String internalName() { return "RUST"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new RustAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
        // TODO: Implement getDependencyCandidates for Rust (e.g. scan Cargo.lock, vendor dir)
        @Override public List<Path> getDependencyCandidates(Project project) { return List.of(); }
        // TODO: Refine isAnalyzed for Rust (e.g. target directory, .cargo, vendor)
        @Override
        public boolean isAnalyzed(Project project, Path pathToImport) {
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
            if (Files.isDirectory(cargoDir) && normalizedPathToImport.startsWith(cargoDir)) {
                return false;
            }
            return true; // Default: if under project root and not in typical build/dependency dirs
        }
    };

    Language NONE = new Language() {
        private final List<String> extensions = Collections.emptyList();
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "None"; }
        @Override public String internalName() { return "NONE"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new DisabledAnalyzer();
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    // --- Infrastructure for fromExtension and enum-like static methods ---

    Language PHP = new Language() {
        private final List<String> extensions = List.of("php", "phtml", "php3", "php4", "php5", "phps");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "PHP"; }
        @Override public String internalName() { return "PHP"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new PhpAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) { return createAnalyzer(project); }
        // TODO: Implement getDependencyCandidates for PHP (e.g. composer's vendor directory)
        @Override public List<Path> getDependencyCandidates(Project project) { return List.of(); }
        // TODO: Refine isAnalyzed for PHP (e.g. vendor directory)
        @Override
        public boolean isAnalyzed(Project project, Path pathToImport) {
            assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
            Path projectRoot = project.getRoot();
            Path normalizedPathToImport = pathToImport.normalize();

            if (!normalizedPathToImport.startsWith(projectRoot)) {
                return false; // Not part of this project
            }
            // Example: exclude vendor directory
            Path vendorDir = projectRoot.resolve("vendor");
            if (normalizedPathToImport.startsWith(vendorDir)) {
                return false;
            }
            return true; // Default: if under project root and not in typical build/dependency dirs
        }
    };

    // --- Infrastructure for fromExtension and enum-like static methods ---

    List<Language> ALL_LANGUAGES = List.of(C_SHARP,
                                           JAVA,
                                           JAVASCRIPT,
                                           PYTHON,
                                           C_CPP,
                                           GO,
                                           RUST,
                                           PHP, // Added PHP here
                                           NONE);

    /**
     * Returns the Language constant corresponding to the given file extension.
     * Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    static Language fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return NONE;
        }
        String lowerExt = extension.toLowerCase();
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
     * @throws NullPointerException if name is null.
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
}
