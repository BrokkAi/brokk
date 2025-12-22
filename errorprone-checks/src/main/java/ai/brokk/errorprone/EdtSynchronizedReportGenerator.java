package ai.brokk.errorprone;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Generates a report of EDT methods that call synchronized methods.
 * Combines the synchronized call graph analysis with EDT context detection.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... EdtSynchronizedReportGenerator /path/to/classes synchronized-methods.txt [whitelist.txt]
 * </pre>
 *
 * <p>Outputs {@code edt-synchronized-violations.txt} with EDT methods that call synchronized.
 *
 * <p>The optional whitelist file can contain method signatures (one per line) to suppress,
 * useful for third-party code that can't be annotated with @SuppressWarnings.
 * Format: {@code fully.qualified.ClassName#methodName}
 */
public class EdtSynchronizedReportGenerator {

    private final Set<String> methodsCallingSynchronized;
    private final Set<String> directlySynchronizedMethods = new HashSet<>();
    private final Set<String> edtMethods = new HashSet<>();
    private final Map<String, String> edtReasons = new HashMap<>();
    private final Set<String> suppressedMethods = new HashSet<>();
    private final Set<String> suppressedClasses = new HashSet<>();

    // Call graph: method -> set of methods it calls
    private final Map<String, Set<String>> callGraph = new HashMap<>();

    // Track synthetic methods (lambdas) that should be treated as EDT
    private final Set<String> edtLambdaMethods = new HashSet<>();

    public EdtSynchronizedReportGenerator(Set<String> methodsCallingSynchronized) {
        this.methodsCallingSynchronized = methodsCallingSynchronized;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(
                    "Usage: EdtSynchronizedReportGenerator <classes-dir> <synchronized-methods.txt> [whitelist.txt]");
            System.exit(1);
        }

        Path classesDir = Paths.get(args[0]);
        Path syncMethodsFile = Paths.get(args[1]);
        Path whitelistFile = args.length > 2 ? Paths.get(args[2]) : null;

        // Load synchronized methods analysis
        Set<String> syncMethods = new HashSet<>();
        Set<String> directSync = new HashSet<>();
        loadSynchronizedMethods(syncMethodsFile, syncMethods, directSync);
        System.out.println("Loaded " + syncMethods.size() + " methods that call synchronized");
        System.out.println("Found " + directSync.size() + " directly synchronized methods");

        // Analyze EDT contexts
        EdtSynchronizedReportGenerator generator = new EdtSynchronizedReportGenerator(syncMethods);
        generator.directlySynchronizedMethods.addAll(directSync);

        // Load whitelist if provided
        if (whitelistFile != null && Files.exists(whitelistFile)) {
            generator.loadWhitelist(whitelistFile);
        }

        // First pass: build call graph and detect suppressions
        generator.analyzeDirectory(classesDir);

        // Recompute which methods call synchronized, treating suppressed methods as safe
        generator.recomputeMethodsCallingSynchronized();

        // Second pass: detect EDT violations using the updated call graph
        // Clear only method violations, keep the #<class> markers
        generator.edtMethods.removeIf(m -> !m.endsWith("#<class>"));
        generator.edtReasons.entrySet().removeIf(e -> !e.getKey().endsWith("#<class>"));
        generator.analyzeDirectory(classesDir);

        System.out.println("Detected " + generator.edtLambdaMethods.size() + " EDT lambda/callback methods");

        // Generate report
        generator.generateReport(Paths.get("edt-synchronized-violations.txt"));
    }

    private static void loadSynchronizedMethods(Path file, Set<String> allMethods, Set<String> directMethods)
            throws IOException {
        Files.lines(file)
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .forEach(line -> {
                    boolean isDirect = line.contains("[DIRECT]");
                    String method = line.split(" ")[0];
                    allMethods.add(method);
                    if (isDirect) {
                        directMethods.add(method);
                    }
                });
    }

    /**
     * Load whitelist of methods/classes to suppress from reporting.
     * Each line should be a method signature: fully.qualified.ClassName#methodName
     * Or a class suppression: fully.qualified.ClassName#<class>
     * Lines starting with # or blank lines are ignored.
     */
    private void loadWhitelist(Path file) throws IOException {
        int methodCount = 0;
        int classCount = 0;

        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.endsWith("#<class>")) {
                suppressedClasses.add(line);
                classCount++;
            } else {
                suppressedMethods.add(line);
                methodCount++;
            }
        }

        System.out.println("Loaded whitelist: " + methodCount + " methods, " + classCount + " classes");
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

    private void analyzeClassFile(Path classFile) throws IOException {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader cr = new ClassReader(is);
            cr.accept(new EdtDetectorVisitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    /**
     * Detects EDT contexts using patterns similar to our Error Prone checker.
     */
    private class EdtDetectorVisitor extends ClassVisitor {
        private String className;

        public EdtDetectorVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name.replace('/', '.');

            // Check if class implements EDT listener interfaces
            for (String iface : interfaces) {
                String ifaceName = iface.replace('/', '.');
                if (isEdtListenerInterface(ifaceName)) {
                    // All public methods in this class run on EDT
                    markClassAsEdt("implements " + ifaceName);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // Check for @SuppressWarnings at class level
            if (descriptor.equals("Ljava/lang/SuppressWarnings;")) {
                return new SuppressWarningsVisitor(className + "#<class>");
            }
            // Check for @EdtSafe at class level
            if (descriptor.equals("Lai/brokk/annotation/EdtSafe;")) {
                suppressedClasses.add(className + "#<class>");
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            String methodSig = className + "#" + name;

            return new MethodVisitor(Opcodes.ASM9) {
                // Look-ahead buffer for Runnable lambdas
                private String pendingRunnableLambda = null;

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    // Check for @SuppressWarnings at method level
                    if (descriptor.equals("Ljava/lang/SuppressWarnings;")) {
                        return new SuppressWarningsVisitor(methodSig);
                    }
                    // Check for @EdtSafe at method level
                    if (descriptor.equals("Lai/brokk/annotation/EdtSafe;")) {
                        suppressedMethods.add(methodSig);
                        if (methodSig.contains("putReserved")) {
                            System.out.println("DEBUG: Found @EdtSafe on " + methodSig);
                        }
                    }
                    return null;
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String methodName, String descriptor, boolean isInterface) {
                    String ownerClass = owner.replace('/', '.');
                    String calledMethod = ownerClass + "#" + methodName;

                    // Check if we have a pending Runnable lambda and this is invokeLater/invokeAndWait
                    if (pendingRunnableLambda != null) {
                        if (isEdtSchedulingMethod(ownerClass, methodName)) {
                            // This Runnable is being passed to invokeLater - mark it as EDT
                            edtLambdaMethods.add(pendingRunnableLambda);
                        }
                        // Clear the pending lambda (consumed or ignored)
                        pendingRunnableLambda = null;
                    }

                    // Build call graph for all method calls
                    callGraph.computeIfAbsent(methodSig, k -> new HashSet<>()).add(calledMethod);

                    // If we're in EDT context and this method calls synchronized
                    if (isMethodInEdtClass() || edtLambdaMethods.contains(methodSig)) {
                        // Only report if the called method uses synchronization AND is not suppressed
                        if (methodsCallingSynchronized.contains(calledMethod) && !isSuppressed(calledMethod)) {
                            String reason = edtLambdaMethods.contains(methodSig)
                                    ? "in EDT lambda/callback"
                                    : "in EDT listener class";
                            markEdtMethod(methodSig, reason + " -> calls " + calledMethod);

                            // Debug for BrokkDiffPanel violations
                            if (methodSig.contains("createAsyncDiffPanel")
                                    && calledMethod.contains("buildAndDisplayPanelOnEdt")) {
                                System.out.println("DEBUG VIOLATION: " + methodSig + " calls " + calledMethod);
                                System.out.println("  calledMethod in methodsCallingSynchronized: "
                                        + methodsCallingSynchronized.contains(calledMethod));
                                System.out.println("  calledMethod suppressed: " + isSuppressed(calledMethod));
                            }
                        }
                    }
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String name,
                        String descriptor,
                        Handle bootstrapMethodHandle,
                        Object... bootstrapMethodArguments) {
                    // Detect lambdas passed to EDT scheduling methods
                    // For lambda metafactory, the Handle is at arg[1], not arg[0]
                    // arg[0] = method signature (Type)
                    // arg[1] = method handle pointing to lambda implementation (Handle)
                    // arg[2] = enforced signature (Type)
                    if (bootstrapMethodArguments.length > 1 && bootstrapMethodArguments[1] instanceof Handle) {
                        Handle lambdaMethod = (Handle) bootstrapMethodArguments[1];
                        String lambdaOwner = lambdaMethod.getOwner().replace('/', '.');
                        String lambdaName = lambdaMethod.getName();
                        String lambdaSig = lambdaOwner + "#" + lambdaName;

                        if (name.equals("run")) {
                            // Special handling for Runnable lambdas - buffer and wait to see where it goes
                            // If next instruction is invokeLater, we'll mark it as EDT
                            // If next instruction is executor.submit, we'll ignore it
                            pendingRunnableLambda = lambdaSig;
                        } else if (isEdtFunctionalInterface(name, descriptor)) {
                            // Swing-specific listener lambdas are always EDT
                            edtLambdaMethods.add(lambdaSig);
                        }
                    }
                }

                @Override
                public void visitInsn(int opcode) {
                    // Clear pending lambda on any other instruction (edge case)
                    // This handles cases where lambda is stored in a variable
                    if (pendingRunnableLambda != null && opcode != Opcodes.NOP) {
                        pendingRunnableLambda = null;
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        private boolean isMethodInEdtClass() {
            return edtMethods.contains(className + "#<class>");
        }

        private void markClassAsEdt(String reason) {
            edtMethods.add(className + "#<class>");
            edtReasons.put(className + "#<class>", reason);
        }
    }

    /**
     * Visitor for @SuppressWarnings annotations.
     * Checks if the annotation contains "edt-synchronized" or "edt".
     */
    private class SuppressWarningsVisitor extends AnnotationVisitor {
        private final String target; // method or class signature

        public SuppressWarningsVisitor(String target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (name.equals("value")) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof String warning) {
                            // Support both "edt-synchronized" and "edt"
                            if (warning.equals("edt-synchronized") || warning.equals("edt")) {
                                if (target.endsWith("#<class>")) {
                                    suppressedClasses.add(target);
                                } else {
                                    suppressedMethods.add(target);
                                }
                            }
                        }
                    }
                };
            }
            return null;
        }
    }

    private boolean isSuppressed(String methodSig) {
        // Check if method is directly suppressed
        if (suppressedMethods.contains(methodSig)) {
            return true;
        }
        // Check if class is suppressed
        String classSig = methodSig.substring(0, methodSig.lastIndexOf('#')) + "#<class>";
        return suppressedClasses.contains(classSig);
    }

    private void markEdtMethod(String methodSig, String reason) {
        if (methodsCallingSynchronized.contains(methodSig)) {
            edtMethods.add(methodSig);
            edtReasons.put(methodSig, reason);
        }
    }

    /**
     * Recompute which methods call synchronized, treating @EdtSafe methods as firebreaks.
     * This ensures that methods calling @EdtSafe methods are not flagged as violations.
     */
    private void recomputeMethodsCallingSynchronized() {
        // Remove suppressed methods from the set
        int before = methodsCallingSynchronized.size();
        methodsCallingSynchronized.removeIf(this::isSuppressed);
        int after = methodsCallingSynchronized.size();

        System.out.println("Removed " + (before - after) + " suppressed methods from synchronized call graph");

        // Now recompute transitive closure, but stop at suppressed methods
        // Start with directly synchronized methods, EXCLUDING suppressed ones (firebreak)
        Set<String> recomputed = new HashSet<>(directlySynchronizedMethods);
        int beforeFilter = recomputed.size();
        recomputed.removeIf(this::isSuppressed);
        int afterFilter = recomputed.size();
        System.out.println(
                "Filtered " + (beforeFilter - afterFilter) + " suppressed methods from directly synchronized");

        // Check if putReserved was filtered
        boolean hadPutReserved = directlySynchronizedMethods.stream().anyMatch(m -> m.contains("putReserved"));
        boolean hasPutReserved = recomputed.stream().anyMatch(m -> m.contains("putReserved"));
        if (hadPutReserved) {
            System.out.println("DEBUG: putReserved in direct sync before filter: " + hadPutReserved);
            System.out.println("DEBUG: putReserved in recomputed after filter: " + hasPutReserved);
        }

        boolean changed = true;
        int iterations = 0;

        while (changed && iterations < 100) {
            changed = false;
            iterations++;

            // For each method in our call graph
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String caller = entry.getKey();

                // Skip if already in set or suppressed
                if (recomputed.contains(caller) || isSuppressed(caller)) {
                    continue;
                }

                // Check if it calls any synchronized methods (that aren't suppressed)
                for (String callee : entry.getValue()) {
                    if (recomputed.contains(callee) && !isSuppressed(callee)) {
                        recomputed.add(caller);
                        changed = true;
                        break;
                    }
                }
            }
        }

        // Update the set
        methodsCallingSynchronized.clear();
        methodsCallingSynchronized.addAll(recomputed);

        System.out.println(
                "Recomputed: " + methodsCallingSynchronized.size() + " methods call synchronized (after suppressions)");
    }

    /**
     * Find path from startMethod to a directly synchronized method.
     * Uses BFS to find shortest path.
     */
    private List<String> findPathToSynchronized(String startMethod) {
        Queue<List<String>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(startMethod));
        visited.add(startMethod);

        int explored = 0;
        final int MAX_EXPLORE = 10000; // Prevent infinite loops

        while (!queue.isEmpty() && explored < MAX_EXPLORE) {
            List<String> path = queue.poll();
            String currentMethod = path.get(path.size() - 1);
            explored++;

            if (explored <= 5) {
                System.out.println("    DEBUG BFS: Exploring " + currentMethod);
            }

            // Explore called methods
            Set<String> calledMethods = callGraph.get(currentMethod);
            if (explored <= 5) {
                System.out.println(
                        "    DEBUG BFS: Has " + (calledMethods != null ? calledMethods.size() : 0) + " calls");
            }
            if (calledMethods != null) {
                for (String calledMethod : calledMethods) {
                    if (!visited.contains(calledMethod)) {
                        visited.add(calledMethod);
                        List<String> newPath = new ArrayList<>(path);
                        newPath.add(calledMethod);

                        // Check if this is a directly synchronized method (and not suppressed)
                        if (directlySynchronizedMethods.contains(calledMethod) && !isSuppressed(calledMethod)) {
                            System.out.println("    DEBUG BFS: Found path after exploring " + explored + " methods");
                            return newPath;
                        }

                        // Continue searching if this method might lead to synchronized code
                        // NOTE: We trace through suppressed methods to find the actual sync method
                        if (methodsCallingSynchronized.contains(calledMethod)) {
                            queue.add(newPath);
                        }
                    }
                }
            }
        }

        System.out.println("    DEBUG BFS: No path found after exploring " + explored + " methods, queue empty: "
                + queue.isEmpty());

        return null; // No path found
    }

    private static boolean isEdtListenerInterface(String ifaceName) {
        return ifaceName.equals("java.awt.event.ActionListener")
                || ifaceName.equals("java.awt.event.MouseListener")
                || ifaceName.equals("java.awt.event.MouseMotionListener")
                || ifaceName.equals("java.awt.event.KeyListener")
                || ifaceName.equals("java.awt.event.WindowListener")
                || ifaceName.equals("java.awt.event.FocusListener")
                || ifaceName.equals("javax.swing.event.ListSelectionListener")
                || ifaceName.equals("javax.swing.event.ChangeListener")
                || ifaceName.equals("javax.swing.event.DocumentListener")
                || ifaceName.equals("javax.swing.event.ListDataListener")
                || ifaceName.equals("javax.swing.event.TreeSelectionListener")
                || ifaceName.equals("javax.swing.event.TableModelListener");
    }

    /**
     * Check if a functional interface name/descriptor indicates a Swing-specific EDT callback.
     * This detects lambdas for Swing listeners (ActionListener, ChangeListener, etc.).
     * Note: "run" (Runnable) is handled separately with look-ahead to distinguish
     * invokeLater (EDT) from executor.submit (background).
     */
    private boolean isEdtFunctionalInterface(String name, String descriptor) {
        // actionPerformed - from ActionListener
        // valueChanged - from ListSelectionListener
        // stateChanged - from ChangeListener
        // Mouse/Key event methods
        return name.equals("actionPerformed")
                || name.equals("valueChanged")
                || name.equals("stateChanged")
                || name.equals("mouseClicked")
                || name.equals("mousePressed")
                || name.equals("mouseReleased")
                || name.equals("mouseMoved")
                || name.equals("mouseDragged")
                || name.equals("keyPressed")
                || name.equals("keyReleased")
                || name.equals("keyTyped")
                || name.equals("windowOpened")
                || name.equals("windowClosing")
                || name.equals("focusGained")
                || name.equals("focusLost");
    }

    /**
     * Check if a method is an EDT scheduling method (invokeLater, invokeAndWait).
     */
    private boolean isEdtSchedulingMethod(String ownerClass, String methodName) {
        return (ownerClass.equals("javax.swing.SwingUtilities") || ownerClass.equals("java.awt.EventQueue"))
                && (methodName.equals("invokeLater") || methodName.equals("invokeAndWait"));
    }

    private void generateReport(Path outputFile) throws IOException {
        List<String> allViolations = new ArrayList<>(edtMethods);
        allViolations.removeIf(m -> m.endsWith("#<class>")); // Remove class markers

        // Separate suppressed and active violations
        List<String> suppressedViolations =
                allViolations.stream().filter(this::isSuppressed).sorted().toList();

        List<String> activeViolations =
                allViolations.stream().filter(v -> !isSuppressed(v)).sorted().toList();

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            writer.write("# EDT methods that call synchronized methods\n");
            writer.write("# These methods may cause UI freezes or deadlocks\n");
            writer.write("# Generated: " + new Date() + "\n");
            writer.write("# Found " + activeViolations.size() + " violations");
            writer.write(" (" + suppressedViolations.size() + " suppressed)\n\n");

            // Write active violations
            if (!activeViolations.isEmpty()) {
                writer.write("## Active Violations\n\n");
                for (String method : activeViolations) {
                    writer.write(method);
                    String reason = edtReasons.get(method);
                    if (reason != null) {
                        writer.write(" // " + reason);
                    }
                    writer.write("\n");

                    // Find and write full trace to synchronized method
                    List<String> trace = findPathToSynchronized(method);
                    if (trace != null && trace.size() > 1) {
                        writer.write("  Trace: ");
                        writer.write(String.join(" -> ", trace));
                        writer.write("\n");
                    }
                    writer.write("\n");
                }
            }

            // Write suppressed violations
            if (!suppressedViolations.isEmpty()) {
                writer.write("\n## Suppressed Violations (@SuppressWarnings)\n\n");
                for (String method : suppressedViolations) {
                    writer.write(method);
                    String reason = edtReasons.get(method);
                    if (reason != null) {
                        writer.write(" // " + reason);
                    }
                    writer.write(" [SUPPRESSED]\n");
                }
            }
        }

        System.out.println("\nEDT Violations Report:");
        System.out.println("======================");
        System.out.println("Found " + activeViolations.size() + " active violations");
        if (suppressedViolations.size() > 0) {
            System.out.println("Found " + suppressedViolations.size() + " suppressed violations");
        }
        System.out.println("Report written to: " + outputFile);

        if (activeViolations.size() > 0) {
            System.out.println("\nTop 10 violations:");
            activeViolations.stream().limit(10).forEach(v -> {
                System.out.println("  - " + v);
                String reason = edtReasons.get(v);
                if (reason != null) {
                    System.out.println("    Reason: " + reason);
                }
                // Show trace to synchronized method
                List<String> trace = findPathToSynchronized(v);
                if (trace != null && trace.size() > 1) {
                    System.out.println("    Trace: " + String.join(" -> ", trace));
                } else {
                    // Debug: why no trace?
                    Set<String> calls = callGraph.get(v);
                    System.out.println("    DEBUG: No trace found for " + v);
                    System.out.println("    DEBUG: Calls from this method: " + (calls != null ? calls.size() : 0));
                    if (calls != null && !calls.isEmpty()) {
                        System.out.println("    DEBUG: First few calls: "
                                + calls.stream().limit(3).toList());
                        // Check if any direct calls are synchronized
                        for (String call : calls) {
                            if (directlySynchronizedMethods.contains(call)) {
                                System.out.println("    DEBUG: FOUND directly synchronized: " + call);
                            }
                            if (methodsCallingSynchronized.contains(call)) {
                                System.out.println("    DEBUG: Calls sync (transitive): " + call);
                            }
                        }
                    }
                }
            });
        }
    }
}
