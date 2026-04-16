package ai.brokk.analyzer;

import static ai.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.java.JavaTypeAnalyzer;
import ai.brokk.project.ICoreProject;
import java.util.*;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import org.treesitter.TreeSitterJava;

public class JavaAnalyzer extends TreeSitterAnalyzer
        implements ImportAnalysisProvider, TypeHierarchyProvider, JvmBasedAnalyzer {

    private static final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");
    private static final Set<String> JAVA_COMMENT_NODE_TYPES = Set.of(LINE_COMMENT, BLOCK_COMMENT);
    private static final Set<String> LOG_RECEIVER_NAMES = Set.of("log", "logger");
    private static final Set<String> CLONE_AST_IDENTIFIER_TYPES =
            Set.of(IDENTIFIER, TYPE_IDENTIFIER, SCOPED_IDENTIFIER, SCOPED_TYPE_IDENTIFIER);
    private static final Set<String> CLONE_AST_STRING_TYPES = Set.of(STRING_LITERAL, CHARACTER_LITERAL);
    private static final Set<String> CLONE_AST_NUMBER_TYPES = Set.of(
            DECIMAL_INTEGER_LITERAL,
            HEX_INTEGER_LITERAL,
            OCTAL_INTEGER_LITERAL,
            BINARY_INTEGER_LITERAL,
            DECIMAL_FLOATING_POINT_LITERAL,
            HEX_FLOATING_POINT_LITERAL);
    private static final Set<String> CLONE_AST_IGNORED_TYPES = Set.of(MODIFIERS, TYPE_PARAMETERS);

    public JavaAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public JavaAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.JAVA, listener);
    }

    private JavaAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.JAVA, state, listener, cache);
    }

    public static JavaAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new JavaAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new JavaAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForJava(reference);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterJava();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/java/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/java/imports.scm");
            case IDENTIFIERS -> Optional.of("treesitter/java/identifiers.scm");
        };
    }

    private static final LanguageSyntaxProfile JAVA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    ENUM_DECLARATION,
                    RECORD_DECLARATION,
                    ANNOTATION_TYPE_DECLARATION),
            Set.of(METHOD_DECLARATION, CONSTRUCTOR_DECLARATION),
            Set.of(FIELD_DECLARATION, ENUM_CONSTANT, CONSTANT_DECLARATION),
            Set.of(CaptureNames.CONSTRUCTOR_DEFINITION),
            Set.of(ANNOTATION, MARKER_ANNOTATION),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.ofEntries( // capture configuration
                    Map.entry(CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE),
                    Map.entry(CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE),
                    Map.entry(CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE),
                    Map.entry(CaptureNames.RECORD_DEFINITION, SkeletonType.CLASS_LIKE),
                    Map.entry(CaptureNames.ANNOTATION_DEFINITION, SkeletonType.CLASS_LIKE), // for @interface
                    Map.entry(CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
                    Map.entry(CaptureNames.CONSTANT_DEFINITION, SkeletonType.FIELD_LIKE),
                    Map.entry(CaptureNames.LAMBDA_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.PACKAGE_DEFINITION, SkeletonType.MODULE_STATEMENT)),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JAVA_SYNTAX_PROFILE;
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
        final String shortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;

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

        // For modules (package declarations), simpleName is the full package name (e.g., "com.example.foo").
        if (type == CodeUnitType.MODULE) {
            int lastDot = simpleName.lastIndexOf('.');
            String parentPkg = (lastDot > 0) ? simpleName.substring(0, lastDot) : "";
            // For a MODULE, the fqName should be the package name.
            // CodeUnit constructor sets fqName = parentPkg.isEmpty() ? leafName : parentPkg + "." + leafName
            String leafName = (lastDot > 0) ? simpleName.substring(lastDot + 1) : simpleName;
            return new CodeUnit(file, type, parentPkg, leafName);
        }

        return new CodeUnit(file, type, packageName, shortName);
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        return determineJvmPackageName(
                rootNode,
                sourceContent,
                PACKAGE_DECLARATION,
                JAVA_SYNTAX_PROFILE.classLikeNodeTypes(),
                (node, sourceContent1) -> sourceContent1.substringFrom(node));
    }

    protected static String determineJvmPackageName(
            TSNode rootNode,
            SourceContent sourceContent,
            String packageDef,
            Set<String> classLikeNodeType,
            BiFunction<TSNode, SourceContent, String> textSlice) {
        // Packages are either present or not, and will be the immediate child of the `program`
        // if they are present at all
        final List<String> namespaceParts = new ArrayList<>();

        // The package may not be the first thing in the file, so we should iterate until either we find it, or we are
        // at a type node.
        TSNode maybeDeclaration = null;
        for (TSNode child : rootNode.getChildren()) {
            String type = child.getType();
            if (packageDef.equals(type)) {
                maybeDeclaration = child;
                break;
            }

            // Skip annotations when searching for the package declaration
            if (ANNOTATION.equals(type) || MARKER_ANNOTATION.equals(type)) {
                continue;
            }

            if (classLikeNodeType.contains(type)) {
                break;
            }
        }

        if (maybeDeclaration != null && packageDef.equals(maybeDeclaration.getType())) {
            // In Java, package_declaration has a single identifier or scoped_identifier child.
            // In Scala, it may have multiple package_identifier children.
            // We iterate through named children and skip annotations (blacklist approach).
            for (TSNode nameNode : maybeDeclaration.getNamedChildren()) {
                String type = nameNode.getType();
                if (ANNOTATION.equals(type) || MARKER_ANNOTATION.equals(type)) {
                    continue;
                }
                String nsPart = textSlice.apply(nameNode, sourceContent);
                // Special handling for nodes that might have child identifiers (like Scala package_identifier)
                if (nsPart.isEmpty() && nameNode.getNamedChildCount() > 0) {
                    List<String> parts = new ArrayList<>();
                    for (TSNode child : nameNode.getNamedChildren()) {
                        String childText = textSlice.apply(child, sourceContent);
                        if (!childText.isEmpty()) {
                            parts.add(childText);
                        }
                    }
                    nsPart = String.join(".", parts);
                }
                if (!nsPart.isEmpty()) {
                    namespaceParts.add(nsPart);
                }
            }
        }
        // Join parts with dots. Java's single scoped_identifier is preserved; Scala's parts are joined.
        return String.join(".", namespaceParts);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String buildCloneAstSignature(String source) {
        return withFreshTree(source, "", tree -> {
            TSNode root = tree.getRootNode();
            if (root == null) {
                return "";
            }
            SourceContent sourceContent = SourceContent.of(source);
            var labels = new ArrayList<String>();
            try (var cursor = new TSTreeCursor(root)) {
                while (true) {
                    TSNode node = cursor.currentNode();
                    if (node == null) {
                        break;
                    }
                    labels.add(normalizeAstLabel(node, sourceContent));
                    if (!gotoNextDepthFirst(cursor, true)) {
                        break;
                    }
                }
            }
            return String.join("|", labels);
        });
    }

    @Override
    protected int refineCloneSimilarityPercent(
            CloneCandidateData left, CloneCandidateData right, int tokenSimilarity, CloneSmellWeights weights) {
        if (left.astSignature().isBlank() || right.astSignature().isBlank()) {
            return tokenSimilarity;
        }
        int astSimilarity = computeAstRefinementSimilarityPercent(left.astSignature(), right.astSignature());
        if (astSimilarity == 0) {
            return tokenSimilarity;
        }
        if (astSimilarity < weights.astSimilarityPercent()) {
            return 0;
        }
        return Math.min(tokenSimilarity, astSimilarity);
    }

    private static String normalizeAstLabel(TSNode node, SourceContent sourceContent) {
        String type = Objects.toString(node.getType(), "");
        String text = sourceContent.substringFrom(node).strip();
        if (CLONE_AST_IDENTIFIER_TYPES.contains(type)) {
            return "ID";
        }
        if (CLONE_AST_STRING_TYPES.contains(type)) {
            return "STR";
        }
        if (CLONE_AST_NUMBER_TYPES.contains(type)) {
            return "NUM";
        }
        if (BOOLEAN_LITERAL.equals(type) || TRUE.equals(text) || FALSE.equals(text)) {
            return "BOOL";
        }
        if (CLONE_AST_IGNORED_TYPES.contains(type)) {
            return "IGN";
        }
        return "N:" + type;
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
        // Hide anonymous/lambda "functions" from Java skeletons while still creating CodeUnits for discovery.
        if (LAMBDA_REGEX.matcher(functionName).find()) {
            return "";
        }

        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        var signature = indent + exportAndModifierPrefix + typeParams + returnType + functionName + paramsText;

        var throwsNode = funcNode.getChildByFieldName("throws");
        if (throwsNode != null) {
            signature += " " + sourceContent.substringFrom(throwsNode);
        }

        return signature;
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
        if (ENUM_CONSTANT.equals(nodeType)) {
            return formatEnumConstant(fieldNode, signatureText, baseIndent);
        }

        if (FIELD_DECLARATION.equals(nodeType) || CONSTANT_DECLARATION.equals(nodeType)) {
            TSNode typeNode = fieldNode.getChildByFieldName("type");
            if (typeNode != null) {
                String modifiers = getPrefixText(fieldNode, typeNode, sourceContent, Set.of("modifiers"));
                String typeStr = sourceContent.substringFrom(typeNode).strip();

                Optional<TSNode> declaratorOpt =
                        findDeclarator(fieldNode, simpleName, sourceContent, VARIABLE_DECLARATOR, "name");
                if (declaratorOpt.isPresent()) {
                    TSNode declarator = declaratorOpt.get();
                    TSNode nameNode = declarator.getChildByFieldName("name");
                    String nameStr = sourceContent.substringFrom(nameNode).strip();

                    String suffix = ";";
                    TSNode valueNode = declarator.getChildByFieldName("value");
                    if (valueNode != null) {
                        String valueType = valueNode.getType();
                        if (valueType != null
                                && (valueType.endsWith("_literal")
                                        || TRUE.equals(valueType)
                                        || FALSE.equals(valueType)
                                        || NULL.equals(valueType))) {
                            suffix = " = "
                                    + sourceContent.substringFrom(valueNode).strip() + ";";
                        }
                    }

                    String full = (modifiers + typeStr + " " + nameStr + suffix).strip();
                    return baseIndent + full;
                }
            }
        }

        return super.formatFieldSignature(
                fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
    }

    private String formatEnumConstant(TSNode fieldNode, String signatureText, String baseIndent) {
        TSNode parent = fieldNode.getParent();
        if (parent != null) {
            List<TSNode> namedChildren = parent.getNamedChildren();
            boolean hasFollowingConstant = false;

            // Compare by byte range to reliably identify the same node
            int targetStart = fieldNode.getStartByte();
            int targetEnd = fieldNode.getEndByte();

            for (int i = 0; i < namedChildren.size(); i++) {
                TSNode child = namedChildren.get(i);
                if (child.getStartByte() == targetStart && child.getEndByte() == targetEnd) {
                    // Check if any subsequent named child is also an enum_constant
                    for (int j = i + 1; j < namedChildren.size(); j++) {
                        TSNode next = namedChildren.get(j);
                        if (ENUM_CONSTANT.equals(next.getType())) {
                            hasFollowingConstant = true;
                            break;
                        }
                    }
                    break;
                }
            }
            return baseIndent + signatureText + (hasFollowingConstant ? "," : "");
        }
        // Fallback: if structure not as expected, do not add terminating punctuation
        return baseIndent + signatureText;
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}";
    }

    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestCase", "Rule", "Ignore");

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        var rootNode = tree.getRootNode();
                        if (rootNode == null) return false;
                        cursor.exec(query, rootNode, sourceContent.text());

                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (TEST_MARKER.equals(captureName)) {
                                    TSNode node = capture.getNode();
                                    String simpleName =
                                            sourceContent.substringFrom(node).strip();
                                    int lastDot = simpleName.lastIndexOf('.');
                                    if (lastDot >= 0) {
                                        simpleName = simpleName.substring(lastDot + 1);
                                    }

                                    if (TEST_ANNOTATIONS.contains(simpleName)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        Set<ProjectFile> result = new HashSet<>(performReferencingFilesOf(file));

        // Java-specific: add same-package files that actually use the target file.
        // Files in the same package have implicit visibility, but we only consider them
        // "referencing" if they contain identifiers matching the target's declarations.
        List<CodeUnit> targetDecls = getTopLevelDeclarations(file);
        if (targetDecls.isEmpty()) {
            return result.isEmpty() ? Set.of() : Collections.unmodifiableSet(result);
        }

        String targetPackage = targetDecls.stream()
                .filter(cu -> cu.isClass() || cu.isModule())
                .map(cu -> cu.isModule() ? cu.fqName() : cu.packageName())
                .findFirst()
                .orElse("");

        if (!targetPackage.isEmpty()) {
            Set<String> targetIdentifiers =
                    targetDecls.stream().map(CodeUnit::identifier).collect(Collectors.toSet());

            withFileProperties(fileState -> {
                for (ProjectFile candidate : fileState.keySet()) {
                    if (candidate.equals(file) || result.contains(candidate)) continue;

                    String candidatePackage = getTopLevelDeclarations(candidate).stream()
                            .filter(cu -> cu.isClass() || cu.isModule())
                            .map(cu -> cu.isModule() ? cu.fqName() : cu.packageName())
                            .findFirst()
                            .orElse("");

                    if (targetPackage.equals(candidatePackage)) {
                        // Check if the candidate actually uses any of target's identifiers
                        Set<String> candidateSymbols = typeIdentifiersOf(candidate);
                        if (candidateSymbols.stream().anyMatch(targetIdentifiers::contains)) {
                            result.add(candidate);
                        }
                    }
                }
                return null;
            });
        }

        return result.isEmpty() ? Set.of() : Collections.unmodifiableSet(result);
    }

    @Override
    public List<CodeUnit> getDirectAncestors(CodeUnit cu) {
        return performGetDirectAncestors(cu);
    }

    @Override
    public Set<CodeUnit> getDirectDescendants(CodeUnit cu) {
        return performGetDirectDescendants(cu);
    }

    @Override
    public SequencedSet<CodeUnit> getDefinitions(String fqName) {
        // Normalize generics/anon/location suffixes for both class and method lookups
        var normalized = normalizeFullName(fqName);
        return super.getDefinitions(normalized);
    }

    /**
     * Strips Java generic type arguments (e.g., "<K, V extends X>") from any segments of the provided name. Handles
     * nested generics by tracking angle bracket depth.
     */
    public static String stripGenericTypeArguments(String name) {
        if (name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name.length());
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                if (depth > 0) depth--;
                continue;
            }
            if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    protected String normalizeFullName(String fqName) {
        // Normalize generics and method/lambda/location suffixes while preserving "$anon$" verbatim.
        String s = stripGenericTypeArguments(fqName);

        if (s.contains("$anon$")) {
            // Replace subclass delimiters with '.' except within the literal "$anon$" segments.
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); ) {
                if (s.startsWith("$anon$", i)) {
                    out.append("$anon$");
                    i += 6; // length of "$anon$"
                } else {
                    char c = s.charAt(i++);
                    out.append(c == '$' ? '.' : c);
                }
            }
            return out.toString();
        }

        // No lambda marker; perform standard normalization:
        // 1) Strip trailing numeric anonymous suffixes like $1 or $2 (optionally followed by :line(:col))
        s = s.replaceFirst("\\$\\d+(?::\\d+(?::\\d+)?)?$", "");
        // 2) Strip trailing location suffix like :line or :line:col (e.g., ":16" or ":328:16")
        s = s.replaceFirst(":[0-9]+(?::[0-9]+)?$", "");
        // 3) Replace subclass delimiters with dots
        s = s.replace('$', '.');
        return s;
    }

    @Override
    protected Optional<String> extractSimpleName(TSNode decl, SourceContent sourceContent) {
        // Special handling for Java lambdas: synthesize a bytecode-style anonymous name
        if (LAMBDA_EXPRESSION.equals(decl.getType())) {
            var enclosingMethod =
                    findEnclosingJavaMethodOrClassName(decl, sourceContent).orElse("lambda");
            int line = decl.getStartPoint().getRow();
            int col = decl.getStartPoint().getColumn();
            String synthesized = enclosingMethod + "$anon$" + line + ":" + col;
            return Optional.of(synthesized);
        }
        return super.extractSimpleName(decl, sourceContent);
    }

    private Optional<String> findEnclosingJavaMethodOrClassName(TSNode node, SourceContent sourceContent) {
        // Walk up to nearest method or constructor
        TSNode current = node.getParent();
        while (current != null) {
            String type = current.getType();
            if (METHOD_DECLARATION.equals(type) || CONSTRUCTOR_DECLARATION.equals(type)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null) {
                    String name = sourceContent.substringFrom(nameNode).strip();
                    if (!name.isEmpty()) {
                        return Optional.of(name);
                    }
                }
                break;
            }
            current = current.getParent();
        }

        // Fallback: if inside an initializer, try nearest class-like to use its name
        current = node.getParent();
        while (current != null) {
            if (isClassLike(current)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null) {
                    String cls = sourceContent.substringFrom(nameNode).strip();
                    if (!cls.isEmpty()) {
                        return Optional.of(cls);
                    }
                }
                break;
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    @Override
    protected boolean isAnonymousStructure(String fqName) {
        var matcher = LAMBDA_REGEX.matcher(fqName);
        return matcher.find();
    }

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode importNode = capturedNodesForMatch.get(getLanguageSyntaxProfile().importNodeType());
        if (importNode != null) {
            String importText = sourceContent.substringFrom(importNode).strip();
            if (!importText.isEmpty()) {
                localImportInfos.add(parseJavaImport(importText));
            }
        }
    }

    /**
     * Parses a Java import statement into structured ImportInfo.
     * Detects wildcard imports and extracts simple identifiers for non-wildcards.
     */
    private ImportInfo parseJavaImport(String rawSnippet) {
        // Determine if it's a wildcard import
        boolean isWildcard = rawSnippet.contains(".*");

        // Extract identifier for non-wildcard imports
        String identifier = null;
        if (!isWildcard) {
            // Remove "import " prefix and ";" suffix, handle static imports
            String normalized = rawSnippet.strip();
            if (normalized.startsWith("import ")) {
                normalized = normalized.substring("import ".length());
            }
            boolean isStatic = normalized.startsWith("static ");
            if (isStatic) {
                normalized = normalized.substring("static ".length());
            }
            if (normalized.endsWith(";")) {
                normalized = normalized.substring(0, normalized.length() - 1).strip();
            }

            // Extract the simple name (last segment)
            // For 'import com.foo.Outer.Inner;', identifier is 'Inner'.
            // For 'import static com.foo.Bar.METHOD;', identifier is 'METHOD'.
            int lastDot = normalized.lastIndexOf('.');
            identifier = (lastDot >= 0 && lastDot < normalized.length() - 1)
                    ? normalized.substring(lastDot + 1)
                    : normalized;
        }

        // Java doesn't have import aliases
        return new ImportInfo(rawSnippet, isWildcard, identifier, null);
    }

    /**
     * Resolves import statements into a set of {@link CodeUnit}s, respecting Java's import precedence rules.
     * Explicit imports (e.g., {@code import com.example.MyClass;}) take priority over wildcard imports
     * (e.g., {@code import com.example.*;}). If multiple wildcard imports could provide a class with the
     * same simple name, the first one encountered in the source file wins. This provides deterministic
     * behavior for ambiguous wildcard imports.
     * <p>
     * Static imports are ignored.
     */
    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        Set<CodeUnit> explicitImports = new LinkedHashSet<>();
        List<String> wildcardImportPackages = new ArrayList<>();

        // 1. First pass: use structured ImportInfo to separate explicit from wildcard.
        // Static imports are excluded by checking the raw snippet or lack of identifier/wildcard status.
        for (ImportInfo info : importInfoOf(file)) {
            if (info.rawSnippet().startsWith("import static ")) {
                continue;
            }

            if (info.isWildcard()) {
                String snippet = info.rawSnippet().strip();
                if (snippet.endsWith(";")) {
                    snippet = snippet.substring(0, snippet.length() - 1).strip();
                }
                if (snippet.startsWith("import ")) {
                    snippet = snippet.substring("import ".length()).strip();
                }
                String packageName = snippet.endsWith(".*") ? snippet.substring(0, snippet.length() - 2) : snippet;
                if (!packageName.isEmpty()) {
                    wildcardImportPackages.add(packageName);
                }
            } else {
                String identifier = info.identifier();
                if (identifier != null) {
                    // Extract the FQN from the raw snippet for definition lookup
                    String snippet = info.rawSnippet().strip();
                    if (snippet.endsWith(";")) {
                        snippet = snippet.substring(0, snippet.length() - 1).strip();
                    }
                    if (snippet.startsWith("import ")) {
                        snippet = snippet.substring("import ".length()).strip();
                    }
                    // Explicit import: find the exact class and add it.
                    getDefinitions(snippet).stream()
                            .filter(CodeUnit::isClass)
                            .findFirst()
                            .ifPresent(explicitImports::add);
                }
            }
        }

        // 2. Build the final set of resolved imports, prioritizing explicit imports.
        Set<CodeUnit> resolved = new LinkedHashSet<>(explicitImports);
        Set<String> resolvedSimpleNames =
                explicitImports.stream().map(CodeUnit::identifier).collect(Collectors.toSet());

        // 3. Process wildcard imports. A class from a wildcard is only added if a class
        //    with the same simple name has not already been added from an explicit import
        //    or a preceding wildcard import. This ensures deterministic resolution.
        for (String packageName : wildcardImportPackages) {
            getDefinitions(packageName).stream()
                    .filter(CodeUnit::isModule)
                    .flatMap(module -> getDirectChildren(module).stream())
                    .filter(CodeUnit::isClass)
                    .filter(child -> packageName.equals(child.packageName()))
                    .filter(child -> resolvedSimpleNames.add(child.identifier()))
                    .forEach(resolved::add);
        }

        return Collections.unmodifiableSet(resolved);
    }

    @Override
    public List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Pull raw supertypes lazily. This extracts the names from the AST on-demand.
        var rawNames = getRawSupertypesLazily(cu);

        if (rawNames.isEmpty()) {
            return List.of();
        }

        // Get resolved imports for this file from the analyzer pipeline
        Set<CodeUnit> resolvedImports = importedCodeUnitsOf(cu.source());

        // Resolve raw names using imports, package and global search, preserving order
        return JavaTypeAnalyzer.compute(
                rawNames, cu.packageName(), resolvedImports, this::getDefinitions, (s) -> searchDefinitions(s, false));
    }

    @Override
    public List<CodeUnit> getAncestors(CodeUnit cu) {
        // Breadth-first traversal of ancestors while preventing cycles and excluding the root.
        if (!cu.isClass()) return List.of();

        var result = new ArrayList<CodeUnit>();
        var seen = new LinkedHashSet<String>();
        // Mark root as seen to avoid re-adding it via cycles (e.g., interfaces A <-> B).
        seen.add(cu.fqName());

        var queue = new ArrayDeque<CodeUnit>();
        // Seed with direct ancestors preserving declaration order.
        for (var parent : getDirectAncestors(cu)) {
            if (cu.fqName().equals(parent.fqName())) {
                continue; // Defensive: avoid self-loop
            }
            if (seen.add(parent.fqName())) {
                result.add(parent);
                queue.add(parent);
            }
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            for (var parent : getDirectAncestors(current)) {
                // Never add the original root as an ancestor.
                if (cu.fqName().equals(parent.fqName())) {
                    continue;
                }
                if (seen.add(parent.fqName())) {
                    result.add(parent);
                    queue.addLast(parent);
                }
            }
        }

        return List.copyOf(result);
    }

    @Override
    public boolean isAccessExpression(ProjectFile file, int startByte, int endByte) {
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) return false;
                    TSNode node = root.getDescendantForByteRange(startByte, endByte);
                    if (node == null) return true;

                    // 1. Check if the node itself or any parent is a comment
                    TSNode walk = node;
                    while (walk != null) {
                        if (isCommentNode(walk)) return false;
                        walk = walk.getParent();
                    }

                    // 2. Check if we are in a declaration context (name of a method, field, param, etc.)
                    TSNode current = node;
                    while (current != null) {
                        String type = current.getType();

                        // If we hit a known reference/usage node type, it's likely a reference
                        if (ACCESS_TYPES.contains(type)) {
                            break; // Continue to nearest declaration check
                        }

                        // If we are the 'name' child of a declaration, it's not a reference
                        TSNode parent = current.getParent();
                        if (parent != null) {
                            String pType = parent.getType();
                            if (DECLARATION_TYPES.contains(pType)) {
                                TSNode nameNode = parent.getChildByFieldName("name");
                                if (nameNode != null && nameNode.getStartByte() == startByte) {
                                    return false;
                                }
                            }
                        }
                        current = current.getParent();
                    }

                    // 3. Perform lexical scope analysis to filter out local variables and parameters
                    // Skip this if the current node is explicitly part of a member access (e.g., this.field or
                    // obj.field)
                    TSNode parent = node.getParent();
                    if (parent != null) {
                        String pType = parent.getType();
                        if (FIELD_ACCESS.equals(pType)) {
                            // In tree-sitter-java, field_access has a "field" child for the member name
                            TSNode fieldNode = parent.getChildByFieldName("field");
                            if (fieldNode != null && fieldNode.getStartByte() == node.getStartByte()) {
                                return true;
                            }
                        }
                        if (METHOD_INVOCATION.equals(pType)) {
                            // In tree-sitter-java, method_invocation has a "name" child for the method name
                            TSNode nameNode = parent.getChildByFieldName("name");
                            if (nameNode != null && nameNode.getStartByte() == node.getStartByte()) {
                                return true;
                            }
                        }
                    }

                    return withSource(
                            file,
                            sourceContent -> {
                                String identifierName =
                                        sourceContent.substringFrom(node).strip();
                                if (!identifierName.isEmpty()) {
                                    var declOpt = findNearestDeclaration(node, identifierName, sourceContent);
                                    if (declOpt.isPresent()) {
                                        var kind = declOpt.get().kind();
                                        return switch (kind) {
                                            case PARAMETER,
                                                    LOCAL_VARIABLE,
                                                    CATCH_PARAMETER,
                                                    FOR_LOOP_VARIABLE,
                                                    RESOURCE_VARIABLE,
                                                    PATTERN_VARIABLE -> false;
                                            default -> true;
                                        };
                                    }
                                }
                                return true;
                            },
                            true);
                },
                true);
    }

    @Override
    protected FileAnalysisAccumulator createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            FileAnalysisAccumulator acc) {
        if (modulePackageName.isBlank()) {
            return acc;
        }

        // Look up the module in cuByFqName (created via captures).
        // Only use modules that are already present; do not create new ones.
        CodeUnit moduleCu = acc.getByFqName(modulePackageName);
        if (moduleCu == null || !moduleCu.isModule()) {
            return acc;
        }

        // Find top-level classes in this package.
        List<CodeUnit> classesInPackage = acc.topLevelCUs().stream()
                .filter(cu -> cu.isClass() && modulePackageName.equals(cu.packageName()))
                .toList();

        for (CodeUnit child : classesInPackage) {
            acc.addChild(moduleCu, child);
        }
        return acc;
    }

    /**
     * Extracts type identifiers using Tree-Sitter.
     * <p>
     * Trade-off: High Precision. By targeting only {@code type_identifier} nodes, we minimize false positives
     * from local variables or method names, ensuring that only relevant type-related imports are pulled in.
     */
    @Override
    public Set<String> extractTypeIdentifiers(String source) {
        try (TSTree tree = getTSParser().parseStringOrThrow(null, source)) {
            TSNode root = tree.getRootNode();
            return performIdentifierExtraction(root, source);
        } catch (Exception e) {
            log.warn("Failed to extract type identifiers using Tree-Sitter query", e);
            return Set.of();
        }
    }

    @Override
    public Set<String> performIdentifierExtraction(@Nullable TSNode root, String source) {
        if (root == null) {
            return Set.of();
        }

        Set<String> identifiers = new HashSet<>();
        withCachedQuery(
                QueryType.IDENTIFIERS,
                query -> {
                    SourceContent sourceContent = SourceContent.of(source);
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, root, sourceContent.text());

                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                TSNode node = capture.getNode();
                                if (node != null) {
                                    String text = sourceContent.substringFrom(node);
                                    if (!text.isEmpty()) {
                                        identifiers.add(text);
                                    }
                                }
                            }
                        }
                    }
                    return true;
                },
                false);
        return identifiers;
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        // Get the source text for this CodeUnit
        var sourceOpt = getSource(cu, false);
        if (sourceOpt.isEmpty()) {
            return Set.of();
        }
        String source = sourceOpt.get();

        // Get all imports for the file
        List<ImportInfo> allImports = importInfoOf(cu.source());
        if (allImports.isEmpty()) {
            return Set.of();
        }

        // Extract type identifiers from source
        Set<String> typeIdentifiers = extractTypeIdentifiers(source);
        if (typeIdentifiers.isEmpty()) {
            return Set.of();
        }

        // Separate explicit imports from wildcard imports
        List<ImportInfo> explicitImports = allImports.stream()
                .filter(imp -> !imp.isWildcard() && imp.identifier() != null)
                .toList();
        List<ImportInfo> wildcardImports =
                allImports.stream().filter(ImportInfo::isWildcard).toList();

        // Match type identifiers against explicit imports
        Set<String> matchedImports = new HashSet<>();
        Set<String> resolvedIdentifiers = new HashSet<>();

        for (ImportInfo imp : explicitImports) {
            String identifier = imp.identifier();
            if (identifier != null && typeIdentifiers.contains(identifier)) {
                matchedImports.add(imp.rawSnippet());
                resolvedIdentifiers.add(identifier);
            }
        }

        // Collect identifiers still unresolved after explicit import matching
        Set<String> unresolvedIdentifiers = typeIdentifiers.stream()
                .filter(id -> !resolvedIdentifiers.contains(id))
                .collect(Collectors.toCollection(HashSet::new));

        if (unresolvedIdentifiers.isEmpty()) {
            return Collections.unmodifiableSet(matchedImports);
        }

        // Handle qualified types (e.g. java.util.List). If they don't match any import's package,
        // assume they are fully qualified and already resolved.
        Set<String> qualifiedNames =
                unresolvedIdentifiers.stream().filter(id -> id.contains(".")).collect(Collectors.toSet());

        Set<String> importPackages = allImports.stream()
                .map(i -> extractPackageFromWildcard(i.rawSnippet()))
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toSet());

        for (String qn : qualifiedNames) {
            boolean matchesImport = false;
            for (String pkg : importPackages) {
                if (qn.startsWith(pkg + ".")) {
                    matchesImport = true;
                    break;
                }
            }
            // If it doesn't match any import prefix, treat as already resolved
            if (!matchesImport) {
                unresolvedIdentifiers.remove(qn);
            }
        }

        if (unresolvedIdentifiers.isEmpty()) {
            return Collections.unmodifiableSet(matchedImports);
        }

        Set<String> resolvedViaWildcard = new HashSet<>();

        // Match unresolved identifiers against wildcard imports using known project symbols
        for (String id : unresolvedIdentifiers) {
            for (ImportInfo wildcardImp : wildcardImports) {
                String pkg = extractPackageFromWildcard(wildcardImp.rawSnippet());

                if (!pkg.isEmpty()) {
                    String lookupName = pkg + "." + id;
                    if (!getDefinitions(lookupName).isEmpty()) {
                        matchedImports.add(wildcardImp.rawSnippet());
                        resolvedViaWildcard.add(id);
                    }
                }
            }
        }

        // After checking all wildcards, if any identifiers are still unresolved
        // (not in explicit imports AND not resolved via wildcards to known types),
        // include ALL remaining wildcards as a conservative fallback for external dependencies.
        boolean stillUnresolved = unresolvedIdentifiers.stream()
                .filter(id -> !resolvedViaWildcard.contains(id))
                // Only simple names (no dots) trigger the conservative wildcard fallback
                .anyMatch(id -> !id.contains("."));

        if (stillUnresolved) {
            for (ImportInfo wildcardImp : wildcardImports) {
                matchedImports.add(wildcardImp.rawSnippet());
            }
        }

        return Collections.unmodifiableSet(matchedImports);
    }

    @Override
    public boolean couldImportFile(List<ImportInfo> imports, ProjectFile target) {
        // Determine target package from its top-level declarations
        String targetPackage = getTopLevelDeclarations(target).stream()
                .filter(cu -> cu.isClass() || cu.isModule())
                .map(cu -> cu.isModule() ? cu.fqName() : cu.packageName())
                .findFirst()
                .orElse("");

        // Check for explicit or wildcard imports
        String targetName = target.getFileName();
        if (targetName.endsWith(".java")) {
            targetName = targetName.substring(0, targetName.length() - 5);
        }
        final String targetClassName = targetName;

        for (ImportInfo imp : imports) {
            // Case 1: Explicit import (e.g. import com.example.Foo; or import static com.example.Foo.METHOD;)
            if (!imp.isWildcard() && imp.identifier() != null) {
                // Direct match on simple name (e.g. Foo or METHOD)
                if (targetClassName.equals(imp.identifier())) return true;

                // For static imports or nested classes, the target class might be the parent segment
                // e.g. import static com.example.Foo.METHOD; should match Foo.java
                // e.g. import com.example.Foo.Inner; should match Foo.java
                if (imp.rawSnippet().contains("." + targetClassName + ".")) return true;
            }

            // Case 2: Wildcard import (e.g. import com.example.*;)
            // Matches if the wildcard package is exactly the target's package,
            // or if it's a static wildcard import of the target class.
            if (imp.isWildcard()) {
                String importPkg = extractPackageFromWildcard(imp.rawSnippet());
                if (importPkg.equals(targetPackage) || importPkg.equals(targetPackage + "." + targetClassName)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Overloaded version that takes the source file to check for same-package visibility.
     */
    @Override
    public boolean couldImportFile(ProjectFile sourceFile, List<ImportInfo> imports, ProjectFile target) {
        if (sourceFile.equals(target)) {
            return false;
        }

        String sourcePackage = getTopLevelDeclarations(sourceFile).stream()
                .filter(cu -> cu.isClass() || cu.isModule())
                .map(cu -> cu.isModule() ? cu.fqName() : cu.packageName())
                .findFirst()
                .orElse("");

        String targetPackage = getTopLevelDeclarations(target).stream()
                .filter(cu -> cu.isClass() || cu.isModule())
                .map(cu -> cu.isModule() ? cu.fqName() : cu.packageName())
                .findFirst()
                .orElse("");

        // In Java, files in the same package (including the default package) see each other.
        if (sourcePackage.equals(targetPackage)) {
            return true;
        }

        return couldImportFile(imports, target);
    }

    @Override
    public Optional<DeclarationInfo> findNearestDeclaration(
            ProjectFile file, int startByte, int endByte, String identifierName) {
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) return Optional.empty();
                    TSNode node = root.getDescendantForByteRange(startByte, endByte);
                    if (node == null) return Optional.empty();

                    return withSource(
                            file,
                            sourceContent -> findNearestDeclaration(node, identifierName, sourceContent),
                            Optional.empty());
                },
                Optional.empty());
    }

    private Optional<DeclarationInfo> findNearestDeclaration(
            TSNode node, String identifierName, SourceContent sourceContent) {
        return findNearestDeclarationFromNode(node, identifierName, sourceContent);
    }

    /**
     * Walks upward from startNode through enclosing scopes, checking for declarations
     * with a matching identifier name. Returns the first match found.
     */
    private Optional<DeclarationInfo> findNearestDeclarationFromNode(
            TSNode startNode, String identifierName, SourceContent sourceContent) {
        TSNode current = startNode;

        while (current != null) {
            String nodeType = current.getType();

            // Check method/constructor parameters
            if (METHOD_DECLARATION.equals(nodeType) || CONSTRUCTOR_DECLARATION.equals(nodeType)) {
                var paramResult = checkFormalParameters(current, identifierName, sourceContent);
                if (paramResult.isPresent()) return paramResult;
            }

            // Check local variable declarations among preceding siblings
            var localResult = checkPrecedingLocalVariables(current, identifierName, sourceContent);
            if (localResult.isPresent()) return localResult;

            // Check enhanced for loop variable
            if (ENHANCED_FOR_STATEMENT.equals(nodeType)) {
                var forResult = checkEnhancedForStatement(current, identifierName, sourceContent);
                if (forResult.isPresent()) return forResult;
            }

            // Check catch formal parameter
            if ("catch_clause".equals(nodeType)) {
                // catch_formal_parameter is a direct named child, not accessed via field name
                for (TSNode child : current.getNamedChildren()) {
                    if (CATCH_FORMAL_PARAMETER.equals(child.getType())) {
                        var catchResult = checkCatchFormalParameter(child, identifierName, sourceContent);
                        if (catchResult.isPresent()) return catchResult;
                        break;
                    }
                }
            }

            // Check try-with-resources
            if ("try_with_resources_statement".equals(nodeType)) {
                TSNode resourceSpec = current.getChildByFieldName("resources");
                if (resourceSpec != null) {
                    var resourceResult = checkResourceSpecification(resourceSpec, identifierName, sourceContent);
                    if (resourceResult.isPresent()) return resourceResult;
                }
            }

            // Check lambda parameters
            if (LAMBDA_EXPRESSION.equals(nodeType)) {
                var lambdaResult = checkLambdaParameters(current, identifierName, sourceContent);
                if (lambdaResult.isPresent()) return lambdaResult;
            }

            // Check instanceof pattern variable (Java 16+)
            if (INSTANCEOF_EXPRESSION.equals(nodeType)) {
                var patternResult = checkInstanceofPattern(current, identifierName, sourceContent);
                if (patternResult.isPresent()) return patternResult;
            }

            current = current.getParent();
        }

        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkFormalParameters(
            TSNode methodOrConstructor, String identifierName, SourceContent sourceContent) {
        TSNode params = methodOrConstructor.getChildByFieldName("parameters");
        if (params == null) return Optional.empty();

        for (TSNode param : params.getNamedChildren()) {
            if (FORMAL_PARAMETER.equals(param.getType())) {
                TSNode nameNode = param.getChildByFieldName("name");
                if (nameNode != null) {
                    String name = sourceContent.substringFrom(nameNode).strip();
                    if (identifierName.equals(name)) {
                        return Optional.of(new DeclarationInfo(DeclarationKind.PARAMETER, name, null));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkPrecedingLocalVariables(
            TSNode current, String identifierName, SourceContent sourceContent) {
        TSNode parent = current.getParent();
        if (parent == null) return Optional.empty();

        // Local variables are declared in local_variable_declaration nodes that are siblings
        // to the current node's path.
        for (TSNode sibling : parent.getNamedChildren()) {
            if (sibling.getEndByte() > current.getStartByte()) break;

            if (LOCAL_VARIABLE_DECLARATION.equals(sibling.getType())) {
                for (TSNode child : sibling.getNamedChildren()) {
                    if (VARIABLE_DECLARATOR.equals(child.getType())) {
                        TSNode nameNode = child.getChildByFieldName("name");
                        if (nameNode != null) {
                            String name = sourceContent.substringFrom(nameNode).strip();
                            if (identifierName.equals(name)) {
                                return Optional.of(new DeclarationInfo(DeclarationKind.LOCAL_VARIABLE, name, null));
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkEnhancedForStatement(
            TSNode enhancedFor, String identifierName, SourceContent sourceContent) {
        // enhanced_for_statement has a "name" field for the loop variable
        TSNode nameNode = enhancedFor.getChildByFieldName("name");
        if (nameNode != null) {
            String name = sourceContent.substringFrom(nameNode).strip();
            if (identifierName.equals(name)) {
                return Optional.of(new DeclarationInfo(DeclarationKind.FOR_LOOP_VARIABLE, name, null));
            }
        }
        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkCatchFormalParameter(
            TSNode catchParam, String identifierName, SourceContent sourceContent) {
        TSNode nameNode = catchParam.getChildByFieldName("name");
        if (nameNode != null) {
            String name = sourceContent.substringFrom(nameNode).strip();
            if (identifierName.equals(name)) {
                return Optional.of(new DeclarationInfo(DeclarationKind.CATCH_PARAMETER, name, null));
            }
        }
        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkResourceSpecification(
            TSNode resourceSpec, String identifierName, SourceContent sourceContent) {
        // resource_specification contains resource children
        for (TSNode resource : resourceSpec.getNamedChildren()) {
            if (RESOURCE.equals(resource.getType())) {
                TSNode nameNode = resource.getChildByFieldName("name");
                if (nameNode != null) {
                    String name = sourceContent.substringFrom(nameNode).strip();
                    if (identifierName.equals(name)) {
                        return Optional.of(new DeclarationInfo(DeclarationKind.RESOURCE_VARIABLE, name, null));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkLambdaParameters(
            TSNode lambda, String identifierName, SourceContent sourceContent) {
        TSNode params = lambda.getChildByFieldName("parameters");
        if (params == null) return Optional.empty();

        String paramsType = params.getType();

        // formal_parameters case (typed lambda: (String x) -> ...)
        if (FORMAL_PARAMETERS.equals(paramsType)) {
            for (TSNode param : params.getNamedChildren()) {
                if (FORMAL_PARAMETER.equals(param.getType())) {
                    TSNode nameNode = param.getChildByFieldName("name");
                    if (nameNode != null) {
                        String name = sourceContent.substringFrom(nameNode).strip();
                        if (identifierName.equals(name)) {
                            return Optional.of(new DeclarationInfo(DeclarationKind.PARAMETER, name, null));
                        }
                    }
                }
            }
        }

        // inferred_parameters case (untyped lambda: (x, y) -> ...)
        if (INFERRED_PARAMETERS.equals(paramsType)) {
            for (TSNode param : params.getNamedChildren()) {
                String name = sourceContent.substringFrom(param).strip();
                if (identifierName.equals(name)) {
                    return Optional.of(new DeclarationInfo(DeclarationKind.PARAMETER, name, null));
                }
            }
        }

        // Single identifier parameter case (lambda: x -> ...)
        if ("identifier".equals(paramsType)) {
            String name = sourceContent.substringFrom(params).strip();
            if (identifierName.equals(name)) {
                return Optional.of(new DeclarationInfo(DeclarationKind.PARAMETER, name, null));
            }
        }

        return Optional.empty();
    }

    private Optional<DeclarationInfo> checkInstanceofPattern(
            TSNode instanceofExpr, String identifierName, SourceContent sourceContent) {
        // instanceof_expression may have a pattern child with a name field (Java 16+)
        TSNode pattern = instanceofExpr.getChildByFieldName("pattern");
        if (pattern != null) {
            TSNode nameNode = pattern.getChildByFieldName("name");
            if (nameNode != null) {
                String name = sourceContent.substringFrom(nameNode).strip();
                if (identifierName.equals(name)) {
                    return Optional.of(new DeclarationInfo(DeclarationKind.PATTERN_VARIABLE, name, null));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    protected String extractPackageFromWildcard(String rawSnippet) {
        // e.g., "import internal.*;" -> "internal"
        // e.g., "import static org.junit.Assert.*;" -> "org.junit.Assert"
        return rawSnippet
                .replaceFirst("^import\\s+", "")
                .replaceFirst("^static\\s+", "")
                .replaceFirst("\\.\\*;$", "")
                .replaceFirst(";$", "")
                .trim();
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName);
    }

    /** Extracts parameter type signature for method/constructor overload distinction. */
    @Override
    protected @Nullable String extractSignature(
            String captureName, TSNode definitionNode, SourceContent sourceContent) {
        if (!CaptureNames.METHOD_DEFINITION.equals(captureName)
                && !CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName)) {
            return null;
        }

        TSNode parametersNode =
                definitionNode.getChildByFieldName(getLanguageSyntaxProfile().parametersFieldName());
        if (parametersNode == null) {
            return "()";
        }

        List<String> params = new ArrayList<>();
        for (TSNode param : parametersNode.getNamedChildren()) {
            String paramType = param.getType();
            TSNode typeNode = null;
            if (FORMAL_PARAMETER.equals(paramType)) {
                typeNode = param.getChildByFieldName("type");
            } else if (SPREAD_PARAMETER.equals(paramType)) {
                // In tree-sitter-java, spread_parameter doesn't have a 'type' field.
                // The type node is the first named child that isn't 'modifiers'.
                for (TSNode child : param.getNamedChildren()) {
                    if (!"modifiers".equals(child.getType())) {
                        typeNode = child;
                        break;
                    }
                }
            }

            if (typeNode != null) {
                String typeText = sourceContent.substringFrom(typeNode).strip();

                // Varargs are arrays at bytecode level; normalize to array notation for signature distinction.
                boolean isVarargsParam = SPREAD_PARAMETER.equals(paramType)
                        || sourceContent.substringFrom(param).contains("...")
                        || typeText.endsWith("...");

                if (isVarargsParam) {
                    if (typeText.endsWith("...")) {
                        typeText = typeText.substring(0, typeText.length() - 3).strip();
                    }
                    typeText += "[]";
                }
                params.add(stripGenericTypeArguments(typeText));
            }
        }

        return params.stream().collect(Collectors.joining(", ", "(", ")"));
    }

    @Override
    protected boolean shouldAttachToParent(
            CodeUnit cu, TSNode node, String captureName, String classChain, List<ScopeSegment> scopeChain) {
        return super.shouldAttachToParent(cu, node, captureName, classChain, scopeChain)
                || CaptureNames.LAMBDA_DEFINITION.equals(captureName);
    }

    @Override
    protected @Nullable CodeUnit findParentForCodeUnit(
            CodeUnit cu,
            TSNode node,
            String captureName,
            String classChain,
            List<ScopeSegment> scopeChain,
            FileAnalysisAccumulator acc,
            SourceContent sourceContent) {
        if (CaptureNames.LAMBDA_DEFINITION.equals(captureName)) {
            var enclosingFnNameOpt = findEnclosingJavaMethodOrClassName(node, sourceContent);
            if (enclosingFnNameOpt.isPresent()) {
                String enclosingFnName = enclosingFnNameOpt.get();
                String methodFqName = classChain.isEmpty() ? enclosingFnName : (classChain + "." + enclosingFnName);
                // Prepend package if present
                if (!cu.packageName().isEmpty()) {
                    methodFqName = cu.packageName() + "." + methodFqName;
                }
                return acc.getByFqName(methodFqName);
            }
        }
        return super.findParentForCodeUnit(cu, node, captureName, classChain, scopeChain, acc, sourceContent);
    }

    @Override
    protected @Nullable CodeUnit createImplicitConstructor(CodeUnit enclosingClass, String classCaptureName) {
        // Java implicit constructors only exist for classes, not interfaces/enums/records/annotations.
        if (!CaptureNames.CLASS_DEFINITION.equals(classCaptureName)) {
            return null;
        }

        // Convention: shortName is "EnclosingClass.shortName + "." + EnclosingClass.identifier()"
        // e.g. for class "Foo" in package "p", shortName is "Foo.Foo" (FQN p.Foo.Foo)
        String constructorName = enclosingClass.identifier();
        String shortName = enclosingClass.shortName() + "." + constructorName;

        return new CodeUnit(enclosingClass.source(), CodeUnitType.FUNCTION, enclosingClass.packageName(), shortName)
                .withSynthetic(true);
    }

    @Override
    public Set<CodeUnit> testFilesToCodeUnits(Collection<ProjectFile> files) {
        var unitsInFiles = AnalyzerUtil.getTestDeclarationsWithLogging(this, files)
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        return AnalyzerUtil.coalesceNestedUnits(this, unitsInFiles);
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        if (!cu.isFunction()) return 0;

        return getSource(cu, false)
                .map(source -> {
                    try (TSTree tree = getTSParser().parseStringOrThrow(null, source)) {
                        TSNode root = tree.getRootNode();
                        if (root == null) return 1;

                        SourceContent content = SourceContent.of(source);
                        int complexity = 1; // Base complexity

                        Deque<TSNode> stack = new ArrayDeque<>();
                        stack.push(root);

                        while (!stack.isEmpty()) {
                            TSNode node = stack.pop();
                            String type = node.getType();
                            if (type == null) {
                                continue;
                            }

                            switch (type) {
                                case IF_STATEMENT,
                                        FOR_STATEMENT,
                                        ENHANCED_FOR_STATEMENT,
                                        WHILE_STATEMENT,
                                        DO_STATEMENT,
                                        CATCH_CLAUSE,
                                        CONDITIONAL_EXPRESSION -> complexity++;
                                case SWITCH_LABEL -> {
                                    // Only count if not the 'default' case
                                    if (!content.substringFrom(node).contains("default")) {
                                        complexity++;
                                    }
                                }
                                case BINARY_EXPRESSION -> {
                                    // Check for && or ||
                                    String operator = content.substringFrom(node);
                                    if (operator.contains("&&") || operator.contains("||")) {
                                        complexity++;
                                    }
                                }
                            }

                            for (int i = 0; i < node.getNamedChildCount(); i++) {
                                TSNode child = node.getNamedChild(i);
                                if (child != null) {
                                    stack.push(child);
                                }
                            }
                        }
                        return complexity;
                    } catch (Exception e) {
                        log.warn("Failed to compute complexity for {} using AST; falling back", cu.fqName(), e);
                        return super.computeCyclomaticComplexity(cu);
                    }
                })
                .orElse(0);
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
        var testMethods = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(METHOD_DECLARATION), testMethods);
        var anonymousShapeCounts = anonymousTestDoubleShapeCounts(testMethods, sourceContent);
        var findings = new ArrayList<TestSmellCandidate>();
        for (TSNode method : testMethods) {
            if (isTestMethod(method, sourceContent)) {
                analyzeTestMethodAssertions(file, method, sourceContent, weights, findings);
                analyzeAnonymousTestDoubles(file, method, sourceContent, weights, anonymousShapeCounts, findings);
            }
        }
        return findings.stream()
                .sorted(Comparator.comparingInt(TestSmellCandidate::score)
                        .reversed()
                        .thenComparing(c -> c.smell().file().toString())
                        .thenComparing(c -> c.smell().enclosingFqName())
                        .thenComparingInt(TestSmellCandidate::startByte))
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private void analyzeTestMethodAssertions(
            ProjectFile file,
            TSNode method,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = method.getChildByFieldName("body");
        if (body == null) {
            return;
        }
        var invocations = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(METHOD_INVOCATION), invocations);
        List<AssertionSignal> assertions = invocations.stream()
                .map(invocation -> assertionSignal(invocation, sourceContent, weights))
                .flatMap(Optional::stream)
                .toList();
        String enclosing = enclosingCodeUnit(
                        file,
                        method.getStartPoint().getRow(),
                        method.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        int assertionCount = assertions.size();

        if (assertionCount == 0) {
            addTestSmell(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_NO_ASSERTIONS,
                    weights.noAssertionWeight(),
                    0,
                    List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                    sourceContent.substringFrom(method),
                    method.getStartByte(),
                    out);
            return;
        }

        for (AssertionSignal assertion : assertions) {
            int score = assertion.baseScore();
            if (score <= 0) {
                continue;
            }
            addTestSmell(
                    file,
                    enclosing,
                    assertion.kind(),
                    score,
                    assertionCount,
                    assertion.reasons(),
                    assertion.excerpt(),
                    assertion.startByte(),
                    out);
        }

        boolean allShallow = assertions.stream().allMatch(AssertionSignal::shallow);
        if (allShallow) {
            int score = weights.shallowAssertionOnlyWeight()
                    - meaningfulAssertionCredit(assertions, weights, AssertionSignal::meaningful);
            if (score > 0) {
                addTestSmell(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(method),
                        method.getStartByte(),
                        out);
            }
        }
    }

    private void analyzeAnonymousTestDoubles(
            ProjectFile file,
            TSNode method,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            Map<String, Integer> anonymousShapeCounts,
            List<TestSmellCandidate> out) {
        var creations = new ArrayList<TSNode>();
        collectNodesByType(method, Set.of(OBJECT_CREATION_EXPRESSION), creations);
        String enclosing = enclosingCodeUnit(
                        file,
                        method.getStartPoint().getRow(),
                        method.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        for (TSNode creation : creations) {
            TSNode classBody = firstNamedChildOfType(creation, CLASS_BODY);
            TSNode typeNode = creation.getChildByFieldName("type");
            if (classBody == null || typeNode == null) {
                continue;
            }
            String shape = anonymousTestDoubleShape(creation, sourceContent);
            boolean repeated = anonymousShapeCounts.getOrDefault(shape, 0) > 1;
            int score = repeated ? weights.repeatedAnonymousTestDoubleWeight() : weights.anonymousTestDoubleWeight();
            var reasons = new ArrayList<String>();
            reasons.add(TEST_ASSERTION_KIND_ANONYMOUS_TEST_DOUBLE);
            if (repeated) {
                reasons.add(TEST_ASSERTION_REASON_REUSABLE_TEST_DOUBLE);
            }
            addTestSmell(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_ANONYMOUS_TEST_DOUBLE,
                    score,
                    0,
                    reasons,
                    sourceContent.substringFrom(creation),
                    creation.getStartByte(),
                    out);
        }
    }

    private Optional<AssertionSignal> assertionSignal(
            TSNode invocation, SourceContent sourceContent, TestAssertionWeights weights) {
        String methodName = methodInvocationName(invocation, sourceContent);
        String text = sourceContent.substringFrom(invocation).strip();
        if (methodName.isBlank() || text.isBlank()) {
            return Optional.empty();
        }

        if (JUNIT_ASSERTION_NAMES.contains(methodName)) {
            return Optional.of(classifyJunitAssertion(invocation, methodName, sourceContent, text, weights));
        }
        if (MOCKITO_VERIFY_NAMES.contains(methodName)) {
            return Optional.of(new AssertionSignal(
                    TEST_ASSERTION_KIND_MOCK_VERIFICATION, 0, false, true, invocation.getStartByte(), List.of(), text));
        }
        if (ASSERTJ_TERMINAL_NAMES.contains(methodName)
                && assertThatArgument(invocation, sourceContent).isPresent()) {
            return Optional.of(classifyAssertJAssertion(invocation, methodName, sourceContent, text, weights));
        }
        return Optional.empty();
    }

    private AssertionSignal classifyJunitAssertion(
            TSNode invocation,
            String methodName,
            SourceContent sourceContent,
            String text,
            TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(invocation);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = SHALLOW_ASSERTION_NAMES.contains(methodName);
        boolean meaningful = !shallow && !FAIL.equals(methodName);
        String kind = TEST_ASSERTION_KIND_JUNIT;

        if ((ASSERT_TRUE.equals(methodName) || ASSERT_FALSE.equals(methodName)) && !args.isEmpty()) {
            TSNode arg = args.getLast();
            boolean constantTruth = (ASSERT_TRUE.equals(methodName) && TRUE.equals(arg.getType()))
                    || (ASSERT_FALSE.equals(methodName) && FALSE.equals(arg.getType()));
            if (constantTruth) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            }
            if (isSelfComparison(arg, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }

        if ((ASSERT_EQUALS.equals(methodName) || ASSERT_SAME.equals(methodName)) && args.size() >= 2) {
            var comparableArgs = comparableAssertionArgs(args);
            TSNode expected = comparableArgs.get(0);
            TSNode actual = comparableArgs.get(1);
            if (isConstantExpression(expected) && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expected, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }

        if ((ASSERT_NOT_NULL.equals(methodName) || ASSERT_NULL.equals(methodName)) && args.size() <= 2) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            meaningful = false;
        }

        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }

        return new AssertionSignal(
                kind, score, shallow, meaningful, invocation.getStartByte(), List.copyOf(reasons), text);
    }

    private AssertionSignal classifyAssertJAssertion(
            TSNode invocation,
            String methodName,
            SourceContent sourceContent,
            String text,
            TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(invocation);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = ASSERTJ_SHALLOW_TERMINAL_NAMES.contains(methodName);
        boolean meaningful = !shallow;
        String kind = TEST_ASSERTION_KIND_ASSERTJ;

        Optional<TSNode> assertThatArg = assertThatArgument(invocation, sourceContent);
        if (assertThatArg.isPresent() && args.size() == 1) {
            TSNode expected = assertThatArg.get();
            TSNode actual = args.getFirst();
            if ((IS_EQUAL_TO.equals(methodName) || IS_SAME_AS.equals(methodName))
                    && isConstantExpression(expected)
                    && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if ((IS_EQUAL_TO.equals(methodName) || IS_SAME_AS.equals(methodName))
                    && sameExpression(expected, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        if ((IS_TRUE.equals(methodName) || IS_FALSE.equals(methodName)) && assertThatArg.isPresent()) {
            TSNode arg = assertThatArg.get();
            boolean constantTruth = (IS_TRUE.equals(methodName) && TRUE.equals(arg.getType()))
                    || (IS_FALSE.equals(methodName) && FALSE.equals(arg.getType()));
            if (constantTruth) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            }
        }
        if (shallow) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            meaningful = false;
        }
        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }
        return new AssertionSignal(
                kind, score, shallow, meaningful, invocation.getStartByte(), List.copyOf(reasons), text);
    }

    private static boolean isTestMethod(TSNode method, SourceContent sourceContent) {
        TSNode modifiers = firstNamedChildOfType(method, MODIFIERS);
        if (modifiers == null) {
            return false;
        }
        var annotations = new ArrayList<TSNode>();
        collectNodesByType(modifiers, Set.of(ANNOTATION, MARKER_ANNOTATION), annotations);
        return annotations.stream()
                .map(annotation -> annotationName(annotation, sourceContent))
                .anyMatch(TEST_ANNOTATIONS::contains);
    }

    private static String methodInvocationName(TSNode invocation, SourceContent sourceContent) {
        TSNode nameNode = invocation.getChildByFieldName("name");
        return nameNode == null ? "" : sourceContent.substringFrom(nameNode).strip();
    }

    private static List<TSNode> argumentNodes(TSNode invocation) {
        TSNode arguments = invocation.getChildByFieldName("arguments");
        if (arguments == null || !ARGUMENT_LIST.equals(arguments.getType())) {
            return List.of();
        }
        var args = new ArrayList<TSNode>();
        for (int i = 0; i < arguments.getNamedChildCount(); i++) {
            TSNode child = arguments.getNamedChild(i);
            if (child != null) {
                args.add(child);
            }
        }
        return List.copyOf(args);
    }

    private static List<TSNode> comparableAssertionArgs(List<TSNode> args) {
        if (args.size() >= 4 && isStringLiteral(args.getFirst())) {
            return args.subList(1, 3);
        }
        return args.subList(0, Math.min(2, args.size()));
    }

    private static Optional<TSNode> assertThatArgument(TSNode invocation, SourceContent sourceContent) {
        TSNode candidate = invocation.getChildByFieldName("object");
        while (candidate != null && METHOD_INVOCATION.equals(candidate.getType())) {
            TSNode nameNode = candidate.getChildByFieldName("name");
            if (nameNode != null
                    && ASSERT_THAT.equals(sourceContent.substringFrom(nameNode).strip())) {
                return argumentNodes(candidate).stream().findFirst();
            }
            candidate = candidate.getChildByFieldName("object");
        }
        return Optional.empty();
    }

    private static boolean isSelfComparison(TSNode node, SourceContent sourceContent) {
        if (BINARY_EXPRESSION.equals(node.getType())) {
            TSNode left = node.getChildByFieldName("left");
            TSNode right = node.getChildByFieldName("right");
            return left != null && right != null && sameExpression(left, right, sourceContent);
        }
        if (METHOD_INVOCATION.equals(node.getType()) && EQUALS.equals(methodInvocationName(node, sourceContent))) {
            TSNode objectNode = node.getChildByFieldName("object");
            return objectNode != null
                    && argumentNodes(node).stream()
                            .findFirst()
                            .map(arg -> sameExpression(objectNode, arg, sourceContent))
                            .orElse(false);
        }
        return false;
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean isConstantExpression(TSNode node) {
        return CONSTANT_LITERAL_TYPES.contains(node.getType());
    }

    private static boolean isStringLiteral(TSNode node) {
        return STRING_LITERAL.equals(node.getType());
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream()
                .anyMatch(arg -> isStringLiteral(arg)
                        && sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold());
    }

    private static int meaningfulAssertionCredit(
            List<AssertionSignal> assertions,
            TestAssertionWeights weights,
            java.util.function.Predicate<AssertionSignal> predicate) {
        long count = assertions.stream().filter(predicate).count();
        int creditable = Math.min((int) count, Math.max(0, weights.meaningfulAssertionCreditCap()));
        return Math.max(0, weights.meaningfulAssertionCredit()) * creditable;
    }

    private static String annotationName(TSNode annotation, SourceContent sourceContent) {
        TSNode nameNode = annotation.getChildByFieldName("name");
        String rawName = nameNode == null
                ? sourceContent.substringFrom(annotation).strip()
                : sourceContent.substringFrom(nameNode).strip();
        rawName = rawName.replaceFirst("^@\\s*", "");
        int lastDot = rawName.lastIndexOf('.');
        return lastDot >= 0 ? rawName.substring(lastDot + 1) : rawName;
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && type.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private void addTestSmell(
            ProjectFile file,
            String enclosing,
            String assertionKind,
            int score,
            int assertionCount,
            List<String> reasons,
            String excerptSource,
            int startByte,
            List<TestSmellCandidate> out) {
        if (score <= 0 || reasons.isEmpty()) {
            return;
        }
        var smell = new TestAssertionSmell(
                file,
                enclosing,
                assertionKind,
                score,
                assertionCount,
                List.copyOf(reasons),
                compactCatchExcerpt(excerptSource));
        out.add(new TestSmellCandidate(smell, startByte));
    }

    private static Map<String, Integer> anonymousTestDoubleShapeCounts(
            List<TSNode> testMethods, SourceContent sourceContent) {
        return testMethods.stream()
                .filter(method -> isTestMethod(method, sourceContent))
                .flatMap(method -> {
                    var creations = new ArrayList<TSNode>();
                    collectNodesByType(method, Set.of(OBJECT_CREATION_EXPRESSION), creations);
                    return creations.stream()
                            .filter(creation -> firstNamedChildOfType(creation, CLASS_BODY) != null)
                            .map(creation -> anonymousTestDoubleShape(creation, sourceContent));
                })
                .collect(Collectors.toMap(shape -> shape, shape -> 1, Integer::sum));
    }

    private static String anonymousTestDoubleShape(TSNode creation, SourceContent sourceContent) {
        TSNode typeNode = creation.getChildByFieldName("type");
        String type = typeNode == null
                ? "<unknown>"
                : sourceContent.substringFrom(typeNode).strip();
        var methods = new ArrayList<TSNode>();
        collectNodesByType(creation, Set.of(METHOD_DECLARATION), methods);
        String methodNames = methods.stream()
                .map(method -> method.getChildByFieldName("name"))
                .filter(Objects::nonNull)
                .map(sourceContent::substringFrom)
                .collect(Collectors.joining(","));
        return type + "#" + methodNames;
    }

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var findings = new ArrayList<SmellCandidate>();
        collectCatchSmells(file, root, sourceContent, weights, findings);
        return findings.stream()
                .sorted(Comparator.comparingInt(SmellCandidate::score)
                        .reversed()
                        .thenComparing(c -> c.smell().file().toString())
                        .thenComparing(c -> c.smell().enclosingFqName())
                        .thenComparingInt(SmellCandidate::startByte))
                .map(SmellCandidate::smell)
                .toList();
    }

    private void collectCatchSmells(
            ProjectFile file,
            TSNode node,
            SourceContent sourceContent,
            ExceptionSmellWeights weights,
            List<SmellCandidate> out) {
        if (node == null) {
            return;
        }
        if (CATCH_CLAUSE.equals(node.getType())) {
            analyzeCatchClause(file, node, sourceContent, weights).ifPresent(out::add);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectCatchSmells(file, child, sourceContent, weights, out);
            }
        }
    }

    private Optional<SmellCandidate> analyzeCatchClause(
            ProjectFile file, TSNode catchNode, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode catchParam = null;
        for (TSNode child : catchNode.getNamedChildren()) {
            if (CATCH_FORMAL_PARAMETER.equals(child.getType())) {
                catchParam = child;
                break;
            }
        }
        if (catchParam == null) {
            return Optional.empty();
        }

        TSNode typeNode = catchParam.getChildByFieldName("type");
        String catchType = sourceContent.substringFrom(typeNode).strip();
        if (catchType.isEmpty()) {
            TSNode nameNode = catchParam.getChildByFieldName("name");
            String paramText = sourceContent.substringFrom(catchParam).strip();
            if (nameNode != null) {
                String name = sourceContent.substringFrom(nameNode).strip();
                int nameIdx = paramText.lastIndexOf(name);
                if (nameIdx > 0) {
                    catchType = paramText.substring(0, nameIdx).strip();
                }
            }
            if (catchType.isEmpty()) {
                catchType = paramText;
            }
        }
        if (catchType.isEmpty()) {
            return Optional.empty();
        }

        TSNode bodyNode = catchNode.getChildByFieldName("body");
        if (bodyNode == null) {
            bodyNode = catchNode.getNamedChildren().stream()
                    .filter(child -> BLOCK.equals(child.getType()))
                    .findFirst()
                    .orElse(null);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }
        String bodyText = sourceContent.substringFrom(bodyNode);
        int bodyStatements = countBodyExpressions(bodyNode);
        boolean hasAnyComment = bodyText.contains("//") || bodyText.contains("/*");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent = hasDescendantOfType(bodyNode, THROW_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.contains("Throwable")) {
            score += weights.genericThrowableWeight();
            reasons.add("generic-catch:Throwable");
        } else if (catchType.contains("Exception")) {
            if (catchType.contains("RuntimeException")) {
                score += weights.genericRuntimeExceptionWeight();
                reasons.add("generic-catch:RuntimeException");
            } else {
                score += weights.genericExceptionWeight();
                reasons.add("generic-catch:Exception");
            }
        }
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
                        catchNode.getStartPoint().getRow(),
                        catchNode.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        String excerpt = compactCatchExcerpt(sourceContent.substringFrom(catchNode));
        var smell = new ExceptionHandlingSmell(
                file, enclosing, catchType, score, bodyStatements, List.copyOf(reasons), excerpt);
        return Optional.of(new SmellCandidate(smell, catchNode.getStartByte()));
    }

    private static int countBodyExpressions(TSNode bodyNode) {
        int expressions = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (LINE_COMMENT.equals(type) || BLOCK_COMMENT.equals(type)) {
                continue;
            }
            if (CATCH_BODY_MEANINGFUL_STATEMENT_TYPES.contains(type)) {
                expressions++;
            }
        }
        return expressions;
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, JAVA_COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode invocation = findFirstNamedDescendant(statement, METHOD_INVOCATION);
        if (invocation == null) {
            return false;
        }
        TSNode objectNode = invocation.getChildByFieldName("object");
        if (objectNode == null) {
            return false;
        }
        String receiverText = sourceContent.substringFrom(objectNode).strip().toLowerCase(Locale.ROOT);
        if (receiverText.isEmpty()) {
            return false;
        }
        return LOG_RECEIVER_NAMES.contains(receiverText)
                || LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiverText.endsWith("." + name));
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private record SmellCandidate(ExceptionHandlingSmell smell, int startByte) {
        int score() {
            return smell.score();
        }
    }

    private record TestSmellCandidate(TestAssertionSmell smell, int startByte) {
        int score() {
            return smell.score();
        }
    }

    private record AssertionSignal(
            String kind,
            int baseScore,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    @Override
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, TSNode classNode, String signature, SourceContent sourceContent) {
        // Aggregate all @type.super captures for the same @type.decl across all matches.
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    // Ascend to the root node for matching
                    TSNode root = classNode;
                    while (root.getParent() != null) {
                        root = root.getParent();
                    }

                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        List<TSNode> aggregateSuperNodes = new ArrayList<>();
                        cursor.exec(query, root, sourceContent.text());

                        TSQueryMatch match = new TSQueryMatch();
                        final int targetStart = classNode.getStartByte();
                        final int targetEnd = classNode.getEndByte();

                        while (cursor.nextMatch(match)) {
                            TSNode declNode = null;
                            List<TSNode> superCapturesThisMatch = new ArrayList<>();

                            for (TSQueryCapture cap : match.getCaptures()) {
                                String capName = query.getCaptureNameForId(cap.getIndex());
                                TSNode n = cap.getNode();
                                if (n == null) continue;

                                if ("type.decl".equals(capName)) {
                                    declNode = n;
                                } else if ("type.super".equals(capName)) {
                                    superCapturesThisMatch.add(n);
                                }
                            }

                            if (declNode != null
                                    && declNode.getStartByte() == targetStart
                                    && declNode.getEndByte() == targetEnd) {
                                aggregateSuperNodes.addAll(superCapturesThisMatch);
                            }
                        }

                        aggregateSuperNodes.sort(Comparator.comparingInt(TSNode::getStartByte));

                        List<String> supers = new ArrayList<>(aggregateSuperNodes.size());
                        for (TSNode s : aggregateSuperNodes) {
                            String text = sourceContent.substringFrom(s).strip();
                            if (!text.isEmpty()) {
                                supers.add(text);
                            }
                        }

                        LinkedHashSet<String> unique = new LinkedHashSet<>(supers);
                        return List.copyOf(unique);
                    }
                },
                List.of());
    }

    private static final class CommentAgg {
        int headerLines;
        int inlineLines;
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        checkStale("commentDensity");
        if (!Languages.JAVA.equals(Languages.fromExtension(cu.source().extension()))) {
            return Optional.empty();
        }
        Map<String, CommentAgg> aggs = collectCommentAggregates(cu.source());
        CommentDensityStats stats = buildRollUpStats(cu, aggs);
        return Optional.of(stats);
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        checkStale("commentDensityByTopLevel");
        if (!Languages.JAVA.equals(Languages.fromExtension(file.extension()))) {
            return List.of();
        }
        Map<String, CommentAgg> aggs = collectCommentAggregates(file);
        List<CommentDensityStats> rows = new ArrayList<>();
        for (CodeUnit top : getTopLevelDeclarations(file)) {
            rows.add(buildRollUpStats(top, aggs));
        }
        return List.copyOf(rows);
    }

    /**
     * Walks the parse tree for line_comment and block_comment nodes, associates each with the smallest enclosing
     * declaration span (using {@link Range#commentStartByte()} through {@link Range#endByte()}), and classifies
     * header vs inline: header if the comment ends on or before the declaration {@link Range#startByte()}.
     */
    private Map<String, CommentAgg> collectCommentAggregates(ProjectFile file) {
        return withTreeOf(
                file,
                tree -> {
                    List<TSNode> comments = new ArrayList<>();
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return Map.of();
                    }
                    collectCommentNodes(root, comments);
                    Map<String, CommentAgg> map = new HashMap<>();
                    for (TSNode c : comments) {
                        int cs = c.getStartByte();
                        int ce = c.getEndByte();
                        Optional<OwningRange> own = findOwningRangeForComment(file, cs, ce);
                        if (own.isEmpty()) {
                            continue;
                        }
                        OwningRange or = own.get();
                        Range r = or.range();
                        boolean header = ce <= r.startByte();
                        int lines = c.getEndPoint().getRow() - c.getStartPoint().getRow() + 1;
                        CommentAgg agg = map.computeIfAbsent(or.cu().fqName(), k -> new CommentAgg());
                        if (header) {
                            agg.headerLines += lines;
                        } else {
                            agg.inlineLines += lines;
                        }
                    }
                    return map;
                },
                Map.of());
    }

    private record OwningRange(CodeUnit cu, Range range) {}

    private Optional<OwningRange> findOwningRangeForComment(ProjectFile file, int cs, int ce) {
        return enclosingCodeUnitByCommentBytes(file, cs, ce).flatMap(cu -> rangesOf(cu).stream()
                .filter(r -> cs >= r.commentStartByte() && ce <= r.endByte())
                .min(Comparator.comparingInt(r -> r.endByte() - r.commentStartByte()))
                .map(r -> new OwningRange(cu, r)));
    }

    private static void collectCommentNodes(TSNode node, List<TSNode> out) {
        if (node == null) {
            return;
        }
        String t = node.getType();
        if (LINE_COMMENT.equals(t) || BLOCK_COMMENT.equals(t)) {
            out.add(node);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode ch = node.getChild(i);
            if (ch != null) {
                collectCommentNodes(ch, out);
            }
        }
    }

    private int spanLines(CodeUnit cu) {
        return rangesOf(cu).stream()
                .mapToInt(r -> r.endLine() - r.startLine() + 1)
                .sum();
    }

    /**
     * Non-class units: rolled-up fields mirror own lines. Class units: rolled-up sums descendant comment lines and
     * span lines (child spans summed; not deduplicated against the outer class span).
     */
    private CommentDensityStats buildRollUpStats(CodeUnit cu, Map<String, CommentAgg> aggs) {
        CommentAgg own = aggs.getOrDefault(cu.fqName(), new CommentAgg());
        int span = spanLines(cu);
        String path = cu.source().toString();
        if (!cu.isClass()) {
            return new CommentDensityStats(
                    cu.fqName(), path, own.headerLines, own.inlineLines, span, own.headerLines, own.inlineLines, span);
        }
        int rh = own.headerLines;
        int ri = own.inlineLines;
        int rs = span;
        for (CodeUnit ch : getDirectChildren(cu)) {
            CommentDensityStats chs = buildRollUpStats(ch, aggs);
            rh += chs.rolledUpHeaderCommentLines();
            ri += chs.rolledUpInlineCommentLines();
            rs += chs.rolledUpSpanLines();
        }
        return new CommentDensityStats(cu.fqName(), path, own.headerLines, own.inlineLines, span, rh, ri, rs);
    }
}
