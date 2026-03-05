package ai.brokk.analyzer;

import static ai.brokk.analyzer.scala.ScalaTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.IProject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterScala;

public class ScalaAnalyzer extends TreeSitterAnalyzer {

    public ScalaAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public ScalaAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.SCALA, listener);
    }

    private ScalaAnalyzer(
            IProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.SCALA, state, listener, cache);
    }

    public static ScalaAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new ScalaAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new ScalaAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterScala();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/scala/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/scala/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return SCALA_SYNTAX_PROFILE;
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
        var effectiveSimpleName = simpleName;

        if (CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName)) {
            // This is a primary constructor, which is matched against the class name. This constructor is "implicit"
            // and needs to be created explicitly as follows.
            effectiveSimpleName = simpleName + "." + simpleName;
        } else if (simpleName.equals("this") && skeletonType.equals(SkeletonType.FUNCTION_LIKE)) {
            // This is a secondary constructor, which is named `this`. The simple name should be the class name.
            // The classChain is the simple name of the enclosing class.
            if (!classChain.isEmpty()) {
                var lastDot = classChain.lastIndexOf('.');
                effectiveSimpleName = lastDot < 0 ? classChain : classChain.substring(lastDot + 1);
            }
        }

        final String shortName = classChain.isEmpty() ? effectiveSimpleName : classChain + "." + effectiveSimpleName;

        var type =
                switch (skeletonType) {
                    case CLASS_LIKE -> CodeUnitType.CLASS;
                    case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
                    case FIELD_LIKE -> CodeUnitType.FIELD;
                    case MODULE_STATEMENT -> CodeUnitType.MODULE;
                    default -> {
                        // This shouldn't be reached if captureConfiguration is exhaustive
                        log.warn("Unhandled CodeUnitType for '{}'", skeletonType);
                        yield CodeUnitType.CLASS;
                    }
                };

        return new CodeUnit(file, type, packageName, shortName);
    }

    @Override
    protected String determineClassName(String nodeType, String shortName) {
        if (OBJECT_DEFINITION.equals(nodeType)) {
            // Companion objects append '$' on a bytecode level to avoid naming conflicts
            return shortName + "$";
        } else {
            return shortName;
        }
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        return JavaAnalyzer.determineJvmPackageName(
                rootNode,
                sourceContent,
                PACKAGE_CLAUSE,
                SCALA_SYNTAX_PROFILE.classLikeNodeTypes(),
                (node, sourceContent1) -> sourceContent1.substringFromBytes(node.getStartByte(), node.getEndByte()));
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}"; // We'll stick to Scala 2 closers
    }

    @Override
    protected boolean requiresSemicolons() {
        return false; // Scala does not require semicolons
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        return signatureText + " {"; // For consistency with closers, we need to open with Scala 2-style braces
    }

    @Override
    protected String bodyPlaceholder() {
        return "= {...}";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            SourceContent sourceContent,
            String exportAndModifierPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        var paramSb = new StringBuilder();
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var nodeKind = funcNode.getFieldNameForChild(i);
            var child = funcNode.getChild(i);
            if ("parameters".equals(nodeKind)) {
                paramSb.append(sourceContent.substringFromBytes(child.getStartByte(), child.getEndByte()));
            }
        }
        var allParamsText = paramSb.toString();

        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText;
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        return indent + exportAndModifierPrefix + "def " + functionName + typeParams + allParamsText + ": " + returnType
                + bodyPlaceholder();
    }

    private static final LanguageSyntaxProfile SCALA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DEFINITION, OBJECT_DEFINITION, INTERFACE_DEFINITION, ENUM_DEFINITION),
            Set.of(FUNCTION_DEFINITION),
            Set.of(VAL_DEFINITION, VAR_DEFINITION, SIMPLE_ENUM_CASE),
            Set.of("annotation", "marker_annotation"),
            Set.of(),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "return_type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.OBJECT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.TRAIT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.LAMBDA_DEFINITION, SkeletonType.FUNCTION_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForScala(reference);
    }

    @Override
    protected boolean isConstructor(CodeUnit candidate, @Nullable CodeUnit enclosingClass, String captureName) {
        return false;
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
        String nodeType = fieldNode.getType();
        if (!VAL_DEFINITION.equals(nodeType) && !VAR_DEFINITION.equals(nodeType)) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        String keyword = VAL_DEFINITION.equals(nodeType) ? "val" : "var";

        StringBuilder sb = new StringBuilder();
        sb.append(baseIndent);
        if (!exportPrefix.isEmpty()) {
            sb.append(exportPrefix.stripTrailing()).append(" ");
        }
        sb.append(keyword).append(" ").append(simpleName);

        TSNode typeNode = fieldNode.getChildByFieldName("type");
        if (typeNode != null && !typeNode.isNull()) {
            sb.append(": ").append(sourceContent.substringFromBytes(typeNode.getStartByte(), typeNode.getEndByte()));
        }

        TSNode valueNode = fieldNode.getChildByFieldName("value");
        if (valueNode != null && !valueNode.isNull()) {
            sb.append(" = ").append(sourceContent.substringFromBytes(valueNode.getStartByte(), valueNode.getEndByte()));
        }

        return sb.toString();
    }

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private static final Set<String> TEST_INFIX_KEYWORDS = Set.of("in", "should", "must", "can");

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        try (TSQuery query = createQuery(QueryType.DEFINITIONS)) {
            if (query != null) {
                try (TSQueryCursor cursor = new TSQueryCursor()) {
                    cursor.exec(query, tree.getRootNode());

                    TSQueryMatch match = new TSQueryMatch();
                    while (cursor.nextMatch(match)) {
                        for (TSQueryCapture capture : match.getCaptures()) {
                            TSNode node = capture.getNode();
                            if (node == null || node.isNull()) continue;

                            String captureName = query.getCaptureNameForId(capture.getIndex());
                            switch (captureName) {
                                case "test.import", "test.call" -> {
                                    return true;
                                }
                                case "test.annotation" -> {
                                    String nodeText =
                                            sourceContent.substringFromBytes(node.getStartByte(), node.getEndByte());
                                    if (TEST_ANNOTATIONS.contains(nodeText)) {
                                        return true;
                                    }
                                }
                                case "test.infix" -> {
                                    String nodeText =
                                            sourceContent.substringFromBytes(node.getStartByte(), node.getEndByte());
                                    if (TEST_INFIX_KEYWORDS.contains(nodeText)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
