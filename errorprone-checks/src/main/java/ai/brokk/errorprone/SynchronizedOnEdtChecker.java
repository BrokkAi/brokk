package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * Detects potentially problematic calls to synchronized methods from EDT (Event Dispatch Thread) contexts.
 *
 * <p>Calling synchronized methods from the EDT can cause UI freezes if:
 * <ul>
 *   <li>The synchronized method performs blocking operations (I/O, waiting, etc.)</li>
 *   <li>Another thread holds the lock and tries to update UI (deadlock)</li>
 *   <li>The synchronized block takes significant time to execute</li>
 * </ul>
 *
 * <p>This checker flags calls to synchronized methods when they occur in EDT contexts:
 * <ul>
 *   <li>Inside {@code SwingUtilities.invokeLater()} or {@code EventQueue.invokeLater()}</li>
 *   <li>Inside the "then" branch of {@code if (SwingUtilities.isEventDispatchThread())}</li>
 *   <li>Inside Swing event listeners (e.g., ActionListener, MouseListener)</li>
 * </ul>
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "SynchronizedOnEdt",
        summary = "Synchronized method called from EDT may cause UI freeze or deadlock",
        explanation = "Calling synchronized methods from the Event Dispatch Thread (EDT) can cause UI freezes "
                + "if the synchronized method performs blocking operations or if another thread holds the lock. "
                + "Consider using lock-free data structures, moving the synchronized logic to a background thread, "
                + "or redesigning to avoid holding locks while on the EDT.",
        severity = BugPattern.SeverityLevel.SUGGESTION)
public final class SynchronizedOnEdtChecker extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher, BugChecker.MethodTreeMatcher {

    private static final String SWING_UTILS_FQCN = "javax.swing.SwingUtilities";
    private static final String EVENT_QUEUE_FQCN = "java.awt.EventQueue";

    // Cache methods that call synchronized methods (per compilation unit)
    private final Set<MethodSymbol> methodsThatCallSynchronized = new HashSet<>();

    // Pre-computed transitive synchronized methods (loaded from file if available)
    private static Set<String> transitiveSynchronizedMethods = null;

    static {
        // Try to load pre-computed analysis - try multiple possible locations
        String[] possiblePaths = {
            "synchronized-methods.txt", // Working directory
            "errorprone-checks/synchronized-methods.txt", // From project root
            "../errorprone-checks/synchronized-methods.txt" // From app module
        };

        for (String pathStr : possiblePaths) {
            try {
                java.nio.file.Path analysisFile = java.nio.file.Paths.get(pathStr);
                if (java.nio.file.Files.exists(analysisFile)) {
                    transitiveSynchronizedMethods = new java.util.HashSet<>();
                    java.nio.file.Files.lines(analysisFile)
                            .filter(line -> !line.startsWith("#") && !line.isBlank())
                            .forEach(line -> {
                                String method = line.split(" ")[0];
                                transitiveSynchronizedMethods.add(method);
                            });
                    System.err.println("SynchronizedOnEdtChecker: Loaded " + transitiveSynchronizedMethods.size()
                            + " methods from "
                            + analysisFile);
                    break; // Found it, stop looking
                }
            } catch (Exception e) {
                // Try next path
            }
        }

        if (transitiveSynchronizedMethods == null) {
            System.err.println(
                    "SynchronizedOnEdtChecker: Pre-computed analysis not found, using direct+1-level analysis only");
        }
    }

    // Common Swing listener interfaces that run on EDT
    private static final Set<String> EDT_LISTENER_INTERFACES = Set.of(
            "java.awt.event.ActionListener",
            "java.awt.event.MouseListener",
            "java.awt.event.MouseMotionListener",
            "java.awt.event.KeyListener",
            "java.awt.event.WindowListener",
            "java.awt.event.FocusListener",
            "javax.swing.event.ListSelectionListener",
            "javax.swing.event.ChangeListener",
            "javax.swing.event.DocumentListener",
            "javax.swing.event.ListDataListener",
            "javax.swing.event.TreeSelectionListener",
            "javax.swing.event.TableModelListener");

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        MethodSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null) {
            return Description.NO_MATCH;
        }

        // Only warn when the call occurs in EDT contexts
        if (!isInEdtContext(state)) {
            return Description.NO_MATCH;
        }

        // Check if the called method is directly synchronized
        if (sym.getModifiers().contains(Modifier.SYNCHRONIZED)) {
            return reportSynchronizedCall(tree, sym, "directly");
        }

        // If we have transitive analysis, use it (unlimited depth)
        if (transitiveSynchronizedMethods != null) {
            String methodSig = getMethodSignature(sym);
            if (transitiveSynchronizedMethods.contains(methodSig)) {
                return reportSynchronizedCall(tree, sym, "transitively");
            }
        }

        // Otherwise fall back to 1-level indirect analysis
        if (callsSynchronizedMethod(sym)) {
            return reportSynchronizedCall(tree, sym, "indirectly");
        }

        return Description.NO_MATCH;
    }

    private Description reportSynchronizedCall(MethodInvocationTree tree, MethodSymbol sym, String manner) {
        String methodName = sym.getSimpleName().toString();
        String className = sym.owner.getSimpleName().toString();

        String message = String.format(
                "Method %s.%s() %s calls synchronized method from EDT and may cause UI freeze or deadlock. "
                        + "Consider moving to background thread or using lock-free design.",
                className, methodName, manner);

        return buildDescription(tree).setMessage(message).build();
    }

    /**
     * Get method signature in the format: fully.qualified.ClassName#methodName
     */
    private static String getMethodSignature(MethodSymbol sym) {
        String className = sym.owner.getQualifiedName().toString();
        String methodName = sym.getSimpleName().toString();
        return className + "#" + methodName;
    }

    /**
     * First pass: Build a cache of methods that call synchronized methods.
     * This is called for every method in the compilation unit.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        MethodSymbol method = ASTHelpers.getSymbol(tree);
        if (method == null || tree.getBody() == null) {
            return Description.NO_MATCH;
        }

        // Scan this method's body for calls to synchronized methods
        Boolean callsSync = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitMethodInvocation(MethodInvocationTree node, Void p) {
                MethodSymbol invoked = ASTHelpers.getSymbol(node);
                if (invoked != null && invoked.getModifiers().contains(Modifier.SYNCHRONIZED)) {
                    return true;
                }
                return super.visitMethodInvocation(node, p);
            }

            @Override
            public Boolean reduce(Boolean r1, Boolean r2) {
                return Boolean.TRUE.equals(r1) || Boolean.TRUE.equals(r2);
            }
        }.scan(tree.getBody(), null);

        if (Boolean.TRUE.equals(callsSync)) {
            methodsThatCallSynchronized.add(method);
        }

        return Description.NO_MATCH; // We don't report anything in the first pass
    }

    /**
     * Checks if a method calls any synchronized method (using our cache).
     */
    private boolean callsSynchronizedMethod(MethodSymbol method) {
        return methodsThatCallSynchronized.contains(method);
    }

    /**
     * Determines if the current code is executing in an EDT context.
     */
    private static boolean isInEdtContext(VisitorState state) {
        return isWithinInvokeLaterArgument(state)
                || isWithinTrueBranchOfEdtCheck(state)
                || isWithinEventListenerMethod(state);
    }

    /**
     * Checks if we're inside an argument to SwingUtilities.invokeLater() or EventQueue.invokeLater().
     */
    private static boolean isWithinInvokeLaterArgument(VisitorState state) {
        Tree target = state.getPath().getLeaf();

        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();
            if (node instanceof MethodInvocationTree mit && isEdtInvokeLater(mit)) {
                // Check whether the target node is within any of the method arguments
                for (Tree arg : mit.getArguments()) {
                    if (containsTree(arg, target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if we're inside the "then" branch of an {@code if (SwingUtilities.isEventDispatchThread())} check.
     */
    private static boolean isWithinTrueBranchOfEdtCheck(VisitorState state) {
        Tree prev = null;
        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();
            if (node instanceof IfTree ift) {
                if (conditionContainsEdtCheck(ift.getCondition())) {
                    Tree thenStmt = ift.getThenStatement();
                    if (prev == thenStmt || (prev != null && containsTree(thenStmt, prev))) {
                        return true;
                    }
                }
            }
            prev = node;
        }
        return false;
    }

    /**
     * Checks if we're inside a method that implements a Swing event listener interface.
     */
    private static boolean isWithinEventListenerMethod(VisitorState state) {
        // Walk up to find the enclosing method
        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();

            // Check if we're in a lambda expression that's passed to an event listener registration
            if (node instanceof LambdaExpressionTree) {
                // Look for patterns like: button.addActionListener(e -> ...)
                TreePath parent = path.getParentPath();
                if (parent != null && parent.getLeaf() instanceof MethodInvocationTree mit) {
                    MethodSymbol ms = ASTHelpers.getSymbol(mit);
                    if (ms != null && isEventListenerRegistration(ms)) {
                        return true;
                    }
                }
            }

            // Check if we're in a method that overrides a listener interface method
            if (node instanceof MethodTree methodTree) {
                MethodSymbol methodSym = ASTHelpers.getSymbol(methodTree);
                if (methodSym != null && isEventListenerMethod(methodSym)) {
                    return true;
                }
            }

            // Check if we're in a method of an anonymous class implementing a listener
            if (node instanceof ClassTree classTree) {
                ClassSymbol classSym = ASTHelpers.getSymbol(classTree);
                if (classSym != null && implementsListenerInterface(classSym)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a method is an event listener registration (e.g., addActionListener).
     */
    private static boolean isEventListenerRegistration(MethodSymbol method) {
        String name = method.getSimpleName().toString();
        return name.startsWith("add") && name.endsWith("Listener");
    }

    /**
     * Checks if a method overrides a Swing event listener interface method.
     */
    private static boolean isEventListenerMethod(MethodSymbol method) {
        // Check if the enclosing class implements a listener interface
        ClassSymbol enclosingClass = method.enclClass();
        if (enclosingClass == null) {
            return false;
        }
        return implementsListenerInterface(enclosingClass);
    }

    /**
     * Checks if a class implements any Swing event listener interface.
     */
    private static boolean implementsListenerInterface(ClassSymbol classSym) {
        // Check all interfaces implemented by this class
        for (var iface : classSym.getInterfaces()) {
            String ifaceName = iface.tsym.getQualifiedName().toString();
            if (EDT_LISTENER_INTERFACES.contains(ifaceName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEdtInvokeLater(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        return ms != null && ms.getSimpleName().contentEquals("invokeLater") && isEdtOwner(ms.owner);
    }

    private static boolean conditionContainsEdtCheck(Tree condition) {
        if (condition == null) {
            return false;
        }
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitMethodInvocation(MethodInvocationTree node, Void p) {
                if (isEdtCheckMethod(node)) {
                    return true;
                }
                return super.visitMethodInvocation(node, p);
            }

            @Override
            public Boolean reduce(Boolean r1, Boolean r2) {
                return Boolean.TRUE.equals(r1) || Boolean.TRUE.equals(r2);
            }
        }.scan(condition, null);
        return Boolean.TRUE.equals(found);
    }

    private static boolean isEdtCheckMethod(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        if (ms == null) {
            return false;
        }
        String name = ms.getSimpleName().toString();
        // SwingUtilities: isEventDispatchThread(); EventQueue: isDispatchThread()
        boolean edtMethod = name.equals("isEventDispatchThread") || name.equals("isDispatchThread");
        if (!edtMethod) {
            return false;
        }
        return isEdtOwner(ms.owner);
    }

    private static boolean isEdtOwner(Symbol owner) {
        if (!(owner instanceof ClassSymbol cs)) {
            return false;
        }
        String qn = cs.getQualifiedName().toString();
        return qn.equals(SWING_UTILS_FQCN) || qn.equals(EVENT_QUEUE_FQCN);
    }

    private static boolean containsTree(Tree root, Tree target) {
        if (root == null || target == null) {
            return false;
        }
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean scan(Tree node, Void p) {
                if (node == target) {
                    return true;
                }
                return super.scan(node, p);
            }

            @Override
            public Boolean reduce(Boolean r1, Boolean r2) {
                return Boolean.TRUE.equals(r1) || Boolean.TRUE.equals(r2);
            }
        }.scan(root, null);
        return Boolean.TRUE.equals(found);
    }
}
