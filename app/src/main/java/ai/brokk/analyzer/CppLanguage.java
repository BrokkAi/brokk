package ai.brokk.analyzer;

import ai.brokk.project.IProject;
import java.util.Set;

public class CppLanguage implements Language {
    private final Set<String> extensions = Set.of("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "h++");

    @Override
    public Set<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "C/C++";
    }

    @Override
    public String internalName() {
        return "C_CPP";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        return new CppAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> CppAnalyzer.fromState(project, state, listener))
                .orElseGet(() -> (CppAnalyzer) createAnalyzer(project, listener));
    }

    @Override
    public Set<String> getSearchPatterns(CodeUnitType type) {
        if (type == CodeUnitType.FUNCTION) {
            return Set.of(
                    "\\b$ident\\s*\\(", // function calls
                    "\\.$ident\\s*\\(", // method calls
                    "::\\s*$ident\\s*\\(" // namespace/class scope
                    );
        } else if (type == CodeUnitType.CLASS) {
            return Set.of(
                    "\\bnew\\s+$ident(?:<.+?>)?\\s*\\(", // constructor with new and optional templates
                    "\\bclass\\s+\\w+\\s*:\\s*public\\s+$ident(?:<.+?>)?", // public inheritance with optional
                    // templates
                    "\\bclass\\s+\\w+\\s*:\\s*private\\s+$ident(?:<.+?>)?", // private inheritance with optional
                    // templates
                    "\\bclass\\s+\\w+\\s*:\\s*protected\\s+$ident(?:<.+?>)?", // protected inheritance with optional
                    // templates
                    "\\b$ident(?:<.+?>)?\\s+\\w+\\s*[;=]", // variable declarations with optional templates
                    "\\b$ident(?:<.+?>)?\\s*\\*", // pointer types with optional templates
                    "\\b$ident(?:<.+?>)?\\s*&", // reference types with optional templates
                    "<\\s*$ident\\s*>", // as template argument
                    "#include\\s+\"$ident\\.h\"" // header includes
                    );
        }
        return Language.super.getSearchPatterns(type);
    }
}
