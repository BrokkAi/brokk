package ai.brokk.errorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;

/**
 * Flags files that are empty or contain only whitespace.
 *
 * <p>LLMs sometimes truncate files to a blank state instead of deleting them.
 * This checker helps identify such cases.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "BrokkEmptyFile",
        summary = "File is empty or contains only whitespace.",
        explanation = "LLMs sometimes truncate files to a blank state instead of deleting them. "
                + "This checker flags such instances.",
        severity = SeverityLevel.WARNING)
public final class EmptyFileChecker extends BugChecker implements BugChecker.CompilationUnitTreeMatcher {

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        if (tree instanceof JCCompilationUnit unit && unit.getSourceFile() != null) {
            String path = unit.getSourceFile().getName();
            // We ignore package-info.java as it is often empty or just contains a package declaration
            // which might be stripped or handled differently by the parser in edge cases.
            if (path.endsWith("package-info.java")) {
                return Description.NO_MATCH;
            }
        }

        CharSequence sourceCode = state.getSourceCode();
        if (sourceCode == null) {
            return Description.NO_MATCH;
        }

        String source = sourceCode.toString();
        if (source.isBlank()) {
            return describeMatch(tree);
        }

        return Description.NO_MATCH;
    }
}
