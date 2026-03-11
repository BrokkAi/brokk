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
 * Flags compilation units whose raw source is empty or contains only whitespace.
 *
 * <p>We also keep a Gradle-level check for truly empty/whitespace-only {@code .java} files because Error Prone is a
 * compiler plugin: if {@code javac} doesn't include a file in the compilation, or doesn't provide retrievable source
 * for an effectively-empty compilation unit, Error Prone may never invoke {@link #matchCompilationUnit} for that file
 * (or may provide no usable source text). The Gradle check closes that gap by scanning source roots directly.
 *
 * <p>LLMs sometimes truncate files to a blank state instead of deleting them. This checker helps identify such cases
 * when the compiler provides the source text.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "BrokkEmptyFile",
        summary = "File is empty or contains only whitespace.",
        explanation = "LLMs sometimes truncate files to a blank state instead of deleting them. "
                + "This checker flags such instances when the compiler provides source text. "
                + "A Gradle-level scan is also recommended for truly empty files that may not be surfaced to "
                + "Error Prone.",
        severity = SeverityLevel.WARNING)
public final class EmptyFileChecker extends BugChecker implements BugChecker.CompilationUnitTreeMatcher {

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        if (tree instanceof JCCompilationUnit unit && unit.getSourceFile() != null) {
            String path = unit.getSourceFile().getName();
            if (path.endsWith("package-info.java")) {
                return Description.NO_MATCH;
            }
        }

        CharSequence sourceCode = state.getSourceForNode(tree);
        if (sourceCode == null) {
            sourceCode = state.getSourceCode();
        }
        if (sourceCode == null) {
            return Description.NO_MATCH;
        }

        if (sourceCode.toString().isBlank()) {
            return describeMatch(tree);
        }

        return Description.NO_MATCH;
    }
}
