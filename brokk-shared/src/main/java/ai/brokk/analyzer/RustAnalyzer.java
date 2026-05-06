package ai.brokk.analyzer;

import static ai.brokk.analyzer.rust.Constants.*;
import static org.treesitter.RustNodeType.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.rust.CognitiveComplexityAnalysis;
import ai.brokk.analyzer.rust.RustExportUsageExtractor;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
import ai.brokk.project.ICoreProject;
import ai.brokk.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;
import org.treesitter.RustNodeField;

public final class RustAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {
    private static final Logger log = LoggerFactory.getLogger(RustAnalyzer.class);

    @Override
    public boolean isFileLevelModule(CodeUnit cu, boolean topLevel) {
        return topLevel
                && cu.isModule()
                && parentOf(cu).isEmpty()
                && languages().stream().anyMatch(language -> language.getExtensions()
                        .contains(cu.source().extension()));
    }

    private record AssertionSignal(
            String kind,
            int score,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    private record RustAssertionAnalysis(
            int assertionCount,
            int shallowAssertionCount,
            int meaningfulAssertionCount,
            List<AssertionSignal> smells) {}

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForRust(reference);
    }

    private static final LanguageSyntaxProfile RS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    nodeType(IMPL_ITEM),
                    nodeType(TRAIT_ITEM),
                    nodeType(STRUCT_ITEM),
                    nodeType(ENUM_ITEM),
                    nodeType(MOD_ITEM)),
            Set.of(nodeType(FUNCTION_ITEM), nodeType(FUNCTION_SIGNATURE_ITEM)),
            Set.of(nodeType(FIELD_DECLARATION), nodeType(CONST_ITEM), nodeType(STATIC_ITEM), nodeType(ENUM_VARIANT)),
            Set.of(nodeType(ATTRIBUTE_ITEM)), // Rust attributes like #[derive(...)]
            Set.of(),
            IMPORT_DECLARATION_CAPTURE, // matches @import.declaration in imports.scm
            nodeField(RustNodeField.NAME), // identifier field name
            nodeField(RustNodeField.BODY), // body field name
            nodeField(RustNodeField.PARAMETERS), // parameters field name
            nodeField(RustNodeField.RETURN_TYPE), // return type field name
            nodeField(RustNodeField.TYPE_PARAMETERS), // type parameters field name
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.IMPL_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.MODULE_DEFINITION, SkeletonType.MODULE_STATEMENT,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.TYPEALIAS_DEFINITION, SkeletonType.ALIAS_LIKE),
            "",
            Set.of(nodeType(VISIBILITY_MODIFIER)));

    public RustAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public RustAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.RUST, listener);
    }

    private RustAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.RUST, state, listener, cache);
    }

    public static RustAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new RustAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new RustAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterRust();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/rust/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/rust/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return RS_SYNTAX_PROFILE;
    }

    @Override
    public int computeCognitiveComplexity(CodeUnit cu) {
        return computeCognitiveComplexity(cu, CognitiveComplexityAnalysis::compute);
    }

    @Override
    public Map<CodeUnit, Integer> computeCognitiveComplexities(ProjectFile file) {
        return computeCognitiveComplexities(file, CognitiveComplexityAnalysis::compute);
    }

    /**
     * Determines the Rust module path for a given file. This considers common Rust project structures like `src/`
     * layouts, `lib.rs`, `main.rs` as crate roots, and `mod.rs` for directory modules.
     *
     * @param file     The project file being analyzed.
     * @param defNode  The TSNode representing the definition (unused in this implementation).
     * @param rootNode The root TSNode of the file's syntax tree (unused in this implementation).
     * @param sourceContent The source code of the file (unused in this implementation).
     * @return The module path string (e.g., "foo.bar"), or an empty string for the crate root.
     */
    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode defNode, TSNode rootNode, SourceContent sourceContent) {
        Path projectRoot = getProject().getRoot();
        Path absFilePath = file.absPath();
        Path fileParentDir = absFilePath.getParent();

        // Determine the effective root for module path calculation (project_root/src/ or project_root/)
        Path srcDirectory = projectRoot.resolve("src");
        boolean usesSrcLayout = Files.isDirectory(srcDirectory) && absFilePath.startsWith(srcDirectory);
        Path effectiveModuleRoot = usesSrcLayout ? srcDirectory : projectRoot;

        String fileNameStr = absFilePath.getFileName().toString();

        // If the file is lib.rs or main.rs, and its parent directory is the effectiveModuleRoot,
        // it's considered the crate root module (empty package path).
        if ((fileNameStr.equals("lib.rs") || fileNameStr.equals("main.rs"))
                && (fileParentDir != null && fileParentDir.equals(effectiveModuleRoot))) {
            return "";
        }

        // Calculate the path of the file's directory relative to the effectiveModuleRoot.
        Path relativeDirFromModuleRoot;
        if (fileParentDir != null && fileParentDir.startsWith(effectiveModuleRoot)) {
            relativeDirFromModuleRoot = effectiveModuleRoot.relativize(fileParentDir);
        } else if (fileParentDir != null && fileParentDir.startsWith(projectRoot)) {
            // Fallback: file is not under effectiveModuleRoot (e.g. src/) but is under projectRoot.
            // This can happen if usesSrcLayout was true but the file is in a sibling dir to src, e.g.
            // project_root/examples/
            // Treat as relative to projectRoot in such cases.
            relativeDirFromModuleRoot = projectRoot.relativize(fileParentDir);
            log.trace(
                    "File {} not in effective module root {}, calculating package relative to project root {}.",
                    absFilePath,
                    effectiveModuleRoot,
                    projectRoot);
        } else {
            // File path is outside the project root, which is highly unexpected.
            log.warn(
                    "File {} is outside the project root {}. Defaulting to empty package name.",
                    absFilePath,
                    projectRoot);
            return "";
        }

        String relativeDirModulePath = relativeDirFromModuleRoot
                .toString()
                .replace(absFilePath.getFileSystem().getSeparator(), ".");
        // Path.toString() on an empty path (e.g., if fileParentDir is effectiveModuleRoot) results in an empty string.
        // Ensure that leading/trailing dots from malformed paths or separator replacement are handled if necessary,
        // though Path relativize and toString usually behave well. Here, simple replacement is okay.
        if (".".equals(relativeDirModulePath)) { // Handle potential dot from root relativization
            relativeDirModulePath = "";
        }

        if (fileNameStr.equals("mod.rs")) {
            // For a file like 'src/foo/bar/mod.rs', relativeDirModulePath would be 'foo.bar'.
            // This is the module path.
            return relativeDirModulePath;
        } else if (fileNameStr.endsWith(".rs")) {
            String fileStem = fileNameStr.substring(0, fileNameStr.length() - ".rs".length());
            if (relativeDirModulePath.isEmpty()) {
                // File is directly in effectiveModuleRoot (e.g., src/my_module.rs or project_root/my_crate_file.rs).
                // If 'src/' layout is used (e.g., 'src/my_module.rs'), its package path is 'my_module'.
                // If not 'src/' layout (e.g. 'project_root/Point.rs' from tests), package path is "" (crate root).
                return usesSrcLayout ? fileStem : "";
            } else {
                // File is in a subdirectory of effectiveModuleRoot (e.g., 'src/foo/bar.rs').
                // relativeDirModulePath is 'foo', fileStem is 'bar' => 'foo.bar'.
                return relativeDirModulePath + "." + fileStem;
            }
        }

        // Fallback for non-.rs files or unexpected structures.
        log.warn(
                "Could not determine Rust package name for non .rs file {} (relative dir path '{}'). Using directory path possibly with filename.",
                absFilePath,
                relativeDirModulePath);
        return relativeDirModulePath.isEmpty() ? fileNameStr : relativeDirModulePath + "." + fileNameStr;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            List<ScopeSegment> scopeChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        log.trace(
                "RustAnalyzer.createCodeUnit: File='{}', Capture='{}', SimpleName='{}', Package='{}', ClassChain='{}'",
                file.getFileName(),
                captureName,
                simpleName,
                packageName,
                classChain);
        return switch (captureName) {
            // "class.definition" is for struct, trait, enum.
            // "impl.definition" is for impl blocks. Both create class-like CodeUnits.
            // simpleName for "impl.definition" will be the type being implemented (e.g., "Point").
            case CaptureNames.CLASS_DEFINITION, CaptureNames.IMPL_DEFINITION ->
                CodeUnit.cls(file, packageName, simpleName);
            // "module.definition" is for mod blocks.
            case CaptureNames.MODULE_DEFINITION -> {
                String fqPackage = classChain.isEmpty()
                        ? packageName
                        : (packageName.isEmpty() ? classChain : packageName + "." + classChain);
                yield CodeUnit.module(file, fqPackage, simpleName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> {
                // For methods, classChain will be the struct/impl type name.
                // For free functions, classChain will be empty (or contain module names).
                String fqSimpleName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, fqSimpleName);
            }
            case CaptureNames.FIELD_DEFINITION -> {
                // For struct fields, classChain is the struct name.
                // For top-level const/static, classChain is empty (or contains module names).
                String fieldShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            case CaptureNames.TYPEALIAS_DEFINITION -> CodeUnit.cls(file, packageName, simpleName);
            default -> {
                log.warn(
                        "Unhandled capture name in RustAnalyzer.createCodeUnit: '{}' for simple name '{}' in file '{}'. Returning null.",
                        captureName,
                        simpleName,
                        file.getFileName());
                yield null; // Explicitly yield null
            }
        };
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, SourceContent sourceContent) {
        // A common pattern for Rust grammar is that visibility_modifier is a direct child.
        // We check the first few children as its position can vary slightly (e.g. after attributes).
        int i = 0;
        for (TSNode child : node.getChildren()) {
            if (nodeType(VISIBILITY_MODIFIER).equals(child.getType())) {
                String text = sourceContent.substringFrom(child).strip();
                return text + " ";
            }
            if (i > 5) break;
            i++;
        }
        return "";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode fnNode,
            SourceContent sourceContent,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        String rt = returnTypeText.isBlank() ? "" : " -> " + returnTypeText;
        // exportPrefix is from getVisibilityPrefix. asyncPrefix from base class logic.
        String header = String.format(
                        "%s%s%sfn %s%s%s%s",
                        indent, exportPrefix, asyncPrefix, functionName, typeParamsText, paramsText, rt)
                .stripLeading();

        TSNode bodyNode = fnNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode != null) {
            // For functions/methods with a body
            return header + " { " + bodyPlaceholder() + " }";
        } else {
            // For function signatures without a body (e.g., in traits)
            return header + ";";
        }
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        // signatureText is derived by TreeSitterAnalyzer using textSlice up to the body or end of node.
        // For Rust, this text (e.g. "struct Foo", "impl Point for Bar") is what we want, prefixed by visibility.
        return baseIndent + exportPrefix + signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // If queries have helper captures like @_.type or similar that aren't actual definitions, list them here.
        // For now, assuming all captures ending in .name or .definition are intentional.
        return Set.of();
    }

    /**
     * Recursively extracts the core type name from a type node, unwrapping generic types,
     * reference types, pointer types, array/slice types, and scoped type identifiers
     * to get the simple type identifier.
     *
     * @implNote Blanket impls over different wrapper types (e.g. {@code impl<T> Deref for *const T}
     * and {@code impl<T> Deref for *mut T}) will both extract the generic type parameter {@code T},
     * producing CodeUnits with identical names. This is inherent to how generic type parameters work
     * and is acceptable.
     */
    private Optional<String> extractCoreTypeName(@Nullable TSNode typeNode, SourceContent sourceContent) {
        if (typeNode == null) return Optional.empty();
        String typeName = typeNode.getType();
        if (typeName == null) return Optional.empty();

        if (nodeType(TYPE_IDENTIFIER).equals(typeName)) {
            return Optional.of(sourceContent.substringFrom(typeNode));
        }

        if (nodeType(SCOPED_TYPE_IDENTIFIER).equals(typeName)) {
            TSNode nameNode = typeNode.getChildByFieldName(nodeField(RustNodeField.NAME));
            return extractCoreTypeName(nameNode, sourceContent)
                    .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
        }

        if (nodeType(GENERIC_TYPE).equals(typeName)
                || nodeType(REFERENCE_TYPE).equals(typeName)
                || nodeType(POINTER_TYPE).equals(typeName)) {
            TSNode innerType = typeNode.getChildByFieldName(nodeField(RustNodeField.TYPE));
            return extractCoreTypeName(innerType, sourceContent)
                    .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
        }

        if (nodeType(ARRAY_TYPE).equals(typeName)) {
            // Array/slice types like [T] or [T; N] have "element" field for the inner type
            TSNode elementType = typeNode.getChildByFieldName(nodeField(RustNodeField.ELEMENT));
            return extractCoreTypeName(elementType, sourceContent)
                    .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
        }

        if (nodeType(TUPLE_TYPE).equals(typeName)) {
            // Tuple types like (A, B) - extract the first element for naming
            // The tuple_type node has children that are the element types
            if (typeNode.getChildCount() > 0) {
                for (int i = 0; i < typeNode.getChildCount(); i++) {
                    TSNode child = typeNode.getChild(i);
                    if (child != null) {
                        String childType = child.getType();
                        // Skip punctuation like '(' ',' ')'
                        if (!"(".equals(childType) && !")".equals(childType) && !",".equals(childType)) {
                            Optional<String> extracted = extractCoreTypeName(child, sourceContent);
                            if (extracted.isPresent()) {
                                return extracted;
                            }
                        }
                    }
                }
            }
            return Optional.of(sourceContent.substringFrom(typeNode));
        }

        String text = sourceContent.substringFrom(typeNode);
        log.debug("extractCoreTypeName: unhandled node type '{}', using full text '{}'", typeName, text);
        return Optional.of(text);
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, SourceContent sourceContent) {
        if (nodeType(IMPL_ITEM).equals(decl.getType())) {
            TSNode typeNode = decl.getChildByFieldName(nodeField(RustNodeField.TYPE));
            if (typeNode != null) {
                Optional<String> name = extractCoreTypeName(typeNode, sourceContent);
                if (name.isPresent()) {
                    return name;
                }
            }
            String errorContext = String.format(
                    "Node type %s (text: '%s')",
                    decl.getType(),
                    sourceContent
                            .substringFrom(decl)
                            .lines()
                            .findFirst()
                            .orElse("")
                            .trim());
            throw new IllegalStateException(
                    "RustAnalyzer.extractSimpleName for impl_item: 'type' field not found or null. Cannot determine simple name for "
                            + errorContext);
        }

        // For all other node types, defer to the base class implementation.
        // If super returns empty, throw.
        Optional<String> nameFromSuper = super.extractSimpleName(decl, sourceContent);
        if (nameFromSuper.isEmpty()) {
            String errorContext = String.format(
                    "Node type %s (text: '%s')",
                    decl.getType(),
                    sourceContent
                            .substringFrom(decl)
                            .lines()
                            .findFirst()
                            .orElse("")
                            .trim());
            throw new IllegalStateException(
                    "super.extractSimpleName (from RustAnalyzer) failed to find a name for " + errorContext);
        }
        return nameFromSuper;
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String simpleName,
            String baseIndent,
            ProjectFile file) {
        String sig = signatureText.strip();
        String pref = exportPrefix.strip();

        String fullSignature;
        if (!pref.isEmpty() && sig.startsWith(pref)) {
            fullSignature = sig;
        } else {
            fullSignature = (exportPrefix.stripTrailing() + " " + sig).strip();
        }

        // Rust fields like "pub x: i32," and "const ORIGIN: Point = ..." should not have semicolons added in skeleton
        // format
        return baseIndent + fullSignature;
    }

    @Override
    protected boolean requiresSemicolons() {
        return false; // Rust fields like "pub x: i32," should not have semicolons added
    }

    @Override
    protected Set<String> getLeadingMetadataNodeTypes() {
        return Set.of(nodeType(ATTRIBUTE_ITEM), nodeType(INNER_ATTRIBUTE_ITEM));
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return performReferencingFilesOf(file);
    }

    public ExportIndex exportIndexOf(ProjectFile file) {
        ExportIndex cached = cache().exportIndex().get(file);
        if (cached != null) {
            return cached;
        }
        ExportIndex computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return ExportIndex.empty();
                    }
                    return withSource(
                            file,
                            source -> RustExportUsageExtractor.computeExportIndex(root, source),
                            ExportIndex.empty());
                },
                ExportIndex.empty());
        cache().exportIndex().put(file, computed);
        return computed;
    }

    public ImportBinder importBinderOf(ProjectFile file) {
        ImportBinder cached = cache().importBinder().get(file);
        if (cached != null) {
            return cached;
        }
        Set<String> localTopLevelNames = getTopLevelDeclarations(file).stream()
                .map(CodeUnit::identifier)
                .collect(java.util.stream.Collectors.toSet());
        ImportBinder computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return ImportBinder.empty();
                    }
                    return withSource(
                            file,
                            source -> RustExportUsageExtractor.computeImportBinder(
                                    this, file, root, source, localTopLevelNames),
                            ImportBinder.empty());
                },
                ImportBinder.empty());
        cache().importBinder().put(file, computed);
        return computed;
    }

    public Set<ReferenceCandidate> exportUsageCandidatesOf(ProjectFile file, ImportBinder binder) {
        Set<ReferenceCandidate> cached = cache().references().get(file);
        if (cached != null) {
            return cached;
        }
        Set<String> localExportNames = exportIndexOf(file).exportsByName().keySet();
        Set<ReferenceCandidate> computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return Set.<ReferenceCandidate>of();
                    }
                    return withSource(
                            file,
                            source -> RustExportUsageExtractor.computeUsageCandidates(
                                    this, file, root, source, binder, localExportNames),
                            Set.<ReferenceCandidate>of());
                },
                Set.<ReferenceCandidate>of());
        cache().references().put(file, computed);
        return computed;
    }

    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        Set<ResolvedReceiverCandidate> cached = cache().receiverCandidates().get(file);
        if (cached != null) {
            return cached;
        }
        Set<ResolvedReceiverCandidate> computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return Set.<ResolvedReceiverCandidate>of();
                    }
                    return withSource(
                            file,
                            source -> RustExportUsageExtractor.computeResolvedReceiverCandidates(
                                    this, file, root, source, binder),
                            Set.<ResolvedReceiverCandidate>of());
                },
                Set.<ResolvedReceiverCandidate>of());
        cache().receiverCandidates().put(file, computed);
        return computed;
    }

    public Map<String, Set<String>> heritageIndex() {
        Map<String, Set<String>> cached = cache().heritageIndex();
        if (cached != null) {
            return cached;
        }
        var computed = new HashMap<String, Set<String>>();
        for (ProjectFile file : getAnalyzedFiles()) {
            ExportIndex index = exportIndexOf(file);
            for (ExportIndex.HeritageEdge edge : index.heritageEdges()) {
                String childKey = qualifiedClassKey(file, edge.childName());
                String parentKey = qualifiedClassKey(file, edge.parentName());
                computed.computeIfAbsent(childKey, ignored -> new HashSet<>()).add(parentKey);
            }
        }
        var immutable = computed.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        cache().heritageIndex(immutable);
        return immutable;
    }

    public @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        boolean declared = exportIndexOf(sourceFile).classMembers().stream()
                .filter(member -> member.ownerClassName().equals(ownerClassName))
                .filter(member -> member.memberName().equals(memberName))
                .anyMatch(member -> instanceReceiver ? !member.staticMember() : member.staticMember());
        if (!declared) {
            return null;
        }
        return getDeclarations(sourceFile).stream()
                .filter(cu -> cu.isFunction() || cu.isField())
                .filter(cu -> parentOf(cu).map(CodeUnit::identifier).orElse("").equals(ownerClassName))
                .filter(cu -> normalizedMemberName(cu).equals(memberName))
                .findFirst()
                .orElse(null);
    }

    public ai.brokk.analyzer.usages.ExportUsageGraphLanguageAdapter.ResolutionOutcome resolveRustModuleOutcome(
            ProjectFile importingFile, String moduleSpecifier) {
        String fqn = resolveRustPathToFqn(moduleSpecifier, packageNameOf(importingFile));
        return rustModuleFileForFqn(fqn)
                .map(ai.brokk.analyzer.usages.ExportUsageGraphLanguageAdapter.ResolutionOutcome::resolved)
                .orElseGet(() -> ai.brokk.analyzer.usages.ExportUsageGraphLanguageAdapter.ResolutionOutcome.external(
                        moduleSpecifier));
    }

    public String packageNameOf(ProjectFile file) {
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) return "";
                    return withSource(file, sc -> determinePackageName(file, root, root, sc), "");
                },
                "");
    }

    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        String currentPackage = packageNameOf(file);
        Set<CodeUnit> resolved = new HashSet<>();

        for (var spec : rustUseSpecsOf(file)) {
            if (spec.wildcard()) {
                String wildcardPath = stripWildcardSegment(spec.path());
                if (!wildcardPath.isEmpty()) {
                    String packageFqn = resolveRustPathToFqn(wildcardPath, currentPackage);
                    // Search for all definitions that belong to this package
                    resolved.addAll(searchDefinitions("^" + Pattern.quote(packageFqn) + "\\.[^.]+$", false));
                }
                continue;
            }

            String fqn = resolveRustPathToFqn(spec.path(), currentPackage);
            resolved.addAll(getDefinitions(fqn));
        }

        return Collections.unmodifiableSet(resolved);
    }

    private List<RustExportUsageExtractor.UseSpec> rustUseSpecsOf(ProjectFile file) {
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.<RustExportUsageExtractor.UseSpec>of();
                    }
                    return withSource(file, source -> rustUseSpecsOf(root, source), List.of());
                },
                List.of());
    }

    private static List<RustExportUsageExtractor.UseSpec> rustUseSpecsOf(TSNode root, SourceContent source) {
        var specs = new ArrayList<RustExportUsageExtractor.UseSpec>();
        collectRustUseSpecs(root, source, specs);
        return List.copyOf(specs);
    }

    private static void collectRustUseSpecs(
            TSNode node, SourceContent source, List<RustExportUsageExtractor.UseSpec> specs) {
        if (nodeType(USE_DECLARATION).equals(node.getType())) {
            specs.addAll(RustExportUsageExtractor.useSpecsOf(node, source));
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            collectRustUseSpecs(child, source, specs);
        }
    }

    private static String stripWildcardSegment(String rustPath) {
        return rustPath.endsWith("::*") ? rustPath.substring(0, rustPath.length() - 3) : rustPath;
    }

    public String resolveRustPathToFqn(String rustPath, String currentPackage) {
        String trimmedPath = rustPath.trim();

        if (trimmedPath.startsWith("crate::")) {
            return trimmedPath.substring("crate::".length()).replace("::", ".");
        }

        if (trimmedPath.startsWith("self::")) {
            String path = trimmedPath.substring("self::".length()).replace("::", ".");
            return currentPackage.isEmpty() ? path : currentPackage + "." + path;
        }

        if (trimmedPath.startsWith("super::")) {
            String remaining = trimmedPath;
            String pkg = currentPackage;
            boolean hitRoot = pkg.isEmpty();

            while (remaining.startsWith("super::")) {
                remaining = remaining.substring("super::".length());
                if (!hitRoot) {
                    int lastDot = pkg.lastIndexOf('.');
                    if (lastDot == -1) {
                        pkg = "";
                        hitRoot = true;
                    } else {
                        pkg = pkg.substring(0, lastDot);
                    }
                }
            }

            String path = remaining.replace("::", ".");
            if (path.startsWith(".")) {
                path = path.substring(1);
            }

            if (hitRoot && currentPackage.isEmpty()) {
                // If we started at root and tried to go super, just return the remaining path
                return path;
            }

            return pkg.isEmpty() ? path : (path.isEmpty() ? pkg : pkg + "." + path);
        }

        // External crates or absolute paths not starting with crate::
        return trimmedPath.replace("::", ".");
    }

    private Optional<ProjectFile> rustModuleFileForFqn(String fqn) {
        String slashPath = fqn.replace('.', '/');
        var candidates = new ArrayList<Path>();
        if (slashPath.isBlank()) {
            candidates.add(Path.of("src/lib.rs"));
            candidates.add(Path.of("src/main.rs"));
            candidates.add(Path.of("lib.rs"));
            candidates.add(Path.of("main.rs"));
        } else {
            candidates.add(Path.of("src", slashPath + ".rs"));
            candidates.add(Path.of("src", slashPath, "mod.rs"));
            candidates.add(Path.of(slashPath + ".rs"));
            candidates.add(Path.of(slashPath, "mod.rs"));
        }
        return candidates.stream()
                .map(path -> getProject().getFileByRelPath(path))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String normalizedMemberName(CodeUnit codeUnit) {
        String identifier = codeUnit.identifier();
        int marker = identifier.indexOf('$');
        return marker >= 0 ? identifier.substring(0, marker) : identifier;
    }

    private static String qualifiedClassKey(ProjectFile file, String className) {
        return PathNormalizer.canonicalizeForProject(file.getRelPath().toString(), file.getRoot()) + ":" + className;
    }

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode importNode = capturedNodesForMatch.get(RS_SYNTAX_PROFILE.importNodeType());
        if (importNode == null) {
            return;
        }

        RustExportUsageExtractor.useSpecsOf(importNode, sourceContent).stream()
                .map(spec -> new ImportInfo(
                        "use " + spec.path() + (spec.alias() != null ? " as " + spec.alias() : "") + ";",
                        spec.wildcard(),
                        spec.localName(),
                        spec.alias()))
                .forEach(localImportInfos::add);
    }

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        checkStale("findExceptionHandlingSmells");
        ExceptionSmellWeights resolvedWeights = weights != null ? weights : ExceptionSmellWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file,
                            source -> detectExceptionHandlingSmells(file, root, source, resolvedWeights),
                            List.of());
                },
                List.of());
    }

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var findings = new ArrayList<SmellCandidate>();

        var matches = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(MATCH_EXPRESSION)), matches);
        for (TSNode matchExpr : matches) {
            analyzeMatchHandlers(file, matchExpr, sourceContent, weights, findings);
        }

        var ifs = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(IF_EXPRESSION)), ifs);
        for (TSNode ifExpr : ifs) {
            analyzeIfLetErrHandler(file, ifExpr, sourceContent, weights).ifPresent(findings::add);
        }

        return findings.stream()
                .sorted(EXCEPTION_SMELL_CANDIDATE_COMPARATOR)
                .map(SmellCandidate::smell)
                .toList();
    }

    private void analyzeMatchHandlers(
            ProjectFile file,
            TSNode matchExpr,
            SourceContent sourceContent,
            ExceptionSmellWeights weights,
            List<SmellCandidate> out) {
        boolean isCatchUnwind = matchContainsCatchUnwindCall(matchExpr, sourceContent);

        var arms = new ArrayList<TSNode>();
        collectNodesByType(matchExpr, Set.of(nodeType(MATCH_ARM)), arms);
        for (TSNode arm : arms) {
            if (!belongsToMatchExpression(arm, matchExpr)) {
                continue;
            }
            TSNode pattern = arm.getChildByFieldName(nodeField(RustNodeField.PATTERN));
            if (pattern == null && arm.getNamedChildCount() > 0) {
                pattern = arm.getNamedChild(0);
            }
            if (pattern == null || !patternContainsErr(pattern, sourceContent)) {
                continue;
            }
            TSNode body = arm.getChildByFieldName(nodeField(RustNodeField.VALUE));
            if (body == null && arm.getNamedChildCount() > 0) {
                body = arm.getNamedChild(arm.getNamedChildCount() - 1);
            }
            if (body == null) {
                continue;
            }
            int base = isCatchUnwind ? weights.genericThrowableWeight() : weights.genericExceptionWeight();
            String baseReason = isCatchUnwind ? "generic-catch:catch_unwind" : "generic-catch:Err";
            analyzeRustHandlerBody(file, body, sourceContent, weights, base, baseReason)
                    .ifPresent(out::add);
        }
    }

    private static boolean belongsToMatchExpression(TSNode arm, TSNode matchExpr) {
        TSNode parent = arm.getParent();
        while (parent != null && !nodeType(MATCH_EXPRESSION).equals(parent.getType())) {
            parent = parent.getParent();
        }
        if (parent == null) {
            return false;
        }
        return parent.getStartByte() == matchExpr.getStartByte() && parent.getEndByte() == matchExpr.getEndByte();
    }

    private static boolean matchContainsCatchUnwindCall(TSNode matchExpr, SourceContent sourceContent) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(matchExpr, Set.of(nodeType(CALL_EXPRESSION)), calls);
        for (TSNode call : calls) {
            if ("catch_unwind".equals(rustCallCalleeLastIdent(call, sourceContent))) {
                return true;
            }
        }
        return false;
    }

    private Optional<SmellCandidate> analyzeIfLetErrHandler(
            ProjectFile file, TSNode ifExpr, SourceContent sourceContent, ExceptionSmellWeights weights) {
        // Only inspect this `if`'s condition; do not search the whole subtree (which would
        // incorrectly attribute nested `if let Err(...)` conditions to an outer `if`).
        TSNode condition = ifExpr.getChildByFieldName(nodeField(RustNodeField.CONDITION));
        if (condition == null || !patternContainsErr(condition, sourceContent)) {
            return Optional.empty();
        }
        TSNode consequence = ifExpr.getChildByFieldName(nodeField(RustNodeField.CONSEQUENCE));
        if (consequence == null) {
            consequence = findFirstNamedDescendant(ifExpr, nodeType(BLOCK));
        }
        if (consequence == null) {
            return Optional.empty();
        }
        return analyzeRustHandlerBody(
                file, consequence, sourceContent, weights, weights.genericExceptionWeight(), "generic-catch:Err");
    }

    private Optional<SmellCandidate> analyzeRustHandlerBody(
            ProjectFile file,
            TSNode handlerNode,
            SourceContent sourceContent,
            ExceptionSmellWeights weights,
            int baseScore,
            String baseReason) {
        TSNode block = nodeType(BLOCK).equals(handlerNode.getType()) ? handlerNode : null;
        int bodyStatements = block != null ? countHandlerStatements(block) : 1;
        boolean hasAnyComment = hasDescendantOfAnyTypeInclusive(handlerNode, COMMENT_NODE_TYPES);
        boolean emptyBody = block != null && bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = block != null && bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean rethrowPresent = hasPanicLike(handlerNode, sourceContent);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyRust(handlerNode, sourceContent) && !rethrowPresent;

        int score = baseScore;
        var reasons = new ArrayList<String>();
        reasons.add(baseReason);
        if (emptyBody) {
            score += weights.emptyBodyWeight();
            reasons.add("empty-body");
        }
        if (commentOnlyBody) {
            score += weights.commentOnlyBodyWeight();
            reasons.add("comment-only-body");
        }
        if (smallBody) {
            score += weights.smallBodyWeight();
            reasons.add("small-body:" + bodyStatements);
        }
        if (logOnly) {
            score += weights.logOnlyWeight();
            reasons.add("log-only-body");
        }

        int creditStatements = Math.min(bodyStatements, Math.max(0, weights.meaningfulBodyStatementThreshold()));
        int bodyCredit = Math.max(0, weights.meaningfulBodyCreditPerStatement()) * creditStatements;
        if (bodyCredit > 0) {
            score -= bodyCredit;
            reasons.add("meaningful-body-credit:" + bodyCredit);
        }
        if (score <= 0) {
            return Optional.empty();
        }

        String enclosing = enclosingCodeUnit(
                        file,
                        handlerNode.getStartPoint().getRow(),
                        handlerNode.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        var smell = new ExceptionHandlingSmell(
                file,
                enclosing,
                baseReason.replace("generic-catch:", ""),
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactExcerptForTable(sourceContent.substringFrom(handlerNode)));
        return Optional.of(new SmellCandidate(smell, handlerNode.getStartByte()));
    }

    private static boolean patternContainsErr(TSNode patternNode, SourceContent sourceContent) {
        Deque<TSNode> stack = new ArrayDeque<>();
        stack.push(patternNode);
        while (!stack.isEmpty()) {
            TSNode node = stack.pop();
            if (node == null) {
                continue;
            }
            if (nodeType(IDENTIFIER).equals(node.getType())
                    && "Err".equals(sourceContent.substringFrom(node).strip())) {
                return true;
            }
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode child = node.getNamedChild(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return false;
    }

    private static int countHandlerStatements(TSNode block) {
        int expressions = 0;
        for (int i = 0; i < block.getNamedChildCount(); i++) {
            TSNode child = block.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (COMMENT_NODE_TYPES.contains(child.getType())) {
                continue;
            }
            expressions++;
        }
        return expressions;
    }

    private static boolean hasPanicLike(TSNode root, SourceContent sourceContent) {
        var macros = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(MACRO_INVOCATION)), macros);
        for (TSNode macro : macros) {
            if ("panic".equals(rustMacroLastIdent(macro, sourceContent))) {
                return true;
            }
        }
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(CALL_EXPRESSION)), calls);
        for (TSNode call : calls) {
            if ("resume_unwind".equals(rustCallCalleeLastIdent(call, sourceContent))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyLogOnlyRust(TSNode handlerNode, SourceContent sourceContent) {
        TSNode node = handlerNode;
        if (nodeType(BLOCK).equals(handlerNode.getType())) {
            node = firstNonCommentNamedChild(handlerNode, COMMENT_NODE_TYPES);
            if (node == null) {
                return false;
            }
        }
        TSNode macro = findFirstNamedDescendant(node, nodeType(MACRO_INVOCATION));
        if (macro == null) {
            return false;
        }
        String lastIdent = rustMacroLastIdent(macro, sourceContent).toLowerCase(Locale.ROOT);
        return RUST_LOG_MACRO_NAMES.contains(lastIdent) || RUST_PRINT_MACRO_NAMES.contains(lastIdent);
    }

    private static String rustMacroLastIdent(TSNode macroInvocation, SourceContent sourceContent) {
        TSNode path = macroInvocation.getNamedChildCount() > 0 ? macroInvocation.getNamedChild(0) : null;
        if (path == null) {
            // Some Rust macro invocations use a `path` node for the callee. Our generated RustNodeType enum
            // does not currently expose a PATH constant, so fall back to the raw node-type string.
            path = findFirstNamedDescendant(macroInvocation, "path");
        }
        if (path == null) {
            path = findFirstNamedDescendant(macroInvocation, nodeType(SCOPED_IDENTIFIER));
        }
        if (path == null) {
            path = findFirstNamedDescendant(macroInvocation, nodeType(IDENTIFIER));
        }
        return path == null ? "" : lastIdentifierIn(path, sourceContent);
    }

    private static String rustCallCalleeLastIdent(TSNode call, SourceContent sourceContent) {
        TSNode function = call.getChildByFieldName(nodeField(RustNodeField.FUNCTION));
        if (function == null && call.getNamedChildCount() > 0) {
            function = call.getNamedChild(0);
        }
        return function == null ? "" : lastIdentifierIn(function, sourceContent);
    }

    private static String lastIdentifierIn(TSNode node, SourceContent sourceContent) {
        String last = "";
        try (var cursor = new TSTreeCursor(node)) {
            while (true) {
                TSNode current = cursor.currentNode();
                if (current == null) {
                    break;
                }
                if (nodeType(IDENTIFIER).equals(current.getType())) {
                    String text = sourceContent.substringFrom(current).strip();
                    if (!text.isEmpty()) {
                        last = text;
                    }
                }
                if (!gotoNextDepthFirst(cursor, current.isNamed())) {
                    break;
                }
            }
        }
        return last;
    }

    @Override
    public List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        checkStale("findTestAssertionSmells");
        if (!containsTests(file)) {
            return List.of();
        }

        TestAssertionWeights resolvedWeights = weights != null ? weights : TestAssertionWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file, source -> detectTestAssertionSmells(file, root, source, resolvedWeights), List.of());
                },
                List.of());
    }

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        var functions = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(FUNCTION_ITEM)), functions);
        var testFunctions = rustFunctionsWithDirectTestAttribute(root, sourceContent);

        var candidates = new ArrayList<TestSmellCandidate>();
        for (TSNode function : functions) {
            if (!testFunctions.contains(function)) {
                continue;
            }

            var analysis = analyzeRustAssertions(function, sourceContent, weights);
            var signals = analysis.smells();
            int assertionCount = analysis.assertionCount();

            String enclosing = enclosingCodeUnit(
                            file,
                            function.getStartPoint().getRow(),
                            function.getEndPoint().getRow())
                    .map(CodeUnit::fqName)
                    .orElse(file.toString());

            if (assertionCount == 0) {
                var smell = new TestAssertionSmell(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_NO_ASSERTIONS,
                        weights.noAssertionWeight(),
                        0,
                        List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                        sourceContent.substringFrom(function));
                candidates.add(new TestSmellCandidate(smell, function.getStartByte()));
                continue;
            }

            for (AssertionSignal signal : signals) {
                if (signal.score() <= 0) continue;
                candidates.add(new TestSmellCandidate(
                        new TestAssertionSmell(
                                file,
                                enclosing,
                                signal.kind(),
                                signal.score(),
                                assertionCount,
                                List.copyOf(signal.reasons()),
                                signal.excerpt()),
                        signal.startByte()));
            }

            boolean allShallow = analysis.shallowAssertionCount() == assertionCount;
            if (allShallow) {
                int score = weights.shallowAssertionOnlyWeight()
                        - meaningfulAssertionCredit(analysis.meaningfulAssertionCount(), weights);
                if (score > 0) {
                    var smell = new TestAssertionSmell(
                            file,
                            enclosing,
                            TEST_ASSERTION_KIND_SHALLOW_ONLY,
                            score,
                            assertionCount,
                            List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                            sourceContent.substringFrom(function));
                    candidates.add(new TestSmellCandidate(smell, function.getStartByte()));
                }
            }
        }

        return candidates.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private static int meaningfulAssertionCredit(int meaningfulAssertionCount, TestAssertionWeights weights) {
        int creditable = Math.min(meaningfulAssertionCount, Math.max(0, weights.meaningfulAssertionCreditCap()));
        return Math.max(0, weights.meaningfulAssertionCredit()) * creditable;
    }

    private RustAssertionAnalysis analyzeRustAssertions(
            TSNode testFn, SourceContent sourceContent, TestAssertionWeights weights) {
        var macros = new ArrayList<TSNode>();
        collectNodesByType(testFn, Set.of(nodeType(MACRO_INVOCATION)), macros);
        if (macros.isEmpty()) return new RustAssertionAnalysis(0, 0, 0, List.of());

        var signals = new ArrayList<AssertionSignal>();
        int assertionCount = 0;
        int shallowAssertionCount = 0;
        int meaningfulAssertionCount = 0;
        for (TSNode macro : macros) {
            String macroName = rustMacroName(macro, sourceContent);
            if (!(ASSERT_MACRO_NAME.equals(macroName)
                    || ASSERT_EQ_MACRO_NAME.equals(macroName)
                    || ASSERT_NE_MACRO_NAME.equals(macroName)
                    || ASSERT_MATCHES_MACRO_NAME.equals(macroName))) {
                continue;
            }
            assertionCount++;

            if (ASSERT_EQ_MACRO_NAME.equals(macroName) || ASSERT_NE_MACRO_NAME.equals(macroName)) {
                var signal = classifyRustEqLikeMacro(macro, sourceContent, weights);
                if (signal != null) {
                    if (signal.shallow()) {
                        shallowAssertionCount++;
                    }
                    if (signal.meaningful()) {
                        meaningfulAssertionCount++;
                    }
                } else {
                    meaningfulAssertionCount++;
                }
                if (signal != null && signal.score() > 0) {
                    signals.add(signal);
                }
                continue;
            }

            if (ASSERT_MATCHES_MACRO_NAME.equals(macroName)) {
                var signal = classifyRustMatchesMacro(macro, sourceContent, weights);
                if (signal != null) {
                    if (signal.shallow()) {
                        shallowAssertionCount++;
                    }
                    if (signal.meaningful()) {
                        meaningfulAssertionCount++;
                    }
                } else {
                    meaningfulAssertionCount++;
                }
                if (signal != null && signal.score() > 0) {
                    signals.add(signal);
                }
                continue;
            }

            if (ASSERT_MACRO_NAME.equals(macroName)) {
                var signal = classifyRustAssertMacro(macro, sourceContent, weights);
                if (signal != null) {
                    if (signal.shallow()) {
                        shallowAssertionCount++;
                    }
                    if (signal.meaningful()) {
                        meaningfulAssertionCount++;
                    }
                } else {
                    meaningfulAssertionCount++;
                }
                if (signal != null && signal.score() > 0) {
                    signals.add(signal);
                }
            }
        }

        signals.sort(Comparator.comparingInt(AssertionSignal::startByte));
        return new RustAssertionAnalysis(
                assertionCount, shallowAssertionCount, meaningfulAssertionCount, List.copyOf(signals));
    }

    private static final String ASSERT_MACRO_NAME = "assert";
    private static final String ASSERT_EQ_MACRO_NAME = "assert_eq";
    private static final String ASSERT_NE_MACRO_NAME = "assert_ne";
    private static final String ASSERT_MATCHES_MACRO_NAME = "assert_matches";

    private static String rustMacroName(TSNode macro, SourceContent sourceContent) {
        int tokenTreeStart = Integer.MAX_VALUE;
        var tokenTrees = new ArrayList<TSNode>();
        collectNodesByType(macro, Set.of(nodeType(TOKEN_TREE)), tokenTrees);
        if (!tokenTrees.isEmpty()) {
            tokenTreeStart = tokenTrees.getFirst().getStartByte();
        }

        var ids = new ArrayList<TSNode>();
        collectNodesByType(macro, Set.of(nodeType(IDENTIFIER)), ids);

        TSNode best = null;
        for (TSNode id : ids) {
            if (id.getStartByte() >= tokenTreeStart) {
                continue;
            }
            if (best == null || id.getStartByte() > best.getStartByte()) {
                best = id;
            }
        }
        if (best == null) {
            return "";
        }
        return sourceContent.substringFrom(best).trim();
    }

    private @Nullable AssertionSignal classifyRustEqLikeMacro(
            TSNode macro, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> exprs = new ArrayList<>();
        collectNodesByType(macro, Set.of(nodeType(EXPRESSION)), exprs);
        if (exprs.size() < 2) {
            exprs = rustMacroArgumentNodes(macro);
        }
        if (exprs.size() < 2) {
            return null;
        }
        TSNode left = exprs.getFirst();
        TSNode right = exprs.get(1);

        int score = 0;
        boolean shallow = false;
        boolean meaningful = true;
        String kind = "";
        var reasons = new ArrayList<String>();

        boolean leftIsNone = isRustNoneExpr(left, sourceContent);
        boolean rightIsNone = isRustNoneExpr(right, sourceContent);
        boolean leftConst = isRustConstantExpr(left, sourceContent);
        boolean rightConst = isRustConstantExpr(right, sourceContent);

        if (leftConst && rightConst) {
            score += weights.constantEqualityWeight();
            reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
            kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
            meaningful = false;
        } else if (sameRustExpr(left, right, sourceContent)) {
            score += weights.tautologicalAssertionWeight();
            reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
            kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
            meaningful = false;
        } else if (leftIsNone || rightIsNone) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            shallow = true;
            meaningful = false;
        }

        boolean overspecified =
                containsLargeRustStringLiteral(macro, sourceContent, weights.largeLiteralLengthThreshold());
        if (overspecified) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
            meaningful = false;
        }

        if (score <= 0) {
            return null;
        }

        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                macro.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(macro));
    }

    private @Nullable AssertionSignal classifyRustAssertMacro(
            TSNode macro, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> exprs = new ArrayList<>();
        collectNodesByType(macro, Set.of(nodeType(EXPRESSION)), exprs);
        if (exprs.isEmpty()) {
            exprs = rustMacroArgumentNodes(macro);
        }
        if (exprs.isEmpty()) return null;
        TSNode condition = exprs.getFirst();

        int score = 0;
        boolean shallow = false;
        boolean meaningful = true;
        String kind = "";
        var reasons = new ArrayList<String>();

        String condText = sourceContent.substringFrom(condition).strip();
        if ("true".equals(condText) || "false".equals(condText)) {
            score += weights.constantTruthWeight();
            reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
            kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
            meaningful = false;
        } else if (isRustNoneExpr(condition, sourceContent)
                || isRustIsNoneMethodCall(condition, sourceContent)
                || isRustNoneEqualityOrInequality(condition, sourceContent)) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            shallow = true;
            meaningful = false;
        }

        boolean overspecified =
                containsLargeRustStringLiteral(macro, sourceContent, weights.largeLiteralLengthThreshold());
        if (overspecified) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
            meaningful = false;
        }

        if (score <= 0) {
            return null;
        }

        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                macro.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(macro));
    }

    private @Nullable AssertionSignal classifyRustMatchesMacro(
            TSNode macro, SourceContent sourceContent, TestAssertionWeights weights) {
        boolean overspecified =
                containsLargeRustStringLiteral(macro, sourceContent, weights.largeLiteralLengthThreshold());
        if (!overspecified) return null;

        return new AssertionSignal(
                TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL,
                weights.overspecifiedLiteralWeight(),
                false,
                false,
                macro.getStartByte(),
                List.of(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL),
                sourceContent.substringFrom(macro));
    }

    private static List<TSNode> rustMacroArgumentNodes(TSNode macro) {
        var args = new ArrayList<TSNode>();
        TSNode tokenTree = null;

        int childCount = macro.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = macro.getChild(i);
            if (child != null && nodeType(TOKEN_TREE).equals(child.getType())) {
                tokenTree = child;
                break;
            }
        }
        if (tokenTree == null) {
            var tokenTrees = new ArrayList<TSNode>();
            collectNodesByType(macro, Set.of(nodeType(TOKEN_TREE)), tokenTrees);
            if (!tokenTrees.isEmpty()) {
                tokenTree = tokenTrees.getFirst();
            }
        }
        if (tokenTree == null) {
            return args;
        }

        int namedCount = tokenTree.getNamedChildCount();
        for (int i = 0; i < namedCount; i++) {
            TSNode child = tokenTree.getNamedChild(i);
            if (child == null) continue;
            String type = child.getType();
            if (type == null) continue;
            // Keep argument-like nodes; ignore punctuation-like wrappers if present.
            if (nodeType(ATTRIBUTE_ITEM).equals(type)) continue;
            args.add(child);
        }
        return args;
    }

    private static boolean containsLargeRustStringLiteral(TSNode root, SourceContent sourceContent, int threshold) {
        var stringLits = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(STRING_LITERAL)), stringLits);
        if (stringLits.isEmpty()) return false;
        for (TSNode lit : stringLits) {
            if (sourceContent.substringFrom(lit).length() >= threshold) {
                return true;
            }
        }
        return false;
    }

    private Set<TSNode> rustFunctionsWithDirectTestAttribute(TSNode root, SourceContent sourceContent) {
        var testFunctions = new HashSet<TSNode>();
        for (TSNode attrItem : testAttributeItems(root, sourceContent)) {
            if (!isRustTestAttributeItem(attrItem, sourceContent)) {
                continue;
            }

            // Shape 1: attribute_item is nested under the function_item.
            TSNode parent = attrItem.getParent();
            if (parent != null && nodeType(FUNCTION_ITEM).equals(parent.getType())) {
                testFunctions.add(parent);
                continue;
            }

            // Shape 2: attribute_item is a sibling immediately preceding the function_item.
            TSNode next = attrItem.getNextSibling();
            while (next != null) {
                String nextType = next.getType();
                if (nodeType(ATTRIBUTE_ITEM).equals(nextType)) {
                    next = next.getNextSibling();
                    continue;
                }
                if (nodeType(FUNCTION_ITEM).equals(nextType)) {
                    testFunctions.add(next);
                }
                break;
            }
        }
        return testFunctions;
    }

    private static boolean isRustNoneExpr(TSNode expr, SourceContent sourceContent) {
        return "None".equals(sourceContent.substringFrom(expr).strip());
    }

    private static boolean isRustIsNoneMethodCall(TSNode expr, SourceContent sourceContent) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(expr, Set.of(nodeType(CALL_EXPRESSION)), calls);
        if (calls.isEmpty()) return false;

        for (TSNode call : calls) {
            var fieldExprs = new ArrayList<TSNode>();
            collectNodesByType(call, Set.of(nodeType(FIELD_EXPRESSION)), fieldExprs);
            if (fieldExprs.isEmpty()) continue;

            for (TSNode fieldExpr : fieldExprs) {
                var idNodes = new ArrayList<TSNode>();
                collectNodesByType(fieldExpr, Set.of(nodeType(IDENTIFIER)), idNodes);
                for (TSNode id : idNodes) {
                    if ("is_none".equals(sourceContent.substringFrom(id).strip())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isRustNoneEqualityOrInequality(TSNode expr, SourceContent sourceContent) {
        var bins = new ArrayList<TSNode>();
        collectNodesByType(expr, Set.of(nodeType(BINARY_EXPRESSION)), bins);
        if (bins.isEmpty()) return false;

        for (TSNode bin : bins) {
            TSNode left = bin.getChildByFieldName(nodeField(RustNodeField.LEFT));
            TSNode right = bin.getChildByFieldName(nodeField(RustNodeField.RIGHT));
            if (left == null || right == null) continue;

            String op = firstUnnamedChildType(bin);
            boolean leftIsNone = isRustNoneExpr(left, sourceContent);
            boolean rightIsNone = isRustNoneExpr(right, sourceContent);
            if (("==".equals(op) && (leftIsNone || rightIsNone)) || ("!=".equals(op) && (leftIsNone || rightIsNone))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRustConstantExpr(TSNode expr, SourceContent sourceContent) {
        // `true`/`false` are represented by the named `boolean_literal` node in the Rust grammar.
        // `None` is an identifier (so we still need text-based matching).
        if (hasDescendantOfType(expr, nodeType(BOOLEAN_LITERAL))) {
            return true;
        }
        if (isRustNoneExpr(expr, sourceContent)) {
            return true;
        }
        return hasDescendantOfType(expr, nodeType(STRING_LITERAL))
                || hasDescendantOfType(expr, nodeType(CHAR_LITERAL))
                || hasDescendantOfType(expr, nodeType(INTEGER_LITERAL))
                || hasDescendantOfType(expr, nodeType(FLOAT_LITERAL))
                || hasDescendantOfType(expr, nodeType(BOOLEAN_LITERAL));
    }

    private List<TSNode> testAttributeItems(TSNode root, SourceContent sourceContent) {
        if (root == null) return List.of();
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    var attrs = new ArrayList<TSNode>();
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, root, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (!TEST_MARKER_CAPTURE.equals(captureName)) continue;

                                TSNode attrItemNode = capture.getNode();
                                if (attrItemNode == null) continue;
                                if (isRustTestContextAttributeItem(attrItemNode, sourceContent)) {
                                    attrs.add(attrItemNode);
                                }
                            }
                        }
                    }
                    return attrs;
                },
                List.of());
    }

    private static boolean isRustTestAttributeItem(TSNode attrItemNode, SourceContent sourceContent) {
        var ids = rustAttributeIdentifiers(attrItemNode, sourceContent);
        // `#[test]` parses to identifier list containing `test` (typically as the first identifier).
        return !ids.isEmpty() && "test".equals(ids.getFirst());
    }

    private static boolean isRustTestContextAttributeItem(TSNode attrItemNode, SourceContent sourceContent) {
        var ids = rustAttributeIdentifiers(attrItemNode, sourceContent);
        if (ids.isEmpty()) {
            return false;
        }
        // test context is either:
        //  - `#[test]` (first identifier is test), or
        //  - `#[cfg(test)]` (first identifier cfg and one nested identifier test).
        if ("test".equals(ids.getFirst())) {
            return true;
        }
        return "cfg".equals(ids.getFirst()) && ids.stream().anyMatch("test"::equals);
    }

    private static List<String> rustAttributeIdentifiers(TSNode attrItemNode, SourceContent sourceContent) {
        var idNodes = new ArrayList<TSNode>();
        collectNodesByType(attrItemNode, Set.of(nodeType(IDENTIFIER)), idNodes);
        if (idNodes.isEmpty()) {
            return List.of();
        }
        var ids = new ArrayList<String>(idNodes.size());
        for (TSNode idNode : idNodes) {
            ids.add(sourceContent.substringFrom(idNode).strip());
        }
        return ids;
    }

    private static boolean sameRustExpr(TSNode left, TSNode right, SourceContent sourceContent) {
        TSNode l = unwrapRustParenthesized(left);
        TSNode r = unwrapRustParenthesized(right);

        if (l == null || r == null) return false;
        String lType = l.getType();
        String rType = r.getType();
        if (lType == null || rType == null || !lType.equals(rType)) return false;

        String type = lType;
        // For the most common leaf nodes, compare the exact CST text (trimmed), not whitespace-normalized strings.
        if (type.equals("identifier") || type.equals("scoped_identifier")) {
            return sourceContent
                    .substringFrom(l)
                    .strip()
                    .equals(sourceContent.substringFrom(r).strip());
        }
        if (type.equals(nodeType(BOOLEAN_LITERAL))
                || type.equals(nodeType(CHAR_LITERAL))
                || type.equals(nodeType(STRING_LITERAL))
                || type.equals(nodeType(INTEGER_LITERAL))
                || type.equals(nodeType(FLOAT_LITERAL))) {
            return sourceContent
                    .substringFrom(l)
                    .strip()
                    .equals(sourceContent.substringFrom(r).strip());
        }

        if (type.equals("binary_expression")) {
            return sameRustBinaryExpr(l, r, sourceContent);
        }

        // Conservative fallback: compare CST text for the whole expression.
        return sourceContent
                .substringFrom(l)
                .strip()
                .equals(sourceContent.substringFrom(r).strip());
    }

    private static TSNode unwrapRustParenthesized(TSNode n) {
        TSNode current = n;
        // Be defensive: unwrap up to 2 layers of `parenthesized_expression`.
        for (int i = 0; i < 2; i++) {
            if (current == null) return n;
            if (!"parenthesized_expression".equals(current.getType())) break;
            if (current.getNamedChildCount() == 0) break;
            TSNode child = current.getNamedChild(0);
            if (child == null) break;
            current = child;
        }
        return current;
    }

    private static boolean sameRustBinaryExpr(TSNode left, TSNode right, SourceContent sourceContent) {
        TSNode lLeft = left.getChildByFieldName(nodeField(RustNodeField.LEFT));
        TSNode lRight = left.getChildByFieldName(nodeField(RustNodeField.RIGHT));
        TSNode rLeft = right.getChildByFieldName(nodeField(RustNodeField.LEFT));
        TSNode rRight = right.getChildByFieldName(nodeField(RustNodeField.RIGHT));
        if (lLeft == null || lRight == null || rLeft == null || rRight == null) {
            return false;
        }

        if (!sameRustExpr(lLeft, rLeft, sourceContent)) return false;
        if (!sameRustExpr(lRight, rRight, sourceContent)) return false;

        // `operator` is typically an unnamed child token (e.g. "==" or "!=").
        return firstUnnamedChildType(left).equals(firstUnnamedChildType(right));
    }

    private static String firstUnnamedChildType(TSNode node) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null) continue;
            if (!child.isNamed()) {
                String t = child.getType();
                return t == null ? "" : t;
            }
        }
        return "";
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        TSNode root = tree.getRootNode();
        if (root == null) return false;
        return !testAttributeItems(root, sourceContent).isEmpty();
    }
}
