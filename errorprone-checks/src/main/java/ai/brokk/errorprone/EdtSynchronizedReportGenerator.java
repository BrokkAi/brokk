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
 * java -cp ... EdtSynchronizedReportGenerator /path/to/classes synchronized-methods.txt
 * </pre>
 *
 * <p>Outputs {@code edt-synchronized-violations.txt} with EDT methods that call synchronized.
 */
public class EdtSynchronizedReportGenerator {

    private final Set<String> methodsCallingSynchronized;
    private final Set<String> edtMethods = new HashSet<>();
    private final Map<String, String> edtReasons = new HashMap<>();

    public EdtSynchronizedReportGenerator(Set<String> methodsCallingSynchronized) {
        this.methodsCallingSynchronized = methodsCallingSynchronized;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: EdtSynchronizedReportGenerator <classes-dir> <synchronized-methods.txt>");
            System.exit(1);
        }

        Path classesDir = Paths.get(args[0]);
        Path syncMethodsFile = Paths.get(args[1]);

        // Load synchronized methods analysis
        Set<String> syncMethods = loadSynchronizedMethods(syncMethodsFile);
        System.out.println("Loaded " + syncMethods.size() + " methods that call synchronized");

        // Analyze EDT contexts
        EdtSynchronizedReportGenerator generator = new EdtSynchronizedReportGenerator(syncMethods);
        generator.analyzeDirectory(classesDir);

        // Generate report
        generator.generateReport(Paths.get("edt-synchronized-violations.txt"));
    }

    private static Set<String> loadSynchronizedMethods(Path file) throws IOException {
        Set<String> methods = new HashSet<>();
        Files.lines(file)
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .forEach(line -> {
                    String method = line.split(" ")[0]; // Remove [DIRECT] marker
                    methods.add(method);
                });
        return methods;
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
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            String methodSig = className + "#" + name;

            return new MethodVisitor(Opcodes.ASM9) {
                private boolean inEdtContext = false;
                private String edtReason = null;

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String methodName, String descriptor, boolean isInterface) {
                    String ownerClass = owner.replace('/', '.');

                    // Check for SwingUtilities.invokeLater or EventQueue.invokeLater
                    if ((ownerClass.equals("javax.swing.SwingUtilities") || ownerClass.equals("java.awt.EventQueue"))
                            && methodName.equals("invokeLater")) {
                        inEdtContext = true;
                        edtReason = "inside invokeLater";
                    }

                    // If we're in EDT context and this method calls synchronized
                    if (inEdtContext || isMethodInEdtClass()) {
                        String calledMethod = ownerClass + "#" + methodName;
                        if (methodsCallingSynchronized.contains(calledMethod)) {
                            String reason = edtReason != null
                                    ? edtReason
                                    : (isMethodInEdtClass() ? "in EDT listener class" : "unknown");
                            markEdtMethod(methodSig, reason + " -> calls " + calledMethod);
                        }
                    }
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

    private void markEdtMethod(String methodSig, String reason) {
        if (methodsCallingSynchronized.contains(methodSig)) {
            edtMethods.add(methodSig);
            edtReasons.put(methodSig, reason);
        }
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

    private void generateReport(Path outputFile) throws IOException {
        List<String> violations = new ArrayList<>(edtMethods);
        violations.removeIf(m -> m.endsWith("#<class>")); // Remove class markers
        Collections.sort(violations);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            writer.write("# EDT methods that call synchronized methods\n");
            writer.write("# These methods may cause UI freezes or deadlocks\n");
            writer.write("# Generated: " + new Date() + "\n");
            writer.write("# Found " + violations.size() + " violations\n\n");

            for (String method : violations) {
                writer.write(method);
                String reason = edtReasons.get(method);
                if (reason != null) {
                    writer.write(" // " + reason);
                }
                writer.write("\n");
            }
        }

        System.out.println("\nEDT Violations Report:");
        System.out.println("======================");
        System.out.println("Found " + violations.size() + " EDT methods that call synchronized");
        System.out.println("Report written to: " + outputFile);

        if (violations.size() > 0) {
            System.out.println("\nTop 10 violations:");
            violations.stream().limit(10).forEach(v -> {
                System.out.println("  - " + v);
                String reason = edtReasons.get(v);
                if (reason != null) {
                    System.out.println("    Reason: " + reason);
                }
            });
        }
    }
}
