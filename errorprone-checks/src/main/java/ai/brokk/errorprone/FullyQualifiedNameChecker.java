package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;

/**
 * Flags uses of fully qualified class names and suggests replacing them with simple names and imports.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "BrokkFullyQualifiedName",
        summary = "Use simple name with an import instead of a fully qualified name",
        explanation = "Avoid using raw, fully qualified class names unless necessary to disambiguate. "
                + "Using simple names with imports makes the code more readable.",
        severity = BugPattern.SeverityLevel.WARNING)
public final class FullyQualifiedNameChecker extends BugChecker implements BugChecker.MemberSelectTreeMatcher {

    @Override
    public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
        Symbol sym = ASTHelpers.getSymbol(tree);
        if (!(sym instanceof ClassSymbol classSymbol)) {
            return Description.NO_MATCH;
        }

        // If the parent is a MemberSelectTree, then 'tree' is just a qualifier
        // (e.g., 'java.util' in 'java.util.List'). We only want to flag the full FQCN.
        if (state.getPath().getParentPath().getLeaf() instanceof MemberSelectTree) {
            return Description.NO_MATCH;
        }

        // Verify the expression (the qualifier) is a package.
        // This ensures we are flagging a FQCN (e.g. java.util.List) and not
        // a nested class access (e.g. Map.Entry) or an instance method call.
        Symbol baseSym = ASTHelpers.getSymbol(tree.getExpression());
        if (!(baseSym instanceof Symbol.PackageSymbol)) {
            return Description.NO_MATCH;
        }

        String source = state.getSourceForNode(tree);
        if (source == null || !source.contains(".")) {
            return Description.NO_MATCH;
        }

        // System.err.println("Matching: " + source + " sym: " + classSymbol.getQualifiedName());

        // Collision check: if the simple name is already in scope and refers to something else,
        // we cannot suggest an import.
        String simpleName = classSymbol.getSimpleName().toString();
        Symbol existing = state.getSymbolFromString(simpleName);
        if (existing != null && !existing.equals(classSymbol)) {
            return Description.NO_MATCH;
        }

        String fqcn = classSymbol.getQualifiedName().toString();
        SuggestedFix.Builder fix = SuggestedFix.builder().replace(tree, simpleName);

        // java.lang classes don't need an explicit import, but their sub-packages (e.g. java.lang.reflect) do.
        boolean isBaseJavaLang = fqcn.startsWith("java.lang.") && fqcn.lastIndexOf('.') == 9;
        if (!isBaseJavaLang) {
            fix.addImport(fqcn);
        }

        return describeMatch(tree, fix.build());
    }
}
