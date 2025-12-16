package ai.brokk.errorprone;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

/**
 * Analyzes bytecode to build a call graph and identify all methods that transitively
 * call synchronized methods. This can be run as a standalone tool or Gradle task.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... SynchronizedCallGraphAnalyzer /path/to/classes /path/to/dependencies/*.jar
 * </pre>
 *
 * <p>Outputs a file {@code synchronized-methods.txt} containing all methods (including indirect)
 * that call synchronized methods. Format: fully.qualified.ClassName#methodName
 */
public class SynchronizedCallGraphAnalyzer {

    // Maps method signature -> set of methods it calls
    private final Map<String, Set<String>> callGraph = new HashMap<>();

    // Direct synchronized methods
    private final Set<String> directSynchronized = new HashSet<>();

    // All methods that transitively call synchronized (computed)
    private Set<String> transitiveSynchronized;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: SynchronizedCallGraphAnalyzer <classpath-entries...>");
            System.err.println("  Analyzes .class files and JARs to find all methods that call synchronized methods");
            System.exit(1);
        }

        SynchronizedCallGraphAnalyzer analyzer = new SynchronizedCallGraphAnalyzer();

        // Analyze all provided class files and JARs
        for (String path : args) {
            Path p = Paths.get(path);
            if (Files.isDirectory(p)) {
                analyzer.analyzeDirectory(p);
            } else if (path.endsWith(".jar")) {
                analyzer.analyzeJar(p);
            } else if (path.endsWith(".class")) {
                analyzer.analyzeClassFile(p);
            }
        }

        // Compute transitive closure
        analyzer.computeTransitiveClosure();

        // Output results
        analyzer.writeResults(Paths.get("synchronized-methods.txt"));

        System.out.printf("Found %d direct synchronized methods%n", analyzer.directSynchronized.size());
        System.out.printf(
                "Found %d methods that transitively call synchronized%n", analyzer.transitiveSynchronized.size());
    }

    private void analyzeDirectory(Path dir) throws IOException {
        Files.walk(dir).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
            try {
                analyzeClassFile(p);
            } catch (IOException e) {
                System.err.println("Error analyzing " + p + ": " + e.getMessage());
            }
        });
    }

    private void analyzeJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        analyzeClassStream(is);
                    } catch (Exception e) {
                        // Ignore errors for individual classes (e.g., unsupported bytecode)
                    }
                }
            }
        }
    }

    private void analyzeClassFile(Path classFile) throws IOException {
        try (InputStream is = Files.newInputStream(classFile)) {
            analyzeClassStream(is);
        }
    }

    private void analyzeClassStream(InputStream is) throws IOException {
        ClassReader cr = new ClassReader(is);
        cr.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    private String className;

                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        this.className = name.replace('/', '.');
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        String methodSig = className + "#" + name;

                        // Check if this method is synchronized
                        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                            directSynchronized.add(methodSig);
                        }

                        // Track calls made by this method
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                String calledMethod = owner.replace('/', '.') + "#" + name;
                                callGraph
                                        .computeIfAbsent(methodSig, k -> new HashSet<>())
                                        .add(calledMethod);
                            }

                            @Override
                            public void visitCode() {
                                // Check for synchronized blocks (monitorenter/monitorexit)
                                // For simplicity, we'll just mark the method if it contains any synchronized code
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.MONITORENTER) {
                                    // Method contains synchronized block
                                    directSynchronized.add(methodSig);
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * Compute transitive closure: all methods that call synchronized methods (directly or indirectly).
     */
    private void computeTransitiveClosure() {
        transitiveSynchronized = new HashSet<>(directSynchronized);
        boolean changed = true;

        // Iteratively expand the set until no more changes
        while (changed) {
            changed = false;
            Set<String> newSynchronized = new HashSet<>();

            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String caller = entry.getKey();
                Set<String> callees = entry.getValue();

                // If any callee is synchronized (transitively), mark caller as synchronized
                for (String callee : callees) {
                    if (transitiveSynchronized.contains(callee) && transitiveSynchronized.add(caller)) {
                        newSynchronized.add(caller);
                        changed = true;
                    }
                }
            }
        }

        System.out.println("Computed transitive closure with " + transitiveSynchronized.size() + " methods");
    }

    private void writeResults(Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            writer.write("# Methods that call synchronized methods (directly or indirectly)\n");
            writer.write("# Format: fully.qualified.ClassName#methodName\n");
            writer.write("# Generated: " + new Date() + "\n\n");

            List<String> sorted = new ArrayList<>(transitiveSynchronized);
            Collections.sort(sorted);

            for (String method : sorted) {
                writer.write(method);
                if (directSynchronized.contains(method)) {
                    writer.write(" [DIRECT]");
                }
                writer.write("\n");
            }
        }

        System.out.println("Results written to " + outputFile);
    }

    /**
     * Check if a method signature calls synchronized methods (for use by Error Prone).
     */
    public boolean callsSynchronized(String methodSignature) {
        ensureComputed();
        return transitiveSynchronized.contains(methodSignature);
    }

    private void ensureComputed() {
        if (transitiveSynchronized == null) {
            throw new IllegalStateException("Must call computeTransitiveClosure() first");
        }
    }

    /**
     * Load pre-computed results from a file.
     */
    public static SynchronizedCallGraphAnalyzer loadResults(Path resultsFile) throws IOException {
        SynchronizedCallGraphAnalyzer analyzer = new SynchronizedCallGraphAnalyzer();
        analyzer.transitiveSynchronized = new HashSet<>();

        Files.lines(resultsFile)
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .forEach(line -> {
                    String method = line.split(" ")[0]; // Remove [DIRECT] marker if present
                    analyzer.transitiveSynchronized.add(method);
                    if (line.contains("[DIRECT]")) {
                        analyzer.directSynchronized.add(method);
                    }
                });

        return analyzer;
    }
}
