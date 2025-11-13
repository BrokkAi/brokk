package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberReferenceTree;
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
public final class BlockingOperationChecker extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher, BugChecker.MemberReferenceTreeMatcher {

    private static final String BLOCKING_ANN_FQCN = "org.jetbrains.annotations.Blocking";
    private static final String SWING_UTILS_FQCN = "javax.swing.SwingUtilities";

    private static boolean hasDirectAnnotation(Symbol sym, String fqcn, VisitorState state) {
        return ASTHelpers.hasAnnotation(sym, fqcn, state);
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        MethodSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null) {
            return Description.NO_MATCH;
        }

        // Only flag methods explicitly annotated as blocking on this symbol
        if (!hasDirectAnnotation(sym, BLOCKING_ANN_FQCN, state)) {
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

    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
        Symbol sym = ASTHelpers.getSymbol(tree);
        if (!(sym instanceof MethodSymbol msym)) {
            return Description.NO_MATCH;
        }

        if (!hasDirectAnnotation(msym, BLOCKING_ANN_FQCN, state)) {
            return Description.NO_MATCH;
        }

        String message = String.format(
                "Referencing potentially blocking %s; prefer the corresponding computed*() non-blocking method.",
                msym.getSimpleName());

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
        return ms != null
                && ms.getSimpleName().contentEquals("invokeLater")
                && isSwingUtilitiesOwner(ms.owner);
    }

    private static boolean isSwingIsEventDispatchThread(MethodInvocationTree mit) {
        MethodSymbol ms = ASTHelpers.getSymbol(mit);
        return ms != null
                && ms.getSimpleName().contentEquals("isEventDispatchThread")
                && isSwingUtilitiesOwner(ms.owner);
    }

    private static boolean isSwingUtilitiesOwner(Symbol owner) {
        if (!(owner instanceof Symbol.ClassSymbol cs)) {
            return false;
        }
        return cs.getQualifiedName().contentEquals(SWING_UTILS_FQCN);
    }

    private static boolean isWithinTrueBranchOfEdtCheck(VisitorState state) {
        // The node being analyzed (e.g., the @Blocking method invocation)
        Tree target = state.getPath().getLeaf();

        for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
            Tree node = path.getLeaf();
            if (node instanceof IfTree ift) {
                // If the if-condition is SwingUtilities.isEventDispatchThread(), ensure the target node
                // is within the 'then' branch subtree.
                if (ift.getCondition() instanceof MethodInvocationTree cond && isSwingIsEventDispatchThread(cond)) {
                    Tree thenStmt = ift.getThenStatement();
                    if (containsTree(thenStmt, target)) {
                        return true;
                    }
                }
            }
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
}
