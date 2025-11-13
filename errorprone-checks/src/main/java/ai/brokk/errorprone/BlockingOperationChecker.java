package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
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

    private static boolean hasDirectAnnotation(Symbol sym, String fqcn) {
        for (var mirror : sym.getAnnotationMirrors()) {
            var annType = mirror.getAnnotationType();
            var elt = annType.asElement();
            if (elt instanceof javax.lang.model.element.TypeElement te) {
                if (te.getQualifiedName().contentEquals(fqcn)) {
                    return true;
                }
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

        if (!hasDirectAnnotation(msym, BLOCKING_ANN_FQCN)) {
            return Description.NO_MATCH;
        }

        String message = String.format(
                "Referencing potentially blocking %s; prefer the corresponding computed*() non-blocking method.",
                msym.getSimpleName());

        return buildDescription(tree).setMessage(message).build();
    }
}
