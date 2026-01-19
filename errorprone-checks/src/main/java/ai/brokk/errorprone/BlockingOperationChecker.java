package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import java.util.List;
import java.util.Map;

/**
 * Flags invocations of methods annotated with ai.brokk.annotations.Blocking
 * and recommends the corresponding computed (non-blocking) alternative.
 *
 * Safe calls:
 * - Calls that resolve to an override that is not annotated @Blocking (typically cheap overrides).
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "BrokkBlockingOperation",
        summary = "Potentially blocking operation on ContextFragment; prefer the computed non-blocking alternative",
        explanation = "This call may perform analyzer work or I/O. Prefer using the corresponding computed*() "
                + "non-blocking method or LoggingFuture call for short-lived tasks. For tasks long enough to be "
                + "noticeable by a user, consider using ContextManager.submitBackgroundTask.",
        severity = BugPattern.SeverityLevel.WARNING)
public final class BlockingOperationChecker extends BugChecker implements BugChecker.MethodInvocationTreeMatcher {

    private static final String BLOCKING_ANN_FQCN = "org.jetbrains.annotations.Blocking";
    private static final String SWING_UTILS_FQCN = "javax.swing.SwingUtilities";
    private static final String EVENT_QUEUE_FQCN = "java.awt.EventQueue";

    /**
     * Map of owner class FQCNs to method names that represent safe background contexts.
     * When a @Blocking call occurs within an argument to any of these methods, it is considered safe.
     *
     * <p>For interface types (like IContextManager), the check uses type hierarchy traversal
     * to match any implementing class. For final classes (like LoggingFuture), a direct FQCN match is used.
     */
    private static final Map<String, List<String>> SAFE_BACKGROUND_CONTEXTS = Map.of(
            "ai.brokk.IContextManager", List.of("submitBackgroundTask"),
            "ai.brokk.concurrent.LoggingFuture", List.of("supplyAsync", "supplyCallableAsync", "allOf", "anyOf"));

    private static boolean hasDirectAnnotation(Symbol sym, String fqcn) {
        for (var a : sym.getAnnotationMirrors()) {
            if (a.getAnnotationType().toString().equals(fqcn)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        MethodSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null) {
            return Description.NO_MATCH;
        }

        // Only flag methods explicitly annotated as blocking on this symbol
        if (!hasDirectAnnotation(sym, BLOCKING_ANN_FQCN)) {
            return Description.NO_MATCH;
        }

        // Do not warn if we are already inside a safe background context
        if (isWithinSafeBackgroundContext(state)) {
            return Description.NO_MATCH;
        }

        // Only warn when the @Blocking call occurs on the EDT contexts we care about
        if (!(isWithinInvokeLaterArgument(state) || isWithinTrueBranchOfEdtCheck(state))) {
            return Description.NO_MATCH;
        }

        String message = String.format(
                "Calling potentially blocking %s() on the EDT; move to a background thread using "
                        + "contextManager.submitBackgroundTask() or use the corresponding computed*() non-blocking method.",
                sym.getSimpleName());

        return buildDescription(tree).setMessage(message).build();
    }

    /**
     * Checks if the current node is within an argument to any method defined in SAFE_BACKGROUND_CONTEXTS.
     */
    private static boolean isWithinSafeBackgroundContext(VisitorState state) {
        Tree target = state.getPath().getLeaf();

        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();
            if (node instanceof MethodInvocationTree mit && isSafeBackgroundMethod(mit)) {
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
     * Checks if the method invocation matches any entry in SAFE_BACKGROUND_CONTEXTS.
     * For instance methods, uses type hierarchy traversal to match implementing classes.
     * For static methods, uses direct FQCN comparison.
     */
    private static boolean isSafeBackgroundMethod(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        if (ms == null) {
            return false;
        }

        String methodName = ms.getSimpleName().toString();
        if (!(ms.owner instanceof Symbol.ClassSymbol ownerClass)) {
            return false;
        }

        for (var entry : SAFE_BACKGROUND_CONTEXTS.entrySet()) {
            String targetFqcn = entry.getKey();
            List<String> methods = entry.getValue();

            if (!methods.contains(methodName)) {
                continue;
            }

            // Check if owner matches directly or via type hierarchy
            if (ownerClass.getQualifiedName().contentEquals(targetFqcn)) {
                return true;
            }
            if (TypeHierarchyUtils.implementsOrExtends(ownerClass, targetFqcn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithinInvokeLaterArgument(VisitorState state) {
        // The node being analyzed (e.g., the @Blocking method invocation)
        Tree target = state.getPath().getLeaf();

        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();
            if (node instanceof MethodInvocationTree mit && isSwingInvokeLater(mit)) {
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

    private static boolean isSwingInvokeLater(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        return ms != null && ms.getSimpleName().contentEquals("invokeLater") && isEdtOwner(ms.owner);
    }

    private static boolean isSwingIsEventDispatchThread(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        if (ms == null) {
            return false;
        }
        var name = ms.getSimpleName().toString();
        // SwingUtilities: isEventDispatchThread(); EventQueue: isDispatchThread()
        boolean edtMethod = name.equals("isEventDispatchThread") || name.equals("isDispatchThread");
        if (!edtMethod) {
            return false;
        }
        Symbol owner = ms.owner;
        if (!(owner instanceof Symbol.ClassSymbol cs)) {
            return false;
        }
        var qn = cs.getQualifiedName().toString();
        if (qn.equals(SWING_UTILS_FQCN) || qn.equals(EVENT_QUEUE_FQCN)) {
            return true;
        }
        // Fallback to simple-name match to be tolerant of unusual owner qualification scenarios
        var sn = cs.getSimpleName().toString();
        return sn.equals("SwingUtilities") || sn.equals("EventQueue");
    }

    private static boolean isEdtOwner(Symbol owner) {
        if (!(owner instanceof Symbol.ClassSymbol cs)) {
            return false;
        }
        var qn = cs.getQualifiedName().toString();
        return qn.equals(SWING_UTILS_FQCN) || qn.equals(EVENT_QUEUE_FQCN);
    }

    private static boolean isWithinTrueBranchOfEdtCheck(VisitorState state) {
        // Walk up the TreePath, remembering the immediate child under each IfTree.
        // When we see an IfTree whose condition calls an EDT check, return true if the previously
        // visited node is the 'then' branch (or inside it).
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

    private static boolean conditionContainsEdtCheck(Tree condition) {
        if (condition == null) {
            return false;
        }
        Boolean found = new TreeScanner<Boolean, Void>() {
            @Override
            public Boolean visitMethodInvocation(MethodInvocationTree node, Void p) {
                if (isSwingIsEventDispatchThread(node)) {
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
}
