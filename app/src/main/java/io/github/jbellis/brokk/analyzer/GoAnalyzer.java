package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSQueryCapture;
import org.treesitter.TreeSitterGo;

import java.util.Collections;
import java.util.Set;

public final class GoAnalyzer extends TreeSitterAnalyzer {
    static final Logger log = LoggerFactory.getLogger(GoAnalyzer.class); // Changed to package-private

    // GO_LANGUAGE field removed, createTSLanguage will provide new instances.
    private static final LanguageSyntaxProfile GO_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("type_spec"), // classLikeNodeTypes
            Set.of("function_declaration", "method_declaration"), // functionLikeNodeTypes
            Set.of("var_spec", "const_spec"), // fieldLikeNodeTypes
            Set.of(), // decoratorNodeTypes (Go doesn't have them in the typical sense)
            "name",        // identifierFieldName (used as fallback if specific .name capture is missing)
            "body",        // bodyFieldName (e.g. function_declaration.body -> block)
            "parameters",  // parametersFieldName
            "result",      // returnTypeFieldName (Go's grammar uses "result" for return types)
            "type_parameters", // typeParametersFieldName (Go generics)
            java.util.Map.of(
              "function.definition", SkeletonType.FUNCTION_LIKE,
              "type.definition", SkeletonType.CLASS_LIKE,
              "variable.definition", SkeletonType.FIELD_LIKE,
              "constant.definition", SkeletonType.FIELD_LIKE,
              "struct.field.definition", SkeletonType.FIELD_LIKE,
              "method.definition", SkeletonType.FUNCTION_LIKE,
              "interface.method.definition", SkeletonType.FUNCTION_LIKE // Added for interface methods
            ),        // captureConfiguration
            "", // asyncKeywordNodeType (Go uses 'go' keyword, not an async modifier on func signature)
            Set.of() // modifierNodeTypes (Go visibility is by capitalization)
    );
    
    @Nullable
    private final ThreadLocal<TSQuery> packageQuery;

    public GoAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.GO, excludedFiles);
        // Initialize the ThreadLocal for the package query.
        // getTSLanguage() is safe to call here and will provide a thread-specific TSLanguage.
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

    public GoAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterGo();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/go.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return GO_SYNTAX_PROFILE;
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        TSQuery currentPackageQuery;
        if (this.packageQuery != null) { // Check if GoAnalyzer constructor has initialized the ThreadLocal field
            currentPackageQuery = this.packageQuery.get();
        } else {
            // This block executes if determinePackageName is called during TreeSitterAnalyzer's constructor,
            // before this.packageQuery (ThreadLocal) is initialized in GoAnalyzer's constructor.
            log.trace("GoAnalyzer.determinePackageName: packageQuery ThreadLocal is null, creating temporary query for file {}", file);
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
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file,
                                                String captureName,
                                                String simpleName,
                                                String packageName,
                                                String classChain) {
        log.trace("GoAnalyzer.createCodeUnit: File='{}', Capture='{}', SimpleName='{}', Package='{}', ClassChain='{}'",
                  file.getFileName(), captureName, simpleName, packageName, classChain);

        return switch (captureName) {
            case "function.definition" -> {
                log.trace("Creating FN CodeUnit for Go function: File='{}', Pkg='{}', Name='{}'", file.getFileName(), packageName, simpleName);
                yield CodeUnit.fn(file, packageName, simpleName);
            }
            case "type.definition" -> { // Covers struct_type and interface_type
                log.trace("Creating CLS CodeUnit for Go type: File='{}', Pkg='{}', Name='{}'", file.getFileName(), packageName, simpleName);
                yield CodeUnit.cls(file, packageName, simpleName);
            }
            case "variable.definition", "constant.definition" -> {
                // For package-level variables/constants, classChain should be empty.
                // We adopt a convention like "_module_.simpleName" for the short name's member part.
                if (!classChain.isEmpty()) {
                    log.warn("Expected empty classChain for package-level var/const '{}', but got '{}'. Proceeding with _module_ convention.", simpleName, classChain);
                }
                String fieldShortName = "_module_." + simpleName;
                log.trace("Creating FIELD CodeUnit for Go package-level var/const: File='{}', Pkg='{}', Name='{}', Resulting ShortName='{}'",
                          file.getFileName(), packageName, simpleName, fieldShortName);
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            case "method.definition" -> {
                // simpleName is now expected to be ReceiverType.MethodName due to adjustments in TreeSitterAnalyzer
                // classChain is now expected to be ReceiverType
                log.trace("Creating FN CodeUnit for Go method: File='{}', Pkg='{}', Name='{}', ClassChain (Receiver)='{}'",
                          file.getFileName(), packageName, simpleName, classChain);
                // CodeUnit.fn will create FQN = packageName + "." + simpleName (e.g., declpkg.MyStruct.GetFieldA)
                // The parent-child relationship will be established by TreeSitterAnalyzer using classChain.
                yield CodeUnit.fn(file, packageName, simpleName);
            }
            case "struct.field.definition" -> {
                // simpleName is FieldName (e.g., "FieldA")
                // classChain is StructName (e.g., "MyStruct")
                // We want the CodeUnit's shortName to be "StructName.FieldName" for uniqueness and parenting.
                String fieldShortName = classChain + "." + simpleName;
                log.trace("Creating FIELD CodeUnit for Go struct field: File='{}', Pkg='{}', Struct='{}', Field='{}', Resulting ShortName='{}'",
                          file.getFileName(), packageName, classChain, simpleName, fieldShortName);
                yield CodeUnit.field(file, packageName, fieldShortName);
            }
            case "interface.method.definition" -> {
                // simpleName is MethodName (e.g., "DoSomething")
                // classChain is InterfaceName (e.g., "MyInterface")
                // We want the CodeUnit's shortName to be "InterfaceName.MethodName".
                String methodShortName = classChain + "." + simpleName;
                log.trace("Creating FN CodeUnit for Go interface method: File='{}', Pkg='{}', Interface='{}', Method='{}', Resulting ShortName='{}'",
                          file.getFileName(), packageName, classChain, simpleName, methodShortName);
                yield CodeUnit.fn(file, packageName, methodShortName);
            }
            default -> {
                log.warn("Unhandled capture name in GoAnalyzer.createCodeUnit: '{}' for simple name '{}' in file '{}'. Returning null.",
                         captureName, simpleName, file.getFileName());
                yield null; // Explicitly yield null for unhandled cases
            }
        };
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        log.trace("GoAnalyzer.renderFunctionDeclaration for node type '{}', functionName '{}'. Params: '{}', Return: '{}'", funcNode.getType(), functionName, paramsText, returnTypeText);
        String rt = !returnTypeText.isEmpty() ? " " + returnTypeText : "";
        String signature;
        if ("method_declaration".equals(funcNode.getType())) {
            TSNode receiverNode = funcNode.getChildByFieldName("receiver");
            String receiverText = "";
            if (receiverNode != null && !receiverNode.isNull()) {
                receiverText = textSlice(receiverNode, src).trim();
            }
            // paramsText from formatParameterList already includes parentheses for regular functions
            // For methods, paramsText is for the method's own parameters, not the receiver.
            signature = String.format("func %s %s%s%s%s", receiverText, functionName, typeParamsText, paramsText, rt);
            return signature + " { " + bodyPlaceholder() + " }";
        } else if ("method_elem".equals(funcNode.getType())) { // Interface method
            // Interface methods don't have 'func', receiver, or body placeholder in their definition.
            // functionName is the method name.
            // paramsText is the parameters (e.g., "()", "(p int)").
            // rt is the return type (e.g., " string", " (int, error)").
            // exportPrefix and asyncPrefix are not applicable here as part of the signature string.
            signature = String.format("%s%s%s%s", functionName, typeParamsText, paramsText, rt);
            return signature; // No " { ... }"
        } else { // For function_declaration
            signature = String.format("func %s%s%s%s", functionName, typeParamsText, paramsText, rt);
            return signature + " { " + bodyPlaceholder() + " }";
        }
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureTextParam, String baseIndent) {
        // classNode is the type_declaration node.
        // We need to extract "type Name kind" (e.g., "type MyStruct struct").
        // The signatureTextParam passed from TreeSitterAnalyzer might be too broad (containing the whole body).
        TSNode typeSpecNode = null;
        for (int i = 0; i < classNode.getNamedChildCount(); i++) {
            TSNode child = classNode.getNamedChild(i);
            if ("type_spec".equals(child.getType())) {
                typeSpecNode = child;
                break;
            }
        }

        if (typeSpecNode == null || typeSpecNode.isNull()) {
            log.warn("renderClassHeader for Go: type_spec child not found in classNode (type_declaration {}). Falling back to potentially incorrect signatureTextParam.", textSlice(classNode,src).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        TSNode nameNode = typeSpecNode.getChildByFieldName("name");
        TSNode kindNode = typeSpecNode.getChildByFieldName("type"); // This is the struct_type or interface_type node

        if (nameNode == null || nameNode.isNull() || kindNode == null || kindNode.isNull()) {
            log.warn("renderClassHeader for Go: name or kind node not found in type_spec for classNode {}. Falling back.", textSlice(classNode,src).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        String nameText = textSlice(nameNode, src);
        String kindText;
        String kindNodeType = kindNode.getType();

        if ("struct_type".equals(kindNodeType)) {
            kindText = "struct";
        } else if ("interface_type".equals(kindNodeType)) {
            kindText = "interface";
        } else {
            log.warn("renderClassHeader for Go: Unhandled kind node type '{}' for classNode {}. Falling back.", kindNodeType, textSlice(classNode,src).lines().findFirst().orElse(""));
            return signatureTextParam + " {";
        }

        // Go visibility is by capitalization, exportPrefix is not used here.
        String actualSignatureText = String.format("type %s %s", nameText, kindText).strip();
        log.trace("GoAnalyzer.renderClassHeader for node {}. Constructed signature: '{}'", textSlice(classNode,src).lines().findFirst().orElse(""), actualSignatureText);
        return actualSignatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        log.trace("Stage 0: getIgnoredCaptures called. Returning empty set.");
        return Set.of();
    }

    @Override
    protected boolean requiresSemicolons() {
        return false;
    }
}
