package io.github.jbellis.brokk.tools;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.git.IGitRepo;

import java.lang.reflect.Field;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility to print skeleton output for files in a directory or a specific file.
 * Usage: java SkeletonPrinter [--skeleton-only] [--no-color] <path> <language>
 *
 * Options:
 *   --skeleton-only    Only show skeleton output, not original content
 *   --no-color         Disable colored output
 *
 * Path can be:
 *   - A directory: Analyze all files of the specified language in the directory
 *   - A specific file: Analyze only that file (language must still match)
 *
 * Supported languages: typescript, javascript, java, python
 */
public class SkeletonPrinter {

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";
    private static final String GRAY = "\u001B[90m";

    private static boolean useColors = true;
    private static boolean skeletonOnly = false;

    private static boolean matchesLanguage(Path path, Language language) {
        var fileName = path.getFileName().toString().toLowerCase();
        return switch (language.name()) {
            case "TYPESCRIPT" -> fileName.endsWith(".ts") || fileName.endsWith(".tsx");
            case "JAVASCRIPT" -> fileName.endsWith(".js") || fileName.endsWith(".jsx");
            case "JAVA" -> fileName.endsWith(".java");
            case "PYTHON" -> fileName.endsWith(".py");
            default -> false;
        };
    }

    private record DirectoryProject(Path root, Language language) implements IProject {

        @Override
        public Set<Language> getAnalyzerLanguages() {
                return Set.of(language);
            }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> matchesLanguage(p, language))
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                System.err.println("Error walking directory: " + e.getMessage());
                return Set.of();
            }
        }
    }

    private record SingleFileProject(Path root, Path filePath, Language language) implements IProject {

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(language);
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            var relativePath = root.relativize(filePath);
            return Set.of(new ProjectFile(root, relativePath));
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java SkeletonPrinter [--skeleton-only] [--no-color] <path> <language>");
            System.err.println("Options:");
            System.err.println("  --skeleton-only    Only show skeleton output, not original content");
            System.err.println("  --no-color         Disable colored output");
            System.err.println("Path can be a directory or a specific file");
            System.err.println("Supported languages: typescript, javascript, java, python");
            System.exit(1);
        }

        // Parse command-line arguments
        int argIndex = 0;
        while (argIndex < args.length - 2) {
            switch (args[argIndex]) {
                case "--skeleton-only" -> skeletonOnly = true;
                case "--no-color" -> useColors = false;
                default -> {
                    System.err.println("Unknown option: " + args[argIndex]);
                    System.exit(1);
                }
            }
            argIndex++;
        }

        if (argIndex + 1 >= args.length) {
            System.err.println("Error: Missing path or language argument");
            System.exit(1);
        }

        var inputPath = Path.of(args[argIndex]);
        var languageStr = args[argIndex + 1].toLowerCase();

        if (!Files.exists(inputPath)) {
            System.err.println("Error: Path does not exist: " + inputPath);
            System.exit(1);
        }

        var language = parseLanguage(languageStr);
        if (language == null) {
            System.err.println("Error: Unsupported language: " + languageStr);
            System.err.println("Supported languages: typescript, javascript, java, python");
            System.exit(1);
        }

        try {
            printSkeletons(inputPath, language);
        } catch (Exception e) {
            System.err.println("Error processing files: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Language parseLanguage(String languageStr) {
        return switch (languageStr) {
            case "typescript", "ts" -> Language.TYPESCRIPT;
            case "javascript", "js" -> Language.JAVASCRIPT;
            case "python", "py" -> Language.PYTHON;
            default -> null;
        };
    }

    private static void printSkeletons(Path inputPath, Language language) {
        if (Files.isDirectory(inputPath)) {
            printDirectorySkeletons(inputPath, language);
        } else if (Files.isRegularFile(inputPath)) {
            printSingleFileSkeletons(inputPath, language);
        } else {
            System.err.println("Error: Path is neither a regular file nor a directory: " + inputPath);
        }
    }

    private static void printDirectorySkeletons(Path directory, Language language) {
        var project = new DirectoryProject(directory.toAbsolutePath(), language);
        var allFiles = project.getAllFiles();

        if (allFiles.isEmpty()) {
            System.out.println("No matching files found in directory: " + directory);
            return;
        }

        System.out.println(colorize(BOLD + CYAN, "=== SKELETON ANALYSIS FOR " + language.name() + " FILES ==="));
        System.out.println(colorize(BLUE, "Directory: ") + directory);
        System.out.println(colorize(BLUE, "Files found: ") + allFiles.size());
        if (!skeletonOnly) {
            System.out.println(colorize(YELLOW, "Use --skeleton-only to show only skeleton output"));
        }
        System.out.println();

        // Process each file individually to avoid CodeUnit name collisions across files
        for (var file : allFiles.stream().sorted().toList()) {
            // Create a separate analyzer for each file to avoid name collisions
            var parentDir = file.absPath().getParent();
            var singleFileProject = new SingleFileProject(parentDir, file.absPath(), language);
            var analyzer = createAnalyzer(singleFileProject, language);

            if (analyzer == null) {
                System.err.println("Error: Could not create analyzer for file: " + file);
                continue;
            }

            printFileSkeletons(analyzer, file);
        }
    }

    private static void printSingleFileSkeletons(Path filePath, Language language) {
        // Verify the file matches the language
        if (!matchesLanguage(filePath, language)) {
            System.err.println("Error: File extension does not match language " + language + ": " + filePath);
            return;
        }

        // Create a project that contains just this file
        var parentDir = filePath.getParent();
        if (parentDir == null) {
            parentDir = Path.of(".");
        }

        var project = new SingleFileProject(parentDir.toAbsolutePath(), filePath.toAbsolutePath(), language);
        var analyzer = createAnalyzer(project, language);

        if (analyzer == null) {
            System.err.println("Error: Could not create analyzer for language: " + language);
            return;
        }

        var projectFile = new ProjectFile(parentDir.toAbsolutePath(), parentDir.toAbsolutePath().relativize(filePath.toAbsolutePath()));

        System.out.println(colorize(BOLD + CYAN, "=== SKELETON ANALYSIS FOR " + language.name() + " FILE ==="));
        System.out.println(colorize(BLUE, "File: ") + filePath);
        if (!skeletonOnly) {
            System.out.println(colorize(YELLOW, "Use --skeleton-only to show only skeleton output"));
        }
        System.out.println();

        printFileSkeletons(analyzer, projectFile);
    }

    private static IAnalyzer createAnalyzer(IProject project, Language language) {
        return switch (language.name()) {
            case "TYPESCRIPT" -> new TypescriptAnalyzer(project);
            case "JAVASCRIPT" -> new JavascriptAnalyzer(project);
            case "PYTHON" -> new PythonAnalyzer(project);
            default -> null;
        };
    }

    private static void printFileSkeletons(IAnalyzer analyzer, ProjectFile file) {
        System.out.println();
        System.out.println(colorize(BOLD + PURPLE, "================================================================================"));
        System.out.println(colorize(BOLD + GREEN, "FILE: ") + colorize(CYAN, file.toString()));
        System.out.println(colorize(BOLD + PURPLE, "================================================================================"));

        if (!skeletonOnly) {
            // Print original file content
            System.out.println();
            System.out.println(colorize(BOLD + YELLOW, "--- ORIGINAL CONTENT ---"));
            try {
                var content = Files.readString(file.absPath());
                System.out.println(highlightOriginalContent(content));
            } catch (Exception e) {
                System.out.println(colorize(RED, "Error reading file: " + e.getMessage()));
            }
            System.out.println();
        }

        System.out.println(colorize(BOLD + GREEN, "--- SKELETON OUTPUT ---"));

        var skeletons = analyzer.getSkeletons(file);
        if (skeletons.isEmpty()) {
            System.out.println(colorize(YELLOW, "No skeletons found in this file."));
            return;
        }

        for (var entry : skeletons.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    CodeUnit cu1 = entry1.getKey();
                    CodeUnit cu2 = entry2.getKey();

                    // Try to sort by source position if analyzer supports it
                    if (analyzer instanceof TreeSitterAnalyzer treeAnalyzer) {
                        Integer pos1 = getSourceStartPosition(treeAnalyzer, cu1);
                        Integer pos2 = getSourceStartPosition(treeAnalyzer, cu2);
                        if (pos1 != null && pos2 != null) {
                            return Integer.compare(pos1, pos2);
                        }
                    }

                    // Fallback to alphabetical by name if position not available
                    return cu1.fqName().compareTo(cu2.fqName());
                })
                .toList()) {
            var skeleton = entry.getValue();
            System.out.println(highlightSkeleton(skeleton));
            System.out.println();
        }

        System.out.println(colorize(BOLD + PURPLE, "================================================================================"));
    }

    private static Integer getSourceStartPosition(TreeSitterAnalyzer analyzer, CodeUnit cu) {
        try {
            // Use reflection to access the private sourceRanges field
            Field sourceRangesField = TreeSitterAnalyzer.class.getDeclaredField("sourceRanges");
            sourceRangesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var sourceRanges = (java.util.Map<CodeUnit, java.util.List<Object>>) sourceRangesField.get(analyzer);

            var ranges = sourceRanges.get(cu);
            if (ranges != null && !ranges.isEmpty()) {
                // Get the first range and extract startByte using reflection
                Object firstRange = ranges.get(0);
                Field startByteField = firstRange.getClass().getDeclaredField("startByte");
                startByteField.setAccessible(true);
                return (Integer) startByteField.get(firstRange);
            }
        } catch (Exception e) {
            // If reflection fails, return null and fall back to other sorting
        }
        return null;
    }

    private static String colorize(String colorCode, String text) {
        if (!useColors) {
            return text;
        }
        return colorCode + text + RESET;
    }

    private static String highlightSkeleton(String skeleton) {
        if (!useColors) {
            return skeleton;
        }

        // Apply syntax highlighting to skeleton
        String highlighted = skeleton;

        // Highlight keywords
        highlighted = highlighted.replaceAll("\\b(export|class|interface|enum|function|const|let|var|type|namespace)\\b",
                                            colorize(BOLD + BLUE, "$1"));

        // Highlight method bodies placeholder
        highlighted = highlighted.replaceAll("\\{ \\.\\.\\. \\}", colorize(YELLOW, "{ ... }"));

        // Highlight decorators
        highlighted = highlighted.replaceAll("@\\w+", colorize(PURPLE, "$0"));

        // Highlight access modifiers
        highlighted = highlighted.replaceAll("\\b(public|private|protected|readonly|static|async|abstract)\\b",
                                            colorize(CYAN, "$1"));

        return highlighted;
    }

    private static String highlightOriginalContent(String content) {
        if (!useColors) {
            return content;
        }

        // Apply comprehensive syntax highlighting to original TypeScript content
        String highlighted = content;

        // Highlight keywords (TypeScript/JavaScript)
        highlighted = highlighted.replaceAll("\\b(export|default|class|interface|enum|function|const|let|var|type|namespace|import|from|as)\\b",
                                            colorize(BOLD + BLUE, "$1"));

        // Highlight control flow keywords
        highlighted = highlighted.replaceAll("\\b(if|else|for|while|do|switch|case|break|continue|return|throw|try|catch|finally)\\b",
                                            colorize(BLUE, "$1"));

        // Highlight access modifiers and other modifiers
        highlighted = highlighted.replaceAll("\\b(public|private|protected|readonly|static|async|abstract|extends|implements)\\b",
                                            colorize(CYAN, "$1"));

        // Highlight primitive types
        highlighted = highlighted.replaceAll("\\b(string|number|boolean|void|any|unknown|never|object|null|undefined)\\b",
                                            colorize(GREEN, "$1"));

        // Highlight decorators
        highlighted = highlighted.replaceAll("@\\w+", colorize(PURPLE, "$0"));

        // Highlight string literals
        highlighted = highlighted.replaceAll("\"([^\"\\\\]|\\\\.)*\"", colorize(GREEN, "$0"));
        highlighted = highlighted.replaceAll("'([^'\\\\]|\\\\.)*'", colorize(GREEN, "$0"));
        highlighted = highlighted.replaceAll("`([^`\\\\]|\\\\.)*`", colorize(GREEN, "$0"));

        // Highlight numbers
        highlighted = highlighted.replaceAll("\\b\\d+(\\.\\d+)?\\b", colorize(YELLOW, "$0"));

        // Highlight comments
        highlighted = highlighted.replaceAll("(?m)//.*$", colorize(GRAY, "$0")); // Dark gray for single-line comments
        highlighted = highlighted.replaceAll("/\\*[\\s\\S]*?\\*/", colorize(GRAY, "$0")); // Dark gray for multi-line comments

        // Highlight operators (arrow functions, etc.)
        highlighted = highlighted.replaceAll("=>", colorize(YELLOW, "=>"));
        highlighted = highlighted.replaceAll("\\b(new|instanceof|typeof|in)\\b", colorize(PURPLE, "$1"));

        return highlighted;
    }

}
