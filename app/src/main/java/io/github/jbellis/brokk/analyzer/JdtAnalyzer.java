package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.util.LombokAnalysisUtils;
import org.apache.commons.io.FileUtils;
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
public class JdtAnalyzer implements IAnalyzer, AutoCloseable {

    private final Logger logger = LoggerFactory.getLogger(JdtAnalyzer.class);
    private final Map<Method, Set<Method>> callGraph = new HashMap<>();

    // Our "unknown" type is Object. This is not necessarily sound, but is friendly for now
    private static final String UNKNOWN = "java.lang.Object";
    private static final String VOID = "void";
    private static final String INIT = "<init>";
    private static final String UNRESOLVED_NAMESPACE = "<unresolvedNamespace>";

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
            var absProjectRoot = projectRoot.toAbsolutePath();
            final Set<Path> exclusions = excludedDirectories.stream().map(Path::of).collect(Collectors.toSet());

            if (LombokAnalysisUtils.projectUsesLombok(projectRoot)) {
                try {
                    final var tempDir = Files.createTempDirectory("brokk-jdt-analyzer-delombok-");
                    try {
                        if (LombokAnalysisUtils.runDelombok(projectRoot, tempDir)) {
                            this.parseFilesAndConstructCallGraph(tempDir, this.parseDirectory(tempDir, exclusions));
                        } else {
                            this.parseFilesAndConstructCallGraph(absProjectRoot, this.parseDirectory(absProjectRoot, exclusions));
                        }
                    } finally {
                        FileUtils.deleteDirectory(tempDir.toFile());
                    }
                } catch (IOException e) {
                    logger.error("Unable to create temporary directory to run Delombok on {}", projectRoot, e);
                }
            } else {
                this.parseFilesAndConstructCallGraph(absProjectRoot, this.parseDirectory(absProjectRoot, exclusions));
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return callGraph.isEmpty();
    }

    public boolean isClassInProject(String classFullName) {
        throw new UnsupportedOperationException();
    }

    public @NotNull String sanitizeType(String typeName) {
        // Check if the type has generic parameters
        if (typeName.contains("<")) {
            final String mainType = typeName.substring(0, typeName.indexOf('<'));
            final String genericPart = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));

            // Process the main part of the type (e.g., "java.util.List")
            final String processedMain = this.processType(mainType);

            // Process each generic parameter recursively
            final String processedParams = Arrays.stream(genericPart.split(","))
                    .map(param -> {
                        final String trimmed = param.trim();
                        // If a parameter is itself generic, recurse
                        if (trimmed.contains("<")) {
                            return this.sanitizeType(trimmed);
                        } else {
                            return this.processType(trimmed);
                        }
                    })
                    .collect(Collectors.joining(", "));

            return String.format("%s<%s>", processedMain, processedParams);
        } else {
            // If not a generic type, process directly
            return this.processType(typeName);
        }
    }

    /**
     * A helper method to convert a single, non-generic type name
     * to its simple name, preserving array brackets.
     *
     * @param typeString The type string (e.g., "java.lang.String" or "int[]").
     * @return The simple name (e.g., "String" or "int[]").
     */
    private String processType(String typeString) {
        boolean isArray = typeString.endsWith("[]");
        // Remove array brackets to get the base type name
        String base = isArray ? typeString.substring(0, typeString.length() - 2) : typeString;

        // Get the last part of the dot-separated package name
        int lastDotIndex = base.lastIndexOf('.');
        String shortName = (lastDotIndex != -1) ? base.substring(lastDotIndex + 1) : base;

        // Add array brackets back if they were present
        return isArray ? shortName + "[]" : shortName;
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
        private Method currentMethod = null;

        // Visit a method declaration to set the current context
        @Override
        public boolean visit(MethodDeclaration node) {
            // Resolve the binding for the current method declaration
            this.currentMethod = Optional.ofNullable(node.resolveBinding())
                    .map(JdtAnalyzer.this::convert)
                    .orElse(convert(node));
            logger.debug("Parsed method declaration: {}", this.currentMethod);
            callGraph.put(this.currentMethod, new HashSet<>());
            return super.visit(node);
        }

        // After visiting the method, clear the context
        @Override
        public void endVisit(MethodDeclaration node) {
            this.currentMethod = null;
            super.endVisit(node);
        }

        // Visit a method invocation to find a call
        @Override
        public boolean visit(MethodInvocation node) {
            if (currentMethod != null) {
                logger.debug("Parsing method invocation: {}", node.getName());
                callGraph.computeIfAbsent(currentMethod, k -> new HashSet<>()).add(convert(node));
            } else {
                logger.warn("Parsing method invocation without method declaration in context: {}", node.getName());
            }
            return super.visit(node);
        }

        // Also visit constructor invocations
        @Override
        public boolean visit(ClassInstanceCreation node) {
            if (currentMethod != null) {
                logger.debug("Parsing class instance creation: {}", node.getType());
                callGraph.computeIfAbsent(currentMethod, k -> new HashSet<>()).add(convert(node));
            } else {
                logger.warn("Parsing class instance creation without method declaration in context: {}", node.getType());
            }
            return super.visit(node);
        }
    }

    @SuppressWarnings("unchecked")
    private Method convert(@NotNull MethodDeclaration methodDeclaration) {
        return Optional.ofNullable(methodDeclaration.resolveBinding())
                .map(this::convert)
                .orElse(new LibraryMethod(
                        methodDeclaration.getName().getIdentifier(),
                        Optional.ofNullable(methodDeclaration.getReturnType2())
                                .map(this::typeFullName)
                                .orElse(UNKNOWN),
                        signature(methodDeclaration.parameters()),
                        UNRESOLVED_NAMESPACE
                ));
    }

    private Method convert(@NotNull IMethodBinding methodBinding) {
        return new ApplicationMethod(
                methodBinding.getName(),
                methodBinding.getReturnType().getQualifiedName(),
                signature(methodBinding.getParameterTypes()),
                methodBinding.getDeclaringClass().getQualifiedName(),
                methodBinding.toString()
        );
    }

    @SuppressWarnings("unchecked")
    private Method convert(@NotNull ClassInstanceCreation instanceCreation) {
        return Optional.ofNullable(instanceCreation.resolveConstructorBinding())
                .map(this::convert)
                .orElse(new LibraryMethod(
                        INIT,
                        typeFullName(instanceCreation.getType()),
                        signature(instanceCreation.arguments()),
                        typeFullName(instanceCreation.getType())
                ));
    }

    @SuppressWarnings("unchecked")
    private Method convert(@NotNull MethodInvocation methodInvocation) {
        return Optional.ofNullable(methodInvocation.resolveMethodBinding())
                .map(this::convert)
                .orElse(new LibraryMethod(
                methodInvocation.getName().getIdentifier(),
                Optional.ofNullable(methodInvocation.getExpression())
                        .map(Expression::resolveTypeBinding)
                        .map(ITypeBinding::getQualifiedName)
                        .orElse(UNKNOWN),
                signature(methodInvocation.arguments()),
                UNRESOLVED_NAMESPACE
        ));
    }

    private String signature(ITypeBinding[] parameterTypes) {
        if (parameterTypes.length == 0) return VOID;
        else return Arrays.stream(parameterTypes)
                .map(ITypeBinding::getQualifiedName)
                .collect(Collectors.joining(","));
    }

    private String signature(List<? extends ASTNode> arguments) {
        if (arguments.isEmpty()) {
            return VOID;
        } else {
            return arguments.stream()
                    .map(node -> {
                        if (node instanceof Expression expr) {
                            ITypeBinding binding = expr.resolveTypeBinding();
                            return (binding != null) ? binding.getQualifiedName() : UNKNOWN;
                        } else if (node instanceof SingleVariableDeclaration svd) {
                            return typeFullName(svd.getType());
                        } else {
                            logger.warn("Unhandled ASTNode during signature creation: {}", node);
                            return UNKNOWN;
                        }
                    })
                    .collect(Collectors.joining(","));
        }
    }

    private String typeFullName(@NotNull Type type) {
        return Optional.ofNullable(type.resolveBinding()).map(ITypeBinding::getQualifiedName).orElse(UNKNOWN);
    }

    interface Method {
        @NotNull String name();

        @NotNull String declaringClassFullName();

        @NotNull String returnType();

        @NotNull String signature();

        @NotNull
        default String fullName(boolean withSignature) {
            if (withSignature) return String.format("%s.%s", declaringClassFullName(), name());
            else return String.format("%s.%s:%s(%s)", declaringClassFullName(), name(), returnType(), signature());
        }

        @NotNull
        default String fullName() {
            return fullName(false);
        }

        @NotNull
        default String code() {
            return String.format("%s %s(%s) { ... }", returnType(), name(), signature());
        }
    }

    record ApplicationMethod(@NotNull String name, @NotNull String returnType, @NotNull String signature,
                             @NotNull String declaringClassFullName, @NotNull String code) implements Method {

        @Override
        public int hashCode() {
            return Objects.hash(name, signature, declaringClassFullName);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ApplicationMethod other) {
                return Objects.equals(name, other.name) &&
                        Objects.equals(declaringClassFullName, other.declaringClassFullName) &&
                        Objects.equals(signature, other.signature);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("ApplicationMethod(%s)", fullName());
        }

    }

    record LibraryMethod(@NotNull String name, @NotNull String returnType, @NotNull String signature,
                         @NotNull String declaringClassFullName) implements Method {

        @Override
        public int hashCode() {
            return Objects.hash(name, signature, declaringClassFullName);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof LibraryMethod other) {
                return Objects.equals(name, other.name) &&
                        Objects.equals(declaringClassFullName, other.declaringClassFullName) &&
                        Objects.equals(signature, other.signature);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("LibraryMethod(%s)", fullName());
        }

    }

    @Override
    public void close() {
        // TODO: Implement?
    }

}
