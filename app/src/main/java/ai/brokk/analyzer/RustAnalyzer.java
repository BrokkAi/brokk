package ai.brokk.analyzer;

import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.IProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

public final class RustAnalyzer extends TreeSitterAnalyzer implements MacroExpansionProvider {
    private static final Logger log = LoggerFactory.getLogger(RustAnalyzer.class);

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForRust(reference);
    }

    private static final LanguageSyntaxProfile RS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(IMPL_ITEM, TRAIT_ITEM, STRUCT_ITEM, ENUM_ITEM, MOD_ITEM),
            Set.of(FUNCTION_ITEM, FUNCTION_SIGNATURE_ITEM),
            Set.of(FIELD_DECLARATION, CONST_ITEM, STATIC_ITEM, ENUM_VARIANT),
            Set.of(ATTRIBUTE_ITEM), // Rust attributes like #[derive(...)]
            Set.of(),
            IMPORT_DECLARATION,
            "name", // Common field name for identifiers
            "body", // e.g., function_item.body, impl_item.body
            "parameters", // e.g., function_item.parameters
            "return_type", // e.g., function_item.return_type
            "type_parameters", // Rust generics
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.IMPL_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.MODULE_DEFINITION, SkeletonType.MODULE_STATEMENT,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "",
            Set.of(VISIBILITY_MODIFIER));

    public RustAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public RustAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.RUST, listener);
    }

    private RustAnalyzer(
            IProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.RUST, state, listener, cache);
    }

    public static RustAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
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
            case MACROS -> Optional.of("treesitter/rust/macros.scm");
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return RS_SYNTAX_PROFILE;
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
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (!child.isNull() && VISIBILITY_MODIFIER.equals(child.getType())) {
                String text = sourceContent.substringFrom(child).strip();
                return text + " ";
            }
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
        if (!bodyNode.isNull()) {
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
        if (typeNode == null || typeNode.isNull()) {
            return Optional.empty();
        }

        String nodeType = typeNode.getType();
        return switch (nodeType) {
            case TYPE_IDENTIFIER -> Optional.of(sourceContent.substringFrom(typeNode));

            case SCOPED_TYPE_IDENTIFIER -> {
                TSNode nameNode = typeNode.getChildByFieldName("name");
                yield extractCoreTypeName(nameNode, sourceContent)
                        .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
            }

            case GENERIC_TYPE, REFERENCE_TYPE, POINTER_TYPE -> {
                TSNode innerType = typeNode.getChildByFieldName("type");
                yield extractCoreTypeName(innerType, sourceContent)
                        .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
            }

            case ARRAY_TYPE -> {
                // Array/slice types like [T] or [T; N] have "element" field for the inner type
                TSNode elementType = typeNode.getChildByFieldName("element");
                yield extractCoreTypeName(elementType, sourceContent)
                        .or(() -> Optional.of(sourceContent.substringFrom(typeNode)));
            }

            case TUPLE_TYPE -> {
                // Tuple types like (A, B) - extract the first element for naming
                // The tuple_type node has children that are the element types
                if (typeNode.getChildCount() > 0) {
                    for (int i = 0; i < typeNode.getChildCount(); i++) {
                        TSNode child = typeNode.getChild(i);
                        if (child != null && !child.isNull()) {
                            String childType = child.getType();
                            // Skip punctuation like '(' ',' ')'
                            if (!childType.equals("(") && !childType.equals(")") && !childType.equals(",")) {
                                Optional<String> extracted = extractCoreTypeName(child, sourceContent);
                                if (extracted.isPresent()) {
                                    yield extracted;
                                }
                            }
                        }
                    }
                }
                yield Optional.of(sourceContent.substringFrom(typeNode));
            }

            default -> {
                String text = sourceContent.substringFrom(typeNode);
                log.debug("extractCoreTypeName: unhandled node type '{}', using full text '{}'", nodeType, text);
                yield Optional.of(text);
            }
        };
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, SourceContent sourceContent) {
        if (IMPL_ITEM.equals(decl.getType())) {
            TSNode typeNode = decl.getChildByFieldName("type");
            if (typeNode != null && !typeNode.isNull()) {
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
        return Set.of(ATTRIBUTE_ITEM, INNER_ATTRIBUTE);
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, tree.getRootNode());

                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (TEST_MARKER.equals(captureName)) {
                                    // The capture is now directly on the attribute_item node
                                    TSNode attrItemNode = capture.getNode();
                                    if (attrItemNode != null && !attrItemNode.isNull()) {
                                        String content = sourceContent.substringFrom(attrItemNode);
                                        // Rust attributes look like #[test] or #[cfg(test)]
                                        if (content.contains("#[test]") || content.contains("#[cfg(test)]")) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }
}
