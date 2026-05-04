package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class PythonExportUsageGraphAdapter implements ExportUsageGraphLanguageAdapter {
    private final PythonAnalyzer analyzer;

    public PythonExportUsageGraphAdapter(PythonAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public ExportIndex exportIndexOf(ProjectFile file) {
        return analyzer.exportIndexOf(file);
    }

    @Override
    public ImportBinder importBinderOf(ProjectFile file) {
        return analyzer.importBinderOf(file);
    }

    @Override
    public Set<ReferenceCandidate> usageCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.exportUsageCandidatesOf(file);
    }

    @Override
    public Set<CodeUnit> definitionsOf(String localName) {
        var definitions = new LinkedHashSet<CodeUnit>();
        definitions.addAll(analyzer.getDefinitions(localName));
        analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.identifier().equals(localName))
                .forEach(definitions::add);
        return Set.copyOf(definitions);
    }

    @Override
    public ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier) {
        return resolvePythonModule(importingFile, moduleSpecifier)
                .map(ResolutionOutcome::resolved)
                .orElseGet(() -> isRelative(moduleSpecifier)
                        ? ResolutionOutcome.empty()
                        : ResolutionOutcome.external(moduleSpecifier));
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return analyzer.referencingFilesOf(file);
    }

    private Optional<ProjectFile> resolvePythonModule(ProjectFile importingFile, String moduleSpecifier) {
        Path modulePath = modulePath(importingFile, moduleSpecifier);
        if (modulePath.toString().isBlank()) {
            return findProjectFile(importingFile.getParent().resolve("__init__.py"));
        }

        Optional<ProjectFile> moduleFile = findProjectFile(modulePath.resolveSibling(modulePath.getFileName() + ".py"));
        if (moduleFile.isPresent()) {
            return moduleFile;
        }
        return findProjectFile(modulePath.resolve("__init__.py"));
    }

    private Path modulePath(ProjectFile importingFile, String moduleSpecifier) {
        if (!isRelative(moduleSpecifier)) {
            return Path.of(moduleSpecifier.replace('.', '/'));
        }

        int dots = leadingDots(moduleSpecifier);
        Path base = importingFile.getParent();
        for (int i = 1; i < dots; i++) {
            base = base.getParent() == null ? Path.of("") : base.getParent();
        }

        String remainder = moduleSpecifier.substring(dots);
        if (remainder.isBlank()) {
            return base;
        }
        return base.resolve(remainder.replace('.', '/')).normalize();
    }

    private Optional<ProjectFile> findProjectFile(Path relPath) {
        Path normalized = relPath.normalize();
        return analyzer.getProject().getAllFiles().stream()
                .filter(file -> file.getRelPath().equals(normalized))
                .findFirst();
    }

    private static boolean isRelative(String moduleSpecifier) {
        return moduleSpecifier.startsWith(".");
    }

    private static int leadingDots(String moduleSpecifier) {
        int dots = 0;
        while (dots < moduleSpecifier.length() && moduleSpecifier.charAt(dots) == '.') {
            dots++;
        }
        return dots;
    }
}
