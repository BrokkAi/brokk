package ai.brokk.analyzer.scala;

import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ScalaAnalyzer;
import ai.brokk.analyzer.TreeSitterStateIO;
import ai.brokk.project.IProject;
import java.util.Set;

public class ScalaLanguage implements Language {

    private final Set<String> extensions = Set.of("scala");

    @Override
    public Set<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Scala";
    }

    @Override
    public String internalName() {
        return "SCALA";
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        return new ScalaAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> (IAnalyzer) ScalaAnalyzer.fromState(project, state, listener))
                .orElseGet(() -> createAnalyzer(project, listener));
    }

    @Override
    public Set<String> getSearchPatterns(CodeUnitType type) {
        if (type == CodeUnitType.FUNCTION) {
            return Set.of(
                    "\\b$ident\\s*\\(", // function/method calls
                    "\\.$ident\\s*\\(" // method calls
                    );
        } else if (type == CodeUnitType.CLASS) {
            return Set.of(
                    "\\bnew\\s+$ident\\s*\\(", // constructor calls
                    "\\bextends\\s+$ident\\b", // inheritance
                    "\\bwith\\s+$ident\\b", // trait mixing
                    "\\b$ident\\s*\\.", // companion object access
                    ":\\s*$ident\\b", // type annotations
                    "<\\s*$ident\\s*>", // generics (deprecated syntax)
                    "\\[\\s*$ident\\s*\\]", // type parameters
                    "\\bcase\\s+class\\s+\\w+.*:\\s*$ident", // case class parameter type
                    "\\bimport\\s+.*\\.$ident\\b" // import statements
                    );
        }
        return Language.super.getSearchPatterns(type);
    }

    @Override
    public String toString() {
        return name();
    }
}
