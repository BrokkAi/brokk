package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class PythonExportUsageGraphAdapter implements ExportUsageGraphLanguageAdapter {
    private final PythonAnalyzer analyzer;
    private final Map<Path, ProjectFile> filesByPath;
    private final Map<ModuleResolutionKey, Optional<ProjectFile>> moduleResolutionCache = new HashMap<>();

    public PythonExportUsageGraphAdapter(PythonAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.filesByPath = analyzer.getProject().getAllFiles().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        file -> file.getRelPath().normalize(), file -> file, (left, right) -> left));
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
    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.resolvedReceiverCandidatesOf(file, binder);
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

    @Override
    public Map<String, Set<String>> heritageIndex() {
        return analyzer.heritageIndex();
    }

    @Override
    public List<CodeUnit> ancestorsOf(CodeUnit ownerClass) {
        return analyzer.getAncestors(ownerClass);
    }

    @Override
    public @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(sourceFile))
                .filter(cu -> ownerClassName.equals(ownerNameOf(cu)))
                .filter(cu -> memberName.equals(cu.identifier()))
                .findFirst()
                .orElse(null);
    }

    private Optional<ProjectFile> resolvePythonModule(ProjectFile importingFile, String moduleSpecifier) {
        var key = new ModuleResolutionKey(importingFile, moduleSpecifier);
        Optional<ProjectFile> cached = moduleResolutionCache.get(key);
        if (cached != null) {
            return cached;
        }
        Path modulePath = modulePath(importingFile, moduleSpecifier);
        Optional<ProjectFile> resolved;
        if (modulePath.toString().isBlank()) {
            resolved = findProjectFile(importingFile.getParent().resolve("__init__.py"));
            moduleResolutionCache.put(key, resolved);
            return resolved;
        }

        Optional<ProjectFile> moduleFile = findProjectFile(modulePath.resolveSibling(modulePath.getFileName() + ".py"));
        if (moduleFile.isPresent()) {
            moduleResolutionCache.put(key, moduleFile);
            return moduleFile;
        }
        resolved = findProjectFile(modulePath.resolve("__init__.py"));
        moduleResolutionCache.put(key, resolved);
        return resolved;
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
        return Optional.ofNullable(filesByPath.get(relPath.normalize()));
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

    private static String ownerNameOf(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }

    private record ModuleResolutionKey(ProjectFile importingFile, String moduleSpecifier) {}
}
