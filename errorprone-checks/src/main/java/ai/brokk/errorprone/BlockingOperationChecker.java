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
        explanation =
                "This call may perform analyzer work or I/O. Prefer using the corresponding computed*() "
                        + "non-blocking method (e.g., computedFiles(), computedSources(), computedText(), computedDescription(), computedSyntaxStyle()).",
        severity = BugPattern.SeverityLevel.WARNING)
public final class BlockingOperationChecker extends BugChecker implements BugChecker.MethodInvocationTreeMatcher {

    private static final String BLOCKING_ANN_FQCN = "org.jetbrains.annotations.Blocking";
    private static final String SWING_UTILS_FQCN = "javax.swing.SwingUtilities";
    private static final String EVENT_QUEUE_FQCN = "java.awt.EventQueue";

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

        // Only warn when the @Blocking call occurs on the EDT contexts we care about
        if (!(isWithinInvokeLaterArgument(state) || isWithinTrueBranchOfEdtCheck(state))) {
            return Description.NO_MATCH;
        }

        String message = String.format(
                "Calling potentially blocking %s(); prefer the corresponding computed*() non-blocking method.",
                sym.getSimpleName());

        return buildDescription(tree).setMessage(message).build();
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
