package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.*;

import java.util.Map;
import java.util.Set;

public class JavaTreeSitterAnalyzer extends TreeSitterAnalyzer {

    @Nullable
    private final ThreadLocal<TSQuery> packageQuery;

    public JavaTreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.JAVA, excludedFiles);
        this.packageQuery = ThreadLocal.withInitial(() -> {
            try {
                return new TSQuery(getTSLanguage(), "(package_clause (package_identifier) @name)");
            } catch (RuntimeException e) {
                // Log and rethrow to indicate a critical setup error for this thread's query.
                log.error("Failed to compile packageQuery for GoAnalyzer ThreadLocal", e);
                throw e;
            }
        });
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterJava();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/java.scm";
    }

    private static final LanguageSyntaxProfile JAVA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration", "annotation_type_declaration"),
            Set.of("method_declaration", "constructor_declaration"),
            Set.of("field_declaration", "enum_constant"),
            Set.of("annotation", "marker_annotation"),
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    "definition.class", SkeletonType.CLASS_LIKE,
                    "definition.interface", SkeletonType.CLASS_LIKE,
                    "definition.enum", SkeletonType.CLASS_LIKE,
                    "definition.record", SkeletonType.CLASS_LIKE,
                    "definition.annotation", SkeletonType.CLASS_LIKE, // for @interface
                    "definition.method", SkeletonType.FUNCTION_LIKE,
                    "definition.constructor", SkeletonType.FUNCTION_LIKE,
                    "definition.field", SkeletonType.FIELD_LIKE,
                    "definition.enum.constant", SkeletonType.FIELD_LIKE
            ),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
    );

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JAVA_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        final String fqName = classChain + "." + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);
        var type = switch (skeletonType) {
            case CLASS_LIKE -> CodeUnitType.CLASS;
            case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
            case FIELD_LIKE -> CodeUnitType.FIELD;
            default -> {
                // This shouldn't be reached if captureConfiguration is exhaustive
                log.warn("Unhandled CodeUnitType for '{}'", skeletonType);
                yield CodeUnitType.CLASS;
            }
        };

        return new CodeUnit(file, type, packageName, fqName);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        TSQuery currentPackageQuery;
        if (this.packageQuery != null) { // Check if JavaTreeSitterAnalyzer constructor has initialized the ThreadLocal field
            currentPackageQuery = this.packageQuery.get();
        } else {
            // This block executes if determinePackageName is called during TreeSitterAnalyzer's constructor,
            // before this.packageQuery (ThreadLocal) is initialized in JavaTreeSitterAnalyzer's constructor.
            log.trace("JavaTreeSitterAnalyzer.determinePackageName: packageQuery ThreadLocal is null, creating temporary query for file {}", file);
            try {
                currentPackageQuery = new TSQuery(getTSLanguage(), "(package_clause (package_identifier) @name)");
            } catch (RuntimeException e) {
                log.error("Failed to compile temporary package query for GoAnalyzer in determinePackageName for file {}: {}", file, e.getMessage(), e);
                return ""; // Cannot proceed without the query
            }
        }
        TSQueryCursor cursor = new TSQueryCursor();
        try {
            cursor.exec(currentPackageQuery, rootNode);
            TSQueryMatch match = new TSQueryMatch(); // Reusable match object

            if (cursor.nextMatch(match)) { // Assuming only one package declaration per Go file
                for (TSQueryCapture capture : match.getCaptures()) {
                    // The query "(package_clause (package_identifier) @name)" captures the package_identifier node with name "name"
                    if ("name".equals(currentPackageQuery.getCaptureNameForId(capture.getIndex()))) {
                        TSNode nameNode = capture.getNode();
                        if (nameNode != null && !nameNode.isNull()) {
                            return textSlice(nameNode, src).trim();
                        }
                    }
                }
            } else {
                log.warn("No package declaration found in Go file: {}", file);
            }
        } catch (Exception e) {
            log.error("Error while determining package name for Go file {}: {}", file, e.getMessage(), e);
        }
        // TSQueryCursor does not appear to have a close() method or implement AutoCloseable.
        // Assuming its resources are managed by GC or when its associated TSQuery/TSTree are GC'd.
        return ""; // Default if no package name found or an error occurs
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return assembleClassSignature(classNode, src, exportPrefix, signatureText, baseIndent);
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportAndModifierPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        var signature = indent + exportAndModifierPrefix + typeParams + returnType + functionName + paramsText;

        var throwsNode = funcNode.getChildByFieldName("throws");
        if (throwsNode != null) {
            signature += " " + textSlice(throwsNode, src);
        }

        return signature;
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}";
    }
}
