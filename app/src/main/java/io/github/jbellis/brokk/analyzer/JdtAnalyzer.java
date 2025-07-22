package io.github.jbellis.brokk.analyzer;

import org.eclipse.jdt.core.dom.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An Eclipse JDT-based analyzer for Java source code that builds a call graph.
 */
public class JdtAnalyzer implements IAnalyzer {

    private final Logger logger = LoggerFactory.getLogger(JdtAnalyzer.class);
    private final Map<IMethodBinding, Set<IMethodBinding>> callGraph = new HashMap<>();

    /**
     * Instantiates an Eclipse JDT based analyzer for Java source code.
     *
     * @param projectRoot         the root directory of the project.
     * @param excludedDirectories the directories to be excluded from parsing, relative to {@code  projectRoot}.
     */
    public JdtAnalyzer(@NotNull final Path projectRoot, @NotNull final Set<String> excludedDirectories) {
        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Error: Provided source path is not a valid directory: " + projectRoot);
        } else {
            final var absProjectRoot = projectRoot.toAbsolutePath();
            final Set<Path> exclusions = excludedDirectories.stream()
                    .map(p -> absProjectRoot.resolve(p).toAbsolutePath())
                    .collect(Collectors.toSet());

            final var sourceFiles = this.parseDirectory(absProjectRoot, exclusions);
            this.parseFilesAndConstructCallGraph(absProjectRoot, sourceFiles);
        }
    }

    /**
     * Ingests and parses all .java files from a source directory, excluding specific paths, and returns the paths to
     * these.
     *
     * @param projectRoot    The absolute path to the root of the codebase (e.g., " /path/to/project/src/main/java").
     * @param exclusionPaths A set of absolute paths to exclude from parsing. Any file or directory starting
     *                       with one of these paths will be ignored.
     */
    @NotNull
    private List<String> parseDirectory(@NotNull final Path projectRoot, @NotNull final Set<Path> exclusionPaths) {
        logger.debug("Starting parsing from: {}", projectRoot);

        final List<String> filePaths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            filePaths.addAll(walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> isNotExcluded(path, exclusionPaths))
                    .map(Path::toString)
                    .toList());
        } catch (IOException e) {
            logger.error("Error walking the file tree to collect source files.", e);
            return filePaths;
        }

        if (filePaths.isEmpty()) {
            logger.warn("No Java source files found to analyze.");
        }
        return filePaths;
    }

    private void parseFilesAndConstructCallGraph(@NotNull final Path projectRoot, @NotNull final List<String> sourceFiles) {
        final ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true); // Enable binding resolution
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setBindingsRecovery(true); // Attempt to recover from errors

        // Set the environment for the parser.
        // This is crucial for resolving bindings correctly.
        // We provide the project source directory as the source path.
        // We include the running VM's environment to resolve standard Java library types (e.g., String).
        parser.setEnvironment(
                null, // No explicit classpath entries; resolve from sourcepath
                new String[]{projectRoot.toString()}, // Source paths
                new String[]{StandardCharsets.UTF_8.name()}, // Encodings (we may want to support more than this
                true // Include the JRE
        );

        // The FileASTRequestor processes each compilation unit as it's created.
        final var requestor = new FileASTRequestor() {
            @Override
            public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                logger.debug("Processing AST for: {}", sourceFilePath);
                // The visitor builds the call graph for one file at a time.
                cu.accept(new CallGraphVisitor());
            }
        };

        parser.createASTs(
                sourceFiles.toArray(new String[0]), // All files to parse
                null, // Encodings (null to use the one set in setEnvironment)
                new String[0], // No specific binding keys to look for
                requestor,
                null // No progress monitor
        );

        logger.info("Analysis complete. Call graph contains {} methods.", callGraph.size());
    }

    /**
     * Checks if a given file path should be excluded.
     *
     * @param filePath       The path of the file to check.
     * @param exclusionPaths The set of paths to exclude.
     * @return true if the file path is NOT in an excluded directory, false otherwise.
     */
    private boolean isNotExcluded(final Path filePath, final Set<Path> exclusionPaths) {
        return exclusionPaths.stream().noneMatch(filePath::startsWith);
    }

    /**
     * Visitor to traverse the AST and build the call graph.
     * It identifies the current method context and logs all method invocations within it.
     */
    private class CallGraphVisitor extends ASTVisitor {

        @Nullable
        private IMethodBinding currentMethodBinding = null;

        // Visit a method declaration to set the current context
        @Override
        public boolean visit(MethodDeclaration node) {
            // Resolve the binding for the current method declaration
            this.currentMethodBinding = node.resolveBinding();
            if (this.currentMethodBinding != null) {
                callGraph.computeIfAbsent(this.currentMethodBinding, k -> new HashSet<>());
            }
            return super.visit(node);
        }

        // After visiting the method, clear the context
        @Override
        public void endVisit(MethodDeclaration node) {
            this.currentMethodBinding = null;
            super.endVisit(node);
        }

        // Visit a method invocation to find a call
        @Override
        public boolean visit(MethodInvocation node) {
            if (currentMethodBinding != null) {
                IMethodBinding invokedMethodBinding = node.resolveMethodBinding();
                if (invokedMethodBinding != null) {
                    // We have a caller (currentMethodBinding) and a callee (invokedMethodBinding)
                    callGraph.computeIfAbsent(currentMethodBinding, k -> new HashSet<>())
                            .add(invokedMethodBinding.getMethodDeclaration());
                }
            }
            return super.visit(node);
        }

        // Also visit constructor invocations
        @Override
        public boolean visit(ClassInstanceCreation node) {
            if (currentMethodBinding != null) {
                IMethodBinding constructorBinding = node.resolveConstructorBinding();
                if (constructorBinding != null) {
                    callGraph.computeIfAbsent(currentMethodBinding, k -> new HashSet<>())
                            .add(constructorBinding.getMethodDeclaration());
                }
            }
            return super.visit(node);
        }
    }

}
