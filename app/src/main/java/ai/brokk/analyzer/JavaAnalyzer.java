package ai.brokk.analyzer;

import static ai.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import ai.brokk.analyzer.java.JavaTypeAnalyzer;
import ai.brokk.project.IProject;
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
import org.treesitter.TreeSitterJava;

public class JavaAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider, TypeHierarchyProvider {

    private static final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");

    public JavaAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public JavaAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.JAVA, listener);
    }

    private JavaAnalyzer(IProject project, AnalyzerState state, ProgressListener listener) {
        super(project, Languages.JAVA, state, listener);
    }

    public static JavaAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new JavaAnalyzer(project, state, listener);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state, ProgressListener listener) {
        return new JavaAnalyzer(getProject(), state, listener);
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
    protected String getQueryResource() {
        return "treesitter/java.scm";
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

        // For modules, compute the parent package and short name from the full package string.
        // simpleName contains the full package (e.g., "com.example.foo"), so split it.
        if (type == CodeUnitType.MODULE) {
            String fullPackage = simpleName;
            int lastDot = fullPackage.lastIndexOf('.');
            String parentPkg = lastDot > 0 ? fullPackage.substring(0, lastDot) : "";
            String leafName = lastDot > 0 ? fullPackage.substring(lastDot + 1) : fullPackage;
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
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            final var child = rootNode.getChild(i);
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
            for (int i = 0; i < maybeDeclaration.getNamedChildCount(); i++) {
                final TSNode nameNode = maybeDeclaration.getNamedChild(i);
                if (nameNode != null && !nameNode.isNull()) {
                    String type = nameNode.getType();
                    if (ANNOTATION.equals(type) || MARKER_ANNOTATION.equals(type)) {
                        continue;
                    }
                    String nsPart = textSlice.apply(nameNode, sourceContent);
                    // Special handling for nodes that might have child identifiers (like Scala package_identifier)
                    if (nsPart.isEmpty() && nameNode.getNamedChildCount() > 0) {
                        List<String> parts = new ArrayList<>();
                        for (int j = 0; j < nameNode.getNamedChildCount(); j++) {
                            TSNode child = nameNode.getNamedChild(j);
                            if (child != null && !child.isNull()) {
                                String childText = textSlice.apply(child, sourceContent);
                                if (!childText.isEmpty()) {
                                    parts.add(childText);
                                }
                            }
                        }
                        nsPart = String.join(".", parts);
                    }
                    if (!nsPart.isEmpty()) {
                        namespaceParts.add(nsPart);
                    }
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
            String baseIndent,
            ProjectFile file) {
        if (ENUM_CONSTANT.equals(fieldNode.getType())) {
            return formatEnumConstant(fieldNode, signatureText, baseIndent);
        }
        return super.formatFieldSignature(fieldNode, sourceContent, exportPrefix, signatureText, baseIndent, file);
    }

    private String formatEnumConstant(TSNode fieldNode, String signatureText, String baseIndent) {
        TSNode parent = fieldNode.getParent();
        if (parent != null) {
            int childCount = parent.getNamedChildCount();
            boolean hasFollowingConstant = false;

            // Compare by byte range to reliably identify the same node
            int targetStart = fieldNode.getStartByte();
            int targetEnd = fieldNode.getEndByte();

            for (int i = 0; i < childCount; i++) {
                TSNode child = parent.getNamedChild(i);
                if (child != null
                        && !child.isNull()
                        && child.getStartByte() == targetStart
                        && child.getEndByte() == targetEnd) {
                    // Check if any subsequent named child is also an enum_constant
                    for (int j = i + 1; j < childCount; j++) {
                        TSNode next = parent.getNamedChild(j);
                        if (next != null && !next.isNull() && ENUM_CONSTANT.equals(next.getType())) {
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

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        var query = getThreadLocalQuery();
        var cursor = new TSQueryCursor();
        cursor.exec(query, tree.getRootNode());

        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(capture.getIndex());
                if (TEST_MARKER.equals(captureName)) {
                    TSNode node = capture.getNode();
                    String rawName = sourceContent.substringFromBytes(node.getStartByte(), node.getEndByte());
                    String simpleName = rawName.strip();
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
        return false;
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
                        Set<String> candidateSymbols =
                                extractTypeIdentifiers(candidate.read().orElse(""));
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
        while (current != null && !current.isNull()) {
            String type = current.getType();
            if (METHOD_DECLARATION.equals(type) || CONSTRUCTOR_DECLARATION.equals(type)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
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
        while (current != null && !current.isNull()) {
            if (isClassLike(current)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
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
        if (importNode != null && !importNode.isNull()) {
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
            Optional<CodeUnit> pkgModule = getDefinitions(packageName).stream()
                    .filter(CodeUnit::isModule)
                    .findFirst();

            if (pkgModule.isPresent()) {
                for (CodeUnit child : getDirectChildren(pkgModule.get())) {
                    if (child.isClass()
                            && packageName.equals(child.packageName())
                            && resolvedSimpleNames.add(child.identifier())) {
                        resolved.add(child);
                    }
                }
            }
        }

        return Collections.unmodifiableSet(resolved);
    }

    @Override
    public List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Pull cached raw supertypes from CodeUnitProperties
        var rawNames = withCodeUnitProperties(
                props -> props.getOrDefault(cu, CodeUnitProperties.empty()).rawSupertypes());

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
        TSTree tree = treeOf(file);
        if (tree == null) return true;

        TSNode root = tree.getRootNode();
        if (root.isNull()) return true;

        TSNode node = root.getDescendantForByteRange(startByte, endByte);
        if (node == null || node.isNull()) return true;

        // 1. Check if the node itself or any parent is a comment
        TSNode walk = node;
        while (walk != null && !walk.isNull()) {
            if (isCommentNode(walk)) return false;
            walk = walk.getParent();
        }

        // 2. Check if we are in a declaration context (name of a method, field, param, etc.)
        TSNode current = node;
        while (current != null && !current.isNull()) {
            String type = current.getType();

            // If we hit a known reference/usage node type, it's likely a reference
            if (type.equals(METHOD_INVOCATION)
                    || type.equals(FIELD_ACCESS)
                    || type.equals(OBJECT_CREATION_EXPRESSION)
                    || type.equals(TYPE_IDENTIFIER)
                    || type.equals(SCOPED_TYPE_IDENTIFIER)
                    || type.equals(MARKER_ANNOTATION)
                    || type.equals(ANNOTATION)
                    || type.equals(CLASS_LITERAL)
                    || type.equals(IMPORT_DECLARATION)) {
                break; // Continue to nearest declaration check
            }

            // If we are the 'name' child of a declaration, it's not a reference
            TSNode parent = current.getParent();
            if (parent != null && !parent.isNull()) {
                String pType = parent.getType();
                if (pType.equals(METHOD_DECLARATION)
                        || pType.equals(FIELD_DECLARATION)
                        || pType.equals(CLASS_DECLARATION)
                        || pType.equals(INTERFACE_DECLARATION)
                        || pType.equals(ENUM_DECLARATION)
                        || pType.equals(RECORD_DECLARATION)
                        || pType.equals(VARIABLE_DECLARATOR)
                        || pType.equals(FORMAL_PARAMETER)) {

                    TSNode nameNode = parent.getChildByFieldName("name");
                    if (nameNode != null && !nameNode.isNull() && nameNode.getStartByte() == startByte) {
                        return false;
                    }
                }
            }
            current = current.getParent();
        }

        // 3. Perform lexical scope analysis to filter out local variables and parameters
        // Skip this if the current node is explicitly part of a member access (e.g., this.field or obj.field)
        TSNode parent = node.getParent();
        if (parent != null && !parent.isNull()) {
            String pType = parent.getType();
            if (pType.equals(FIELD_ACCESS)) {
                // In tree-sitter-java, field_access has a "field" child for the member name
                TSNode fieldNode = parent.getChildByFieldName("field");
                if (fieldNode != null && !fieldNode.isNull() && fieldNode.getStartByte() == node.getStartByte()) {
                    return true;
                }
            }
            if (pType.equals(METHOD_INVOCATION)) {
                // In tree-sitter-java, method_invocation has a "name" child for the method name
                TSNode nameNode = parent.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull() && nameNode.getStartByte() == node.getStartByte()) {
                    return true;
                }
            }
        }

        var sourceContentOpt = SourceContent.read(file);
        if (sourceContentOpt.isPresent()) {
            SourceContent sourceContent = sourceContentOpt.get();
            String identifierName = sourceContent.substringFrom(node).strip();
            if (!identifierName.isEmpty()) {
                var declOpt = findNearestDeclaration(file, startByte, endByte, identifierName);
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
        }

        return true;
    }

    @Override
    protected void createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            Map<String, CodeUnit> localCuByFqName,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges,
            Map<CodeUnit, List<CodeUnit>> localChildren) {
        if (modulePackageName.isBlank()) {
            return;
        }

        // Look up the module in localCuByFqName (created via captures).
        // Only use modules that are already present; do not create new ones.
        CodeUnit moduleCu = localCuByFqName.get(modulePackageName);
        if (moduleCu == null || !moduleCu.isModule()) {
            return;
        }

        // Filter localTopLevelCUs to find top-level classes in this package.
        List<CodeUnit> classesInPackage = localTopLevelCUs.stream()
                .filter(cu -> cu.isClass() && modulePackageName.equals(cu.packageName()))
                .toList();

        // Always record the module's children (even if empty) so callers can distinguish
        // "known module with no children" from "no relationship recorded".
        localChildren.put(moduleCu, new ArrayList<>(classesInPackage));
    }

    /**
     * Extracts type identifiers using Tree-Sitter.
     * <p>
     * Trade-off: High Precision. By targeting only {@code type_identifier} nodes, we minimize false positives
     * from local variables or method names, ensuring that only relevant type-related imports are pulled in.
     */
    @Override
    public Set<String> extractTypeIdentifiers(String source) {
        try {
            TSTree tree = getTSParser().parseString(null, source);
            TSNode root = tree.getRootNode();
            if (root.isNull()) {
                return Set.of();
            }

            org.treesitter.TSQuery identifierQuery =
                    new org.treesitter.TSQuery(getTSLanguage(), "[(type_identifier) (scoped_type_identifier)] @type");
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(identifierQuery, root);

            SourceContent sourceContent = SourceContent.of(source);
            Set<String> identifiers = new HashSet<>();
            TSQueryMatch match = new TSQueryMatch();

            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    TSNode node = capture.getNode();
                    if (node != null && !node.isNull()) {
                        String text = sourceContent.substringFrom(node);
                        if (!text.isEmpty()) {
                            identifiers.add(text);
                        }
                    }
                }
            }
            return identifiers;
        } catch (Exception e) {
            log.warn("Failed to extract type identifiers using Tree-Sitter query", e);
            return Set.of();
        }
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
        TSTree tree = treeOf(file);
        if (tree == null) return Optional.empty();

        TSNode root = tree.getRootNode();
        if (root.isNull()) return Optional.empty();

        TSNode node = root.getDescendantForByteRange(startByte, endByte);
        if (node == null || node.isNull()) return Optional.empty();

        var sourceContentOpt = SourceContent.read(file);
        if (sourceContentOpt.isEmpty()) return Optional.empty();

        return findNearestDeclarationFromNode(node, identifierName, sourceContentOpt.get());
    }

    /**
     * Walks upward from startNode through enclosing scopes, checking for declarations
     * with a matching identifier name. Returns the first match found.
     */
    private Optional<DeclarationInfo> findNearestDeclarationFromNode(
            TSNode startNode, String identifierName, SourceContent sourceContent) {
        TSNode current = startNode;

        while (current != null && !current.isNull()) {
            String nodeType = current.getType();

            // Check method/constructor parameters
            if (nodeType.equals(METHOD_DECLARATION) || nodeType.equals(CONSTRUCTOR_DECLARATION)) {
                var paramResult = checkFormalParameters(current, identifierName, sourceContent);
                if (paramResult.isPresent()) return paramResult;
            }

            // Check local variable declarations among preceding siblings
            var localResult = checkPrecedingLocalVariables(current, identifierName, sourceContent);
            if (localResult.isPresent()) return localResult;

            // Check enhanced for loop variable
            if (nodeType.equals(ENHANCED_FOR_STATEMENT)) {
                var forResult = checkEnhancedForStatement(current, identifierName, sourceContent);
                if (forResult.isPresent()) return forResult;
            }

            // Check catch formal parameter
            if ("catch_clause".equals(nodeType)) {
                // catch_formal_parameter is a direct named child, not accessed via field name
                for (int i = 0; i < current.getNamedChildCount(); i++) {
                    TSNode child = current.getNamedChild(i);
                    if (child != null && !child.isNull() && CATCH_FORMAL_PARAMETER.equals(child.getType())) {
                        var catchResult = checkCatchFormalParameter(child, identifierName, sourceContent);
                        if (catchResult.isPresent()) return catchResult;
                        break;
                    }
                }
            }

            // Check try-with-resources
            if (nodeType.equals("try_with_resources_statement")) {
                TSNode resourceSpec = current.getChildByFieldName("resources");
                if (resourceSpec != null && !resourceSpec.isNull()) {
                    var resourceResult = checkResourceSpecification(resourceSpec, identifierName, sourceContent);
                    if (resourceResult.isPresent()) return resourceResult;
                }
            }

            // Check lambda parameters
            if (nodeType.equals(LAMBDA_EXPRESSION)) {
                var lambdaResult = checkLambdaParameters(current, identifierName, sourceContent);
                if (lambdaResult.isPresent()) return lambdaResult;
            }

            // Check instanceof pattern variable (Java 16+)
            if (nodeType.equals(INSTANCEOF_EXPRESSION)) {
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
        if (params == null || params.isNull()) return Optional.empty();

        for (int i = 0; i < params.getNamedChildCount(); i++) {
            TSNode param = params.getNamedChild(i);
            if (param != null && !param.isNull() && FORMAL_PARAMETER.equals(param.getType())) {
                TSNode nameNode = param.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
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
        if (parent == null || parent.isNull()) return Optional.empty();

        // Local variables are declared in local_variable_declaration nodes that are siblings
        // to the current node's path.
        for (int i = 0; i < parent.getNamedChildCount(); i++) {
            TSNode sibling = parent.getNamedChild(i);
            if (sibling.getEndByte() > current.getStartByte()) break;

            if (LOCAL_VARIABLE_DECLARATION.equals(sibling.getType())) {
                for (int j = 0; j < sibling.getNamedChildCount(); j++) {
                    TSNode child = sibling.getNamedChild(j);
                    if (VARIABLE_DECLARATOR.equals(child.getType())) {
                        TSNode nameNode = child.getChildByFieldName("name");
                        if (nameNode != null && !nameNode.isNull()) {
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
        if (nameNode != null && !nameNode.isNull()) {
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
        if (nameNode != null && !nameNode.isNull()) {
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
        for (int i = 0; i < resourceSpec.getNamedChildCount(); i++) {
            TSNode resource = resourceSpec.getNamedChild(i);
            if (resource != null && !resource.isNull() && RESOURCE.equals(resource.getType())) {
                TSNode nameNode = resource.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
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
        if (params == null || params.isNull()) return Optional.empty();

        String paramsType = params.getType();

        // formal_parameters case (typed lambda: (String x) -> ...)
        if (FORMAL_PARAMETERS.equals(paramsType)) {
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                TSNode param = params.getNamedChild(i);
                if (param != null && !param.isNull() && FORMAL_PARAMETER.equals(param.getType())) {
                    TSNode nameNode = param.getChildByFieldName("name");
                    if (nameNode != null && !nameNode.isNull()) {
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
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                TSNode param = params.getNamedChild(i);
                if (param != null && !param.isNull()) {
                    String name = sourceContent.substringFrom(param).strip();
                    if (identifierName.equals(name)) {
                        return Optional.of(new DeclarationInfo(DeclarationKind.PARAMETER, name, null));
                    }
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
        if (pattern != null && !pattern.isNull()) {
            TSNode nameNode = pattern.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
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
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, TSNode classNode, String signature, SourceContent sourceContent) {
        // Aggregate all @type.super captures for the same @type.decl across all matches.
        var query = getThreadLocalQuery();

        // Ascend to the root node for matching
        TSNode root = classNode;
        while (root.getParent() != null && !root.getParent().isNull()) {
            root = root.getParent();
        }

        var cursor = new TSQueryCursor();
        cursor.exec(query, root);

        TSQueryMatch match = new TSQueryMatch();
        List<TSNode> aggregateSuperNodes = new ArrayList<>();

        final int targetStart = classNode.getStartByte();
        final int targetEnd = classNode.getEndByte();

        while (cursor.nextMatch(match)) {
            TSNode declNode = null;
            List<TSNode> superCapturesThisMatch = new ArrayList<>();

            for (TSQueryCapture cap : match.getCaptures()) {
                String capName = query.getCaptureNameForId(cap.getIndex());
                TSNode n = cap.getNode();
                if (n == null || n.isNull()) continue;

                if ("type.decl".equals(capName)) {
                    declNode = n;
                } else if ("type.super".equals(capName)) {
                    superCapturesThisMatch.add(n);
                }
            }

            if (declNode != null && declNode.getStartByte() == targetStart && declNode.getEndByte() == targetEnd) {
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
}
