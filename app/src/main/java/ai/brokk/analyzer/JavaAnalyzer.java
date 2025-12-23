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
import org.treesitter.TreeSitterJava;

public class JavaAnalyzer extends TreeSitterAnalyzer {

    private static final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");
    private static final String LAMBDA_EXPRESSION = "lambda_expression";

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
            Set.of(FIELD_DECLARATION, ENUM_CONSTANT),
            Set.of("annotation", "marker_annotation"),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.RECORD_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.ANNOTATION_DEFINITION, SkeletonType.CLASS_LIKE, // for @interface
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.LAMBDA_DEFINITION, SkeletonType.FUNCTION_LIKE),
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
            if (packageDef.equals(child.getType())) {
                maybeDeclaration = child;
                break;
            } else if (classLikeNodeType.contains(child.getType())) {
                break;
            }
        }

        if (maybeDeclaration != null && packageDef.equals(maybeDeclaration.getType())) {
            for (int i = 0; i < maybeDeclaration.getNamedChildCount(); i++) {
                final TSNode nameNode = maybeDeclaration.getNamedChild(i);
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice.apply(nameNode, sourceContent);
                    namespaceParts.add(nsPart);
                }
            }
        }
        Collections.reverse(namespaceParts);
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

        // 1. First pass: parse all import statements, separating explicit from wildcard.
        for (String importLine : importStatements) {
            if (importLine.isBlank()) continue;

            String normalized = importLine.strip();
            if (!normalized.startsWith("import ") || normalized.startsWith("import static ")) {
                continue;
            }

            if (normalized.endsWith(";")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            normalized = normalized.substring("import ".length()).trim();

            if (normalized.endsWith(".*")) {
                String packageName =
                        normalized.substring(0, normalized.length() - 2).trim();
                if (!packageName.isEmpty()) {
                    wildcardImportPackages.add(packageName);
                }
            } else if (!normalized.isEmpty()) {
                // Explicit import: find the exact class and add it.
                getDefinitions(normalized).stream()
                        .filter(CodeUnit::isClass)
                        .findFirst()
                        .ifPresent(explicitImports::add);
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
        // Create a MODULE CodeUnit for the current file's package and attach the file's top-level classes as children.
        if (modulePackageName.isBlank()) {
            return; // default package: no module CU
        }

        // Locate the package_declaration node to compute a precise range for the module signature
        TSNode packageNode = null;
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode child = rootNode.getChild(i);
            if (child != null && !child.isNull() && PACKAGE_DECLARATION.equals(child.getType())) {
                packageNode = child;
                break;
            }
        }

        // Determine parent package and simple name, so that fqName(parent + "." + short) == modulePackageName
        int idx = modulePackageName.lastIndexOf('.');
        String parentPkg = idx >= 0 ? modulePackageName.substring(0, idx) : "";
        String simpleName = idx >= 0 ? modulePackageName.substring(idx + 1) : modulePackageName;

        CodeUnit moduleCu = CodeUnit.module(file, parentPkg, simpleName);

        // Signature for a Java package module
        String signature = "package " + modulePackageName + ";";
        localSignatures.computeIfAbsent(moduleCu, k -> new ArrayList<>()).add(signature);

        // Range covering the package declaration (when available)
        if (packageNode != null) {
            Range r = new Range(
                    packageNode.getStartByte(),
                    packageNode.getEndByte(),
                    packageNode.getStartPoint().getRow(),
                    packageNode.getEndPoint().getRow(),
                    packageNode.getStartByte());
            localSourceRanges.computeIfAbsent(moduleCu, k -> new ArrayList<>()).add(r);
        }

        // Children: include only top-level classes declared in this exact package
        List<CodeUnit> classesInThisFileAndPackage = new ArrayList<>();
        for (CodeUnit cu : localTopLevelCUs) {
            if (cu.isClass() && modulePackageName.equals(cu.packageName())) {
                classesInThisFileAndPackage.add(cu);
            }
        }
        localChildren.put(moduleCu, classesInThisFileAndPackage);

        // Register in local lookup for potential parent-child bindings (not added as a top-level CU)
        localCuByFqName.put(moduleCu.fqName(), moduleCu);
    }

    @Override
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, TSNode classNode, String signature, SourceContent sourceContent) {
        // Aggregate all @type.super captures for the same @type.decl across all matches.
        // Previously only the first match was considered, which dropped additional interfaces.
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
                // Accumulate all type.super nodes for this declaration; do not break after first match.
                aggregateSuperNodes.addAll(superCapturesThisMatch);
            }
        }

        // Sort once to preserve source order: superclass first, then interfaces in declaration order
        aggregateSuperNodes.sort(Comparator.comparingInt(TSNode::getStartByte));

        List<String> supers = new ArrayList<>(aggregateSuperNodes.size());
        for (TSNode s : aggregateSuperNodes) {
            String text = sourceContent.substringFrom(s).strip();
            if (!text.isEmpty()) {
                supers.add(text);
            }
        }

        // Deduplicate while preserving order to avoid duplicates like [BaseClass, BaseClass, ...]
        LinkedHashSet<String> unique = new LinkedHashSet<>(supers);
        return List.copyOf(unique);
    }
}
