package ai.brokk.analyzer;

import ai.brokk.project.IProject;
import java.util.Set;

public class JavaLanguage implements JvmLanguage {
    private final Set<String> extensions = Set.of("java");

    JavaLanguage() {}

    @Override
    public Set<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Java";
    }

    @Override
    public String internalName() {
        return "JAVA";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        return new JavaAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> {
                    var analyzer = JavaAnalyzer.fromState(project, state, listener);
                    return (IAnalyzer) analyzer;
                })
                .orElseGet(() -> createAnalyzer(project, listener));
    }

    @Override
    public Set<String> getSearchPatterns(CodeUnitType type) {
        if (type == CodeUnitType.FUNCTION) {
            return Set.of(
                    "\\b$ident\\s*\\(", // method calls: foo(...)
                    "::\\s*$ident\\b" // method references: ::foo or this::foo
                    );
        } else if (type == CodeUnitType.CLASS) {
            return Set.of(
                    "\\bnew\\s+$ident(?:<.+?>)?\\s*\\(", // constructor calls with optional generics
                    "\\bextends\\s+$ident(?:<.+?>)?", // inheritance with optional generics
                    "\\bimplements\\s+$ident(?:<.+?>)?", // interface implementation with optional generics
                    "\\b$ident\\s*\\.", // static access
                    "\\b$ident(?:<.+?>)?\\s+\\w+\\s*[;=]", // variable declaration with optional generics
                    "\\b$ident(?:<.+?>)?\\s+\\w+\\s*\\)", // parameter with optional generics
                    "<\\s*$ident\\s*>", // as generic type argument
                    "\\(\\s*$ident(?:<.+?>)?\\s*\\)", // cast with optional generics
                    "\\bimport\\s+.*\\.$ident\\b" // import
                    );
        }
        return JvmLanguage.super.getSearchPatterns(type);
    }

    @Override
    public ImportSupport getDependencyImportSupport() {
        return ImportSupport.NONE;
    }
}
