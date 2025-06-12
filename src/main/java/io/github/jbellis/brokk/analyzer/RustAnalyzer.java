package io.github.jbellis.brokk.analyzer;
import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger; // Added import
import org.slf4j.LoggerFactory; // Added import
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RustAnalyzer extends TreeSitterAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(RustAnalyzer.class); // Added logger

    // RS_LANGUAGE field removed, createTSLanguage will provide new instances.

    private static final LanguageSyntaxProfile RS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            /* classLikeNodeTypes  */ Set.of("impl_item", "trait_item", "struct_item", "enum_item"),
            /* functionLikeNodes   */ Set.of("function_item", "function_signature_item"),
            /* fieldLikeNodes      */ Set.of("field_declaration", "const_item", "static_item", "enum_variant"),
            /* decoratorNodes      */ Set.of("attribute_item"), // Rust attributes like #[derive(...)]
            /* identifierFieldName */ "name", // Common field name for identifiers
            /* bodyFieldName       */ "body", // e.g., function_item.body, impl_item.body
            /* parametersFieldName */ "parameters", // e.g., function_item.parameters
            /* returnTypeFieldName */ "return_type", // e.g., function_item.return_type
            /* capture → Skeleton  */ Map.of(
                    "class.definition", SkeletonType.CLASS_LIKE,    // For struct, trait, enum
                    "impl.definition", SkeletonType.CLASS_LIKE,     // For impl blocks
                    "function.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE
            ),
            /* async keyword node   */ "", // Rust 'async' is a modifier, not a distinct node type like in JS. Handled by signature text.
            /* modifier node types  */ Set.of("visibility_modifier") // For `pub`, `pub(crate)`, etc.
    );

    public RustAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.RUST, excludedFiles);
    }

    public RustAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    /* ---------- TreeSitterAnalyzer hooks ---------- */

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterRust();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/rust.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return RS_SYNTAX_PROFILE;
    }

    /**
     * Determines the Rust module path for a given file.
     * This considers common Rust project structures like `src/` layouts,
     * `lib.rs`, `main.rs` as crate roots, and `mod.rs` for directory modules.
     *
     * @param file The project file being analyzed.
     * @param defNode The TSNode representing the definition (unused in this implementation).
     * @param rootNode The root TSNode of the file's syntax tree (unused in this implementation).
     * @param src The source code of the file (unused in this implementation).
     * @return The module path string (e.g., "foo.bar"), or an empty string for the crate root.
     */
    @Override
    protected String determinePackageName(ProjectFile file, TSNode defNode, TSNode rootNode, String src) {
        Path projectRoot = getProject().getRoot();
        Path absFilePath = file.absPath();
        Path fileParentDir = absFilePath.getParent();

        if (fileParentDir == null) {
            // This should ideally not happen for valid project files
            log.warn("File {} has no parent directory. Defaulting to empty package name.", absFilePath);
            return "";
        }

        // Determine the effective root for module path calculation (project_root/src/ or project_root/)
        Path srcDirectory = projectRoot.resolve("src");
        boolean usesSrcLayout = Files.isDirectory(srcDirectory) && absFilePath.startsWith(srcDirectory);
        Path effectiveModuleRoot = usesSrcLayout ? srcDirectory : projectRoot;

        String fileNameStr = absFilePath.getFileName().toString();

        // If the file is lib.rs or main.rs, and its parent directory is the effectiveModuleRoot,
        // it's considered the crate root module (empty package path).
        if ((fileNameStr.equals("lib.rs") || fileNameStr.equals("main.rs")) && fileParentDir.equals(effectiveModuleRoot)) {
            return "";
        }

        // Calculate the path of the file's directory relative to the effectiveModuleRoot.
        Path relativeDirFromModuleRoot;
        if (fileParentDir.startsWith(effectiveModuleRoot)) {
            relativeDirFromModuleRoot = effectiveModuleRoot.relativize(fileParentDir);
        } else if (fileParentDir.startsWith(projectRoot)) {
            // Fallback: file is not under effectiveModuleRoot (e.g. src/) but is under projectRoot.
            // This can happen if usesSrcLayout was true but the file is in a sibling dir to src, e.g. project_root/examples/
            // Treat as relative to projectRoot in such cases.
            relativeDirFromModuleRoot = projectRoot.relativize(fileParentDir);
            log.trace("File {} not in effective module root {}, calculating package relative to project root {}.", absFilePath, effectiveModuleRoot, projectRoot);
        } else {
            // File path is outside the project root, which is highly unexpected.
            log.warn("File {} is outside the project root {}. Defaulting to empty package name.", absFilePath, projectRoot);
            return "";
        }

        String relativeDirModulePath = relativeDirFromModuleRoot.toString()
            .replace(absFilePath.getFileSystem().getSeparator(), ".");
        // Path.toString() on an empty path (e.g., if fileParentDir is effectiveModuleRoot) results in an empty string.
        // Ensure that leading/trailing dots from malformed paths or separator replacement are handled if necessary,
        // though Path relativize and toString usually behave well. Here, simple replacement is okay.
        if (".".equals(relativeDirModulePath) || relativeDirModulePath.startsWith(".निया")) { // Handle potential dot from root relativization
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
        log.warn("Could not determine Rust package name for non .rs file {} (relative dir path '{}'). Using directory path possibly with filename.",
                 absFilePath, relativeDirModulePath);
        return relativeDirModulePath.isEmpty() ? fileNameStr : relativeDirModulePath + "." + fileNameStr;
    }

    /** Map query captures → CodeUnit. */
    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName,
                                      String classChain) {
        log.trace("RustAnalyzer.createCodeUnit: File='{}', Capture='{}', SimpleName='{}', Package='{}', ClassChain='{}'",
                  file.getFileName(), captureName, simpleName, packageName, classChain);
        return switch (captureName) {
            // "class.definition" is for struct, trait, enum.
            // "impl.definition" is for impl blocks. Both create class-like CodeUnits.
            // simpleName for "impl.definition" will be the type being implemented (e.g., "Point").
            case "class.definition", "impl.definition" -> CodeUnit.cls(file, packageName, simpleName);
            case "function.definition" -> {
                // For methods, classChain will be the struct/impl type name.
                // For free functions, classChain will be empty.
                String fqSimpleName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, fqSimpleName);
            }
            case "field.definition" -> {
                // For struct fields, classChain is the struct name.
                // For top-level const/static, classChain is empty.
                String fieldShortName = classChain.isEmpty() ? "_module_." + simpleName
                                                            : classChain + "." + simpleName;
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            default -> {
                log.warn("Unhandled capture name in RustAnalyzer.createCodeUnit: '{}' for simple name '{}' in file '{}'. Returning null.",
                         captureName, simpleName, file.getFileName());
                yield null;
            }
        };
    }

    /* ---------- signature rendering ---------- */

    @Override
    protected String bodyPlaceholder() {
        return "..."; 
    } // For functions/methods with bodies

    /** Detects visibility modifiers like `pub`, `pub(crate)`. */
    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        // A common pattern for Rust grammar is that visibility_modifier is a direct child.
        // We check the first few children as its position can vary slightly (e.g. after attributes).
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull() && "visibility_modifier".equals(child.getType())) {
                String text = textSlice(child, src).strip();
                return text + " ";
            }
        }
        return "";
    }

    @Override
    protected String renderFunctionDeclaration(TSNode fnNode,
                                               String src,
                                               String exportPrefix, // Visibility: "pub ", "pub(crate) ", or ""
                                               String asyncPrefix,  // Async: "async " or "" (base class tries to populate this)
                                               String functionName,
                                               String paramsText,
                                               String returnTypeText,
                                               String indent) { // indent is "" when called from buildSignatureString

        String rt = returnTypeText.isBlank() ? "" : " -> " + returnTypeText;
        // exportPrefix is from getVisibilityPrefix. asyncPrefix from base class logic.
        String header = String.format("%s%s%sfn %s%s%s", indent, exportPrefix, asyncPrefix, functionName, paramsText, rt).stripLeading();

        TSNode bodyNode = fnNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode != null && !bodyNode.isNull()) {
            // For functions/methods with a body
            return header + " { " + bodyPlaceholder() + " }";
        } else {
            // For function signatures without a body (e.g., in traits)
            return header + ";";
        }
    }

    @Override
    protected String renderClassHeader(TSNode classNode, // e.g., struct_item, trait_item, impl_item
                                       String src,
                                       String exportPrefix, // Visibility
                                       String signatureText, // Base signature like "struct Foo", "trait Bar", "impl Point"
                                       String baseIndent) { // baseIndent is "" when called from buildSignatureString

        // signatureText is derived by TreeSitterAnalyzer using textSlice up to the body or end of node.
        // For Rust, this text (e.g. "struct Foo", "impl Point for Bar") is what we want, prefixed by visibility.
        return baseIndent + exportPrefix + signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        // Rust structs, enums, traits, impls all use "}"
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // If queries have helper captures like @_.type or similar that aren't actual definitions, list them here.
        // For now, assuming all captures ending in .name or .definition are intentional.
        return Set.of();
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        if ("impl_item".equals(decl.getType())) {
            TSNode typeNode = decl.getChildByFieldName("type");
            // In `impl Trait for Type`, typeNode is `Type`.
            // In `impl Type`, typeNode is `Type`.
            if (typeNode != null && !typeNode.isNull()) {
                String typeNodeType = typeNode.getType();
                return switch (typeNodeType) {
                    case "type_identifier" -> Optional.of(textSlice(typeNode, src));
                    case "generic_type" -> {
                        TSNode genericTypeNameNode = typeNode.getChildByFieldName("type");
                        if (genericTypeNameNode != null && !genericTypeNameNode.isNull() && "type_identifier".equals(genericTypeNameNode.getType())) {
                            yield Optional.of(textSlice(genericTypeNameNode, src));
                        }
                        String fullGenericTypeNodeText = textSlice(typeNode, src);
                        log.warn("RustAnalyzer.extractSimpleName for impl_item (generic_type): Could not extract specific name. Using full text '{}'. Node: {}",
                                 fullGenericTypeNodeText, textSlice(decl, src).lines().findFirst().orElse(""));
                        yield Optional.of(fullGenericTypeNodeText);
                    }
                    case "scoped_type_identifier" -> {
                        TSNode scopedNameNode = typeNode.getChildByFieldName("name");
                        if (scopedNameNode != null && !scopedNameNode.isNull() && "type_identifier".equals(scopedNameNode.getType())) {
                            yield Optional.of(textSlice(scopedNameNode, src));
                        }
                        String fullScopedTypeNodeText = textSlice(typeNode, src);
                        log.warn("RustAnalyzer.extractSimpleName for impl_item (scoped_type_identifier): Could not extract specific name. Using full text '{}'. Node: {}",
                                 fullScopedTypeNodeText, textSlice(decl, src).lines().findFirst().orElse(""));
                        yield Optional.of(fullScopedTypeNodeText);
                    }
                    default -> {
                        String fullTypeNodeText = textSlice(typeNode, src);
                        log.warn("RustAnalyzer.extractSimpleName for impl_item: Unhandled type node structure '{}'. Using full text '{}'. Node: {}",
                                 typeNodeType, fullTypeNodeText, textSlice(decl, src).lines().findFirst().orElse(""));
                        yield Optional.of(fullTypeNodeText);
                    }
                };
            }
            // If typeNode is null or isNull
            String errorContext = String.format("Node type %s (text: '%s')",
                                                decl.getType(),
                                                textSlice(decl, src).lines().findFirst().orElse("").trim());
            throw new IllegalStateException("RustAnalyzer.extractSimpleName for impl_item: 'type' field not found or null. Cannot determine simple name for " + errorContext);
        } // This closing brace was correctly placed after the previous edit.

        // For all other node types, defer to the base class implementation.
        // If super returns empty, throw.
        Optional<String> nameFromSuper = super.extractSimpleName(decl, src);
        if (nameFromSuper.isEmpty()) {
            String errorContext = String.format("Node type %s (text: '%s')",
                                                decl.getType(),
                                                textSlice(decl, src).lines().findFirst().orElse("").trim());
            throw new IllegalStateException("super.extractSimpleName (from RustAnalyzer) failed to find a name for " + errorContext);
        }
        return nameFromSuper;
    }
}
