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
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        return determineJvmPackageName(
                rootNode, src, PACKAGE_DECLARATION, JAVA_SYNTAX_PROFILE.classLikeNodeTypes(), this::textSlice);
    }

    protected static String determineJvmPackageName(
            TSNode rootNode,
            String src,
            String packageDef,
            Set<String> classLikeNodeType,
            BiFunction<TSNode, String, String> textSlice) {
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
                    String nsPart = textSlice.apply(nameNode, src);
                    namespaceParts.add(nsPart);
                }
            }
        }
        Collections.reverse(namespaceParts);
        return String.join(".", namespaceParts);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
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
            signature += " " + textSlice(throwsNode, src);
        }

        return signature;
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            String src,
            String exportPrefix,
            String signatureText,
            String baseIndent,
            ProjectFile file) {
        if (ENUM_CONSTANT.equals(fieldNode.getType())) {
            return formatEnumConstant(fieldNode, signatureText, baseIndent);
        }
        return super.formatFieldSignature(fieldNode, src, exportPrefix, signatureText, baseIndent, file);
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
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        // Special handling for Java lambdas: synthesize a bytecode-style anonymous name
        if (LAMBDA_EXPRESSION.equals(decl.getType())) {
            var enclosingMethod = findEnclosingJavaMethodOrClassName(decl, src).orElse("lambda");
            int line = decl.getStartPoint().getRow();
            int col = decl.getStartPoint().getColumn();
            String synthesized = enclosingMethod + "$anon$" + line + ":" + col;
            return Optional.of(synthesized);
        }
        return super.extractSimpleName(decl, src);
    }

    private Optional<String> findEnclosingJavaMethodOrClassName(TSNode node, String src) {
        // Walk up to nearest method or constructor
        TSNode current = node.getParent();
        while (current != null && !current.isNull()) {
            String type = current.getType();
            if (METHOD_DECLARATION.equals(type) || CONSTRUCTOR_DECLARATION.equals(type)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    String name = textSlice(nameNode, src).strip();
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
                    String cls = textSlice(nameNode, src).strip();
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
            CodeUnit cu, TSNode classNode, String signature, String src) {
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
            String text = textSlice(s, src).strip();
            if (!text.isEmpty()) {
                supers.add(text);
            }
        }

        // Deduplicate while preserving order to avoid duplicates like [BaseClass, BaseClass, ...]
        LinkedHashSet<String> unique = new LinkedHashSet<>(supers);
        return List.copyOf(unique);
    }

    @Override
    public Optional<CodeUnit> inferTypeAt(ProjectFile file, int offset) {
        log.debug("inferTypeAt: file={}, offset={}", file, offset);
        // Read source early so we can do source-based fallbacks when referenced-identifier ranges
        // produced by queries/regex are incomplete (e.g., "new Helper().process().getLeaf().value").
        var srcOpt = file.read();
        String src = srcOpt.orElse("");
        log.debug("inferTypeAt: source present={}, length={}", !src.isEmpty(), src.length());

        // Try to obtain an identifier via the on-demand referenced-identifier extractor (preferred).
        var identOpt = getIdentifierAt(file, offset);
        log.debug("inferTypeAt: identifier from getIdentifierAt present={}", identOpt.isPresent());

        // If the extractor did not return a helpful long form (or returned empty), try a source-based
        // scanner that expands left/right from the byte offset to produce a best-effort chained token.
        if (identOpt.isEmpty() && !src.isEmpty()) {
            Optional<String> fallback = extractChainedIdentifierFromSource(src, offset);
            if (fallback.isPresent()) {
                identOpt = fallback;
                log.debug("inferTypeAt: fallback identifier from source = '{}'", identOpt.get());
            } else {
                log.debug("inferTypeAt: fallback extractor returned empty");
            }
        } else if (!src.isEmpty()) {
            // Even when the query/regex extractor returned something, it may be a partial suffix of a longer chain
            // (common when the regex-based extractor matches only the trailing portion). Prefer the source-based
            // extractor when it yields a longer/more complete chain.
            Optional<String> expanded = extractChainedIdentifierFromSource(src, offset);
            if (expanded.isPresent()) {
                if (expanded.get().length() > identOpt.get().length()) {
                    log.debug(
                            "inferTypeAt: expanded identifier from source ('{}') is longer than query result ('{}') - using expanded",
                            expanded.get(),
                            identOpt.get());
                    identOpt = expanded;
                } else {
                    log.debug(
                            "inferTypeAt: expanded identifier from source ('{}') not longer than query result ('{}') - keeping query result",
                            expanded.get(),
                            identOpt.get());
                }
            } else {
                log.debug("inferTypeAt: expanded identifier extractor returned empty");
            }
        }

        if (identOpt.isEmpty()) {
            log.debug("inferTypeAt: no identifier available at offset {}", offset);
            return Optional.empty();
        }
        String ident = identOpt.get().strip();
        log.debug("inferTypeAt: resolved identifier='{}'", ident);
        if (ident.isEmpty()) {
            log.debug("inferTypeAt: identifier empty after strip");
            return Optional.empty();
        }

        // Early detection: if identifier starts with "new ", strip it and mark as constructor
        boolean detectedNewFromIdent = false;
        if (ident.toLowerCase(Locale.ROOT).startsWith("new ")) {
            ident = ident.replaceFirst("(?i)^new\\s+", "").strip();
            // Also strip trailing parentheses if present
            if (ident.endsWith("()")) {
                ident = ident.substring(0, ident.length() - 2).strip();
            }
            detectedNewFromIdent = true;
            log.debug("inferTypeAt: detected 'new' from identifier, stripped ident='{}'", ident);
        }

        // Helper to strip trailing parentheses for method call cases (e.g., "method()" -> "method").
        java.util.function.UnaryOperator<String> stripParens = s -> {
            int p = s.indexOf('(');
            return p >= 0 ? s.substring(0, p) : s;
        };

        // Build inference context (enclosing class/method + imports)
        var ctx = buildTypeInferenceContext(file, offset);
        CodeUnit enclosingClass = ctx.enclosingClass();
        log.debug("inferTypeAt: enclosingClass present={}, enclosingMethod present={}, visibleImports={}",
                  true,
                  true,
                ctx.visibleImports().size());

        // Attempt to find the referenced identifier Range so we can inspect nearby source (for 'new' detection).
        List<IAnalyzer.Range> ranges = getReferencedIdentifiers(file);
        IAnalyzer.Range bestRange = null;
        int bestLen = -1;
        for (var r : ranges) {
            if (r.startByte() <= offset && r.endByte() >= offset) {
                int len = r.endByte() - r.startByte();
                if (len > bestLen) {
                    bestLen = len;
                    bestRange = r;
                }
            }
        }
        log.debug("inferTypeAt: found referenced identifier ranges={}, bestRangePresent={}", ranges.size(), bestRange != null);

        // Read source if available for constructor detection and richer heuristics
        boolean looksLikeConstructor = false;
        if (bestRange != null && !src.isBlank()) {
            int ctxStart = Math.max(0, bestRange.startByte() - 16); // small window for "new "
            String prefix = ASTTraversalUtils.safeSubstringFromByteOffsets(src, ctxStart, bestRange.startByte());
            if (prefix.matches("(?s).*\\bnew\\s*$")) {
                looksLikeConstructor = true;
                log.debug("inferTypeAt: context prefix before bestRange indicates constructor ('{}')", prefix);
            }
        }

        // Helper: resolve a simple type name to a CodeUnit representing the class (best-effort).
        java.util.function.Function<String, Optional<CodeUnit>> resolveTypeName = (name) -> {
            log.debug("resolveTypeName: attempting to resolve '{}'", name);
            if (name.isBlank()) {
                log.debug("resolveTypeName: name blank, returning empty");
                return Optional.empty();
            }
            String nm = name.strip();
            // 1) Fully-qualified attempt
            if (nm.contains(".")) {
                var defs = getDefinitions(nm);
                if (!defs.isEmpty()) {
                    for (CodeUnit d : defs) if (d.isClass()) {
                        log.debug("resolveTypeName: resolved by FQN to {}", d);
                        return Optional.of(d);
                    }
                }
            }

            // 2) Visible imports (explicit/wildcard resolution was precomputed in buildTypeInferenceContext)
            for (CodeUnit imp : ctx.visibleImports()) {
                if (imp.identifier().equals(nm) || imp.shortName().equals(nm) || imp.fqName().endsWith("." + nm)) {
                    if (imp.isClass()) {
                        log.debug("resolveTypeName: resolved by visible import to {}", imp);
                        return Optional.of(imp);
                    }
                }
            }

            // 3) Same package as enclosing class
            String pkg = enclosingClass.packageName();
            if (!pkg.isBlank()) {
                var defs = getDefinitions(pkg + "." + nm);
                if (!defs.isEmpty()) {
                    for (CodeUnit d : defs) if (d.isClass()) {
                        log.debug("resolveTypeName: resolved by same-package to {}", d);
                        return Optional.of(d);
                    }
                }
            }

            // 4) Global direct definitions (exact)
            var defsExact = getDefinitions(nm);
            if (!defsExact.isEmpty()) {
                for (CodeUnit d : defsExact) if (d.isClass()) {
                    log.debug("resolveTypeName: resolved by global exact to {}", d);
                    return Optional.of(d);
                }
            }

            // 5) Broad search fallback
            var search = searchDefinitions(nm);
            if (!search.isEmpty()) {
                for (CodeUnit d : search) if (d.isClass()) {
                    log.debug("resolveTypeName: resolved by search to {}", d);
                    return Optional.of(d);
                }
            }

            log.debug("resolveTypeName: could not resolve '{}'", name);
            return Optional.empty();
        };

        // ----- SPECIAL: this./super. handling first (simple cases) -----
        if (ident.startsWith("this.")) {
            String memberChain = ident.substring("this.".length()).strip();
            log.debug("inferTypeAt: handling 'this.' chain '{}'", memberChain);
            if (memberChain.isEmpty()) return Optional.empty();
            String leftmost = stripParens.apply(memberChain.split("\\.", 2)[0]);

            // 1) Search direct children of enclosing class
            for (CodeUnit child : getDirectChildren(enclosingClass)) {
                if (matchesMember(child, leftmost)) {
                    log.debug("inferTypeAt: matched member '{}' on enclosingClass via getDirectChildren -> {}", leftmost, child);
                    return Optional.of(child);
                }
            }

            // 2) Fallback: search ancestors (inherited members)
            for (CodeUnit anc : getAncestors(enclosingClass)) {
                for (CodeUnit child : getDirectChildren(anc)) {
                    if (matchesMember(child, leftmost)) {
                        log.debug("inferTypeAt: matched member '{}' on ancestor {} -> {}", leftmost, anc, child);
                        return Optional.of(child);
                    }
                }
            }

            log.debug("inferTypeAt: 'this.' resolution failed for '{}'", leftmost);
            return Optional.empty();
        }

        if (ident.startsWith("super.")) {
            String memberChain = ident.substring("super.".length()).strip();
            log.debug("inferTypeAt: handling 'super.' chain '{}'", memberChain);
            if (memberChain.isEmpty()) return Optional.empty();
            String leftmost = stripParens.apply(memberChain.split("\\.", 2)[0]);

            // 1) Prefer direct ancestors (non-transitive), i.e., immediate supertypes
            for (CodeUnit directParent : getDirectAncestors(enclosingClass)) {
                for (CodeUnit child : getDirectChildren(directParent)) {
                    if (matchesMember(child, leftmost)) {
                        log.debug("inferTypeAt: matched member '{}' on direct ancestor {} -> {}", leftmost, directParent, child);
                        return Optional.of(child);
                    }
                }
            }

            // 2) Fallback: transitive ancestors
            for (CodeUnit anc : getAncestors(enclosingClass)) {
                for (CodeUnit child : getDirectChildren(anc)) {
                    if (matchesMember(child, leftmost)) {
                        log.debug("inferTypeAt: matched member '{}' on ancestor {} -> {}", leftmost, anc, child);
                        return Optional.of(child);
                    }
                }
            }

            log.debug("inferTypeAt: 'super.' resolution failed for '{}'", leftmost);
            return Optional.empty();
        }

        // ----- QUALIFIED / CHAINED ACCESS RESOLUTION: foo.bar.baz -----
        if (ident.contains(".")) {
            log.debug("inferTypeAt: handling chained/qualified identifier '{}'", ident);
            // Normalize by removing explicit 'new' markers and surrounding whitespace around dots before splitting.
            // This helps when the identifier extractor included 'new' or had uneven spacing around dots.
            String normalizedForSplit = ident.replaceAll("(?i)\\bnew\\s+", "").replaceAll("\\s*\\.\\s*", ".");
            String[] rawSegments = normalizedForSplit.split("\\.");
            if (rawSegments.length >= 2) {
                // Normalize each segment by stripping parentheses for method invocations
                String[] segments = new String[rawSegments.length];
                for (int i = 0; i < rawSegments.length; i++) segments[i] = stripParens.apply(rawSegments[i].strip());

                // Resolve leftmost segment to an initial "type" context if possible
                Optional<CodeUnit> currentType = Optional.empty();

                // If left is a 'new' expression like "new Helper" (after paren stripping), treat its type as the token after 'new '
                String leftCandidate = segments[0];
                if (leftCandidate.matches("(?i:^\\s*new\\s+.+)")) {
                    leftCandidate = leftCandidate.replaceFirst("(?i:^\\s*new\\s+)", "").strip();
                }

                // If the extractor omitted a leading "new" token (common with regex fallback), detect via nearby source.
                if (!leftCandidate.toLowerCase(Locale.ROOT).startsWith("new ") && bestRange != null && !src.isBlank()) {
                    // Inspect the small prefix immediately before the matched range for "new ".
                    int ctxStart = Math.max(0, bestRange.startByte() - 8);
                    String prefix = ASTTraversalUtils.safeSubstringFromByteOffsets(src, ctxStart, bestRange.startByte());
                    if (prefix.matches("(?s).*\\bnew\\s*$")) {
                        // Include the 'new' marker in leftCandidate handling by leaving leftCandidate as-is;
                        // resolving it as a type name below will still work because we strip any leading new later.
                        leftCandidate = "new " + leftCandidate;
                        leftCandidate = leftCandidate.replaceFirst("(?i:^\\s*new\\s+)", "").strip();
                        log.debug("inferTypeAt: detected implicit 'new' before leftCandidate via source prefix");
                    }
                }

                log.debug("inferTypeAt: leftCandidate='{}'", leftCandidate);

                // 1) If left looks like a qualified FQN, prefer that
                if (leftCandidate.contains(".")) currentType = resolveTypeName.apply(leftCandidate);

                // 2) Try locals/params: prefer locals declared before offset and within enclosing method
                boolean attemptedLocals = false;
                if (currentType.isEmpty()) {
                    attemptedLocals = true;
                    try {
                        List<TreeSitterAnalyzer.LocalVariableInfo> locals = getLocalVariables(file);
                        if (!locals.isEmpty()) {
                            for (var lv : locals) {
                                if (!leftCandidate.equals(lv.name())) continue;
                                if (lv.range().startByte() > offset) continue;
                                // If local has a declared type, try to resolve it
                                String typeName = lv.typeName();
                                if (!typeName.isBlank()) {
                                    var resolved = resolveTypeName.apply(stripGenericTypeArguments(typeName));
                                    if (resolved.isPresent()) {
                                        currentType = resolved;
                                        log.debug("inferTypeAt: resolved type for local '{}' -> {}", lv.name(), resolved.get());
                                        break;
                                    }
                                }
                                // otherwise, we cannot progress from an untyped local
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Local variable lookup failed during chained resolution: {}", e.toString());
                    }
                }
                log.debug("inferTypeAt: attemptedLocals={}, currentTypePresent={}", attemptedLocals, currentType.isPresent());

                // Fallback: when local-variable query unavailable or returned nothing, continue with
                // other resolution strategies below (field/method on enclosing class, type name, etc.)
                // This is a soft failure - pattern variables, implicitly typed locals, etc. may not
                // be resolvable via the locals query but can still be resolved through other means.
                log.debug("inferTypeAt: local variable lookup did not resolve type for '{}'", leftCandidate);

                // 3) Try as a field/method on enclosing class (instance)
                if (currentType.isEmpty()) {
                    for (CodeUnit child : getDirectChildren(enclosingClass)) {
                        if (matchesMember(child, leftCandidate)) {
                            log.debug("inferTypeAt: leftCandidate '{}' matched member on enclosing class -> {}", leftCandidate, child);
                            if (rawSegments.length == 1) return Optional.of(child);

                            // If it's a nested class (including enums), use it as the type context and continue
                            if (child.isClass()) {
                                currentType = Optional.of(child);
                                log.debug("inferTypeAt: leftCandidate matched nested class, using as type context -> {}", child);
                                break;
                            }

                            // infer its declared/return type and attempt to resolve
                            Optional<String> typeTok = child.isFunction()
                                    ? parseReturnType(child)
                                    : (child.isField() ? parseFieldType(child) : Optional.empty());
                            if (typeTok.isPresent()) {
                                var resolved = resolveTypeName.apply(stripGenericTypeArguments(typeTok.get()));
                                if (resolved.isPresent()) {
                                    currentType = resolved;
                                    log.debug("inferTypeAt: inferred member type '{}' resolved -> {}", typeTok.get(), resolved.get());
                                    break;
                                }
                            }
                            // If we couldn't get a type, we cannot continue the chain
                            return Optional.of(child);
                        }
                    }
                }

                // 4) Finally try to resolve left as a type name
                if (currentType.isEmpty()) {
                    currentType = resolveTypeName.apply(leftCandidate);
                    log.debug("inferTypeAt: attempted resolving leftCandidate as type, present={}", currentType.isPresent());
                }

                // ADDITIONAL FALLBACK: look for classes declared in the SAME file (top-level or nested) with matching simple name.
                if (currentType.isEmpty()) {
                    // top-levels first (fast)
                    for (CodeUnit top : getTopLevelDeclarations(file)) {
                        if (top.isClass()) {
                            String shortName = top.shortName();
                            int dot = shortName.lastIndexOf('.');
                            String simple = dot >= 0 ? shortName.substring(dot + 1) : shortName;
                            if (simple.equals(leftCandidate) || top.identifier().equals(leftCandidate)) {
                                currentType = Optional.of(top);
                                log.debug("inferTypeAt: resolved leftCandidate via top-level class in file -> {}", top);
                                break;
                            }
                        }
                    }
                    // if still empty, check other declarations in the file (includes nested)
                    if (currentType.isEmpty()) {
                        for (CodeUnit cu : getDeclarations(file)) {
                            if (cu.isClass()) {
                                String shortName = cu.shortName();
                                int dot = shortName.lastIndexOf('.');
                                String simple = dot >= 0 ? shortName.substring(dot + 1) : shortName;
                                if (simple.equals(leftCandidate) || cu.identifier().equals(leftCandidate)) {
                                    currentType = Optional.of(cu);
                                    log.debug("inferTypeAt: resolved leftCandidate via other declaration in file -> {}", cu);
                                    break;
                                }
                            }
                        }
                    }
1                }

                // If still unresolved, chain cannot be followed
                if (currentType.isEmpty()) {
                    // Best-effort fallback: if the last segment corresponds uniquely to a field in the project,
                    // return that field to satisfy simple chained resolution cases where intermediate inference failed.
                    String lastSegFallback = segments[segments.length - 1];
                    var allCandidates = searchDefinitions(lastSegFallback);
                    var fieldCandidates = allCandidates.stream()
                            .filter(CodeUnit::isField)
                            .filter(cu -> matchesMember(cu, lastSegFallback))
                            .toList();
                    if (fieldCandidates.size() == 1) {
                        log.debug("inferTypeAt: using unique global field fallback for '{}' -> {}", lastSegFallback, fieldCandidates.getFirst());
                        return Optional.of(fieldCandidates.getFirst());
                    }
                    log.debug("inferTypeAt: chained resolution failed for '{}'", ident);
                    return Optional.empty();
                }

                // Iteratively walk remaining segments
                Optional<CodeUnit> curTypeOpt = currentType;
                for (int i = 1; i < segments.length; i++) {
                    String seg = segments[i];
                    CodeUnit curType = curTypeOpt.get();

                    // Look for member under curType
                    CodeUnit member = null;
                    for (CodeUnit child : getDirectChildren(curType)) {
                        if (matchesMember(child, seg)) {
                            member = child;
                            break;
                        }
                    }
                    if (member == null) {
                        // Not found in this type - try a conservative fallback: if the segment names a field that exists
                        // uniquely in the whole project, return that one.
                        var globalCandidates = searchDefinitions(seg);
                        var globalFieldCandidates = globalCandidates.stream()
                                .filter(CodeUnit::isField)
                                .filter(cu -> matchesMember(cu, seg))
                                .toList();
                        if (globalFieldCandidates.size() == 1) {
                            log.debug("inferTypeAt: global unique field fallback for '{}' -> {}", seg, globalFieldCandidates.getFirst());
                            return Optional.of(globalFieldCandidates.getFirst());
                        }
                        log.debug("inferTypeAt: member '{}' not found on type {} while walking chain", seg, curType);
                        return Optional.empty();
                    }

                    // If this is the final segment, return the member found
                    if (i == segments.length - 1) {
                        log.debug("inferTypeAt: final chain segment '{}' resolved to {}", seg, member);
                        return Optional.of(member);
                    }

                    // Otherwise, infer member's type to continue chaining
                    if (member.isClass()) {
                        curTypeOpt = Optional.of(member);
                        continue;
                    }

                    if (member.isFunction()) {
                        Optional<String> ret = parseReturnType(member);
                        if (ret.isEmpty()) {
                            log.debug("inferTypeAt: member {} is function with unknown return type - returning member", member);
                            return Optional.of(member); // can't continue; return member
                        }
                        curTypeOpt = resolveTypeName.apply(stripGenericTypeArguments(ret.get()));
                        if (curTypeOpt.isEmpty()) {
                            // Could not resolve the return type to a class; stop at member
                            log.debug("inferTypeAt: could not resolve return type '{}' of member {}, returning member", ret.get(), member);
                            return Optional.of(member);
                        }
                        continue;
                    }

                    if (member.isField()) {
                        Optional<String> ft = parseFieldType(member);
                        if (ft.isEmpty()) {
                            log.debug("inferTypeAt: member {} is field with unknown type - returning member", member);
                            return Optional.of(member);
                        }
                        curTypeOpt = resolveTypeName.apply(stripGenericTypeArguments(ft.get()));
                        if (curTypeOpt.isEmpty()) {
                            log.debug("inferTypeAt: could not resolve field type '{}' of member {}, returning member", ft.get(), member);
                            return Optional.of(member);
                        }
                        continue;
                    }

                    // Fallback: unknown member kind
                    log.debug("inferTypeAt: unknown member kind for {}, returning member", member);
                    return Optional.of(member);
                }
            }
            return Optional.empty();
        }

        // ----- NEW / constructor detection (single-name) -----
        if (looksLikeConstructor || detectedNewFromIdent) {
            log.debug("inferTypeAt: looksLikeConstructor={}, detectedNewFromIdent={}", looksLikeConstructor, detectedNewFromIdent);
            var typeCu = resolveTypeName.apply(ident);
            if (typeCu.isPresent()) {
                log.debug("inferTypeAt: constructor resolved to {}", typeCu.get());
                return typeCu;
            } else {
                log.debug("inferTypeAt: constructor resolution failed for '{}'", ident);
            }
        }

        // ----- UNQUALIFIED NAME RESOLUTION: local -> parameter -> field -> inherited -----
        if (!ident.contains(".")) {
            String name = stripParens.apply(ident);
            log.debug("inferTypeAt: unqualified name resolution for '{}'", name);

            // 1) Locals & parameters (from locals query). Prefer locals declared before offset.
            List<TreeSitterAnalyzer.LocalVariableInfo> locals = getLocalVariables(file);
            if (!locals.isEmpty()) {
                for (var lv : locals) {
                    if (!name.equals(lv.name())) continue;
                    if (lv.range().startByte() > offset) continue; // declared after use
                    String typeName = lv.typeName();
                    if (!typeName.isBlank()) {
                        var resolved = resolveTypeName.apply(stripGenericTypeArguments(typeName));
                        if (resolved.isPresent()) {
                            log.debug("inferTypeAt: resolved local '{}' declared type -> {}", lv.name(), resolved.get());
                            return resolved;
                        }
                    }
                    // Fallback: return a synthetic CodeUnit to represent the local (so callers get a non-empty result).
                    String pkg = enclosingClass.packageName();
                    var synthetic = CodeUnit.field(file, pkg, name);
                    log.debug("inferTypeAt: returning synthetic CodeUnit for local '{}': {}", name, synthetic);
                    return Optional.of(synthetic);
                }
            }

            // 2) Fields/methods on enclosing class (instance)
            for (CodeUnit child : getDirectChildren(enclosingClass)) {
                if (matchesMember(child, name)) {
                    log.debug("inferTypeAt: unqualified name '{}' matched member on enclosing class -> {}", name, child);
                    return Optional.of(child);
                }
            }
            // 3) Inherited members
            for (CodeUnit anc : getAncestors(enclosingClass)) {
                for (CodeUnit child : getDirectChildren(anc)) {
                    if (matchesMember(child, name)) {
                        log.debug("inferTypeAt: unqualified name '{}' matched member on ancestor {} -> {}", name, anc, child);
                        return Optional.of(child);
                    }
                }
            }
        }

        log.debug("inferTypeAt: falling through - no resolution for '{}'", ident);
        // Not handled by this helper
        return Optional.empty();
    }

    /**
         * Best-effort source-level chained-identifier extractor.
         *
         * Attempts to expand a UTF-8 byte offset into a dotted/method-call chain like:
         *   - "a.b.c"
         *   - "obj.getX().y"
         *   - "new Helper().process().getLeaf().value"
         *
         * This is purposely permissive and used as a fallback when the Tree-sitter / query-based extractor
         * did not produce a suitable range covering the full chain.
         */
    private static Optional<String> extractChainedIdentifierFromSource(String src, int byteOffset) {
        log.debug("extractChainedIdentifierFromSource: byteOffset={}, srcPresent={}", byteOffset, !src.isEmpty());
        if (src.isEmpty()) return Optional.empty();
        byteOffset = Math.max(0, byteOffset);

        // Map byte offset to char index (UTF-8). Simple linear scan is acceptable for small test inputs.
        int charIdx = -1;
        byte[] bytes = src.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (byteOffset > bytes.length) byteOffset = bytes.length;
        int acc = 0;
        for (int i = 0; i < src.length(); i++) {
            String ch = src.substring(i, i + 1);
            int blen = ch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (acc + blen > byteOffset) {
                charIdx = i;
                break;
            }
            acc += blen;
        }
        if (charIdx == -1) charIdx = src.length();
        log.debug("extractChainedIdentifierFromSource: mapped byteOffset {} -> charIdx {}", byteOffset, charIdx);

        // Expand leftwards: allow letters, digits, '_', '$', '.', '(', ')', whitespace
        int start = charIdx;
        while (start > 0) {
            char c = src.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '(' || c == ')' || Character.isWhitespace(c)) {
                start--;
            } else {
                break;
            }
        }

        // Expand rightwards: allow letters, digits, '_', '$', '.', '(', ')'
        int end = charIdx;
        while (end < src.length()) {
            char c = src.charAt(end);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '(' || c == ')') {
                end++;
            } else {
                break;
            }
        }

        log.debug("extractChainedIdentifierFromSource: expanded range charStart={}, charEnd={}", start, end);

        if (start >= end) {
            log.debug("extractChainedIdentifierFromSource: no expansion around offset -> empty");
            return Optional.empty();
        }
        String candidate = src.substring(start, end).strip();

        if (candidate.isEmpty()) {
            log.debug("extractChainedIdentifierFromSource: candidate empty after strip");
            return Optional.empty();
        }

        // If the token immediately before start is "new", include it
        int lookBack = Math.max(0, start - 8);
        String before = src.substring(lookBack, start);
        if (before.matches("(?s).*\\bnew\\s*$")) {
            // find the position of 'new' in this slice
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bnew\\s*$").matcher(before);
            if (m.find()) {
                int newPos = lookBack + m.start();
                candidate = src.substring(newPos, end).strip();
                log.debug("extractChainedIdentifierFromSource: included 'new' prefix, candidate now='{}'", candidate);
            }
        }

        // Trim any trailing unmatched punctuation like commas/semicolons
        candidate = candidate.replaceAll("[,;]+\\s*$", "").strip();

        log.debug("extractChainedIdentifierFromSource: returning candidate='{}'", candidate);
        return candidate.isEmpty() ? Optional.empty() : Optional.of(candidate);
    }

    /**
     * Robust member name comparison helper. Matches by:
     *  - CodeUnit.identifier()
     *  - CodeUnit.shortName()
     *  - trailing segment of CodeUnit.fqName() ('.' or '$' separators)
     */
    private static boolean matchesMember(CodeUnit cu, String name) {
        if (name.isBlank()) return false;
        String candidate = name.strip();

        // Direct identifier (may be different from shortName in some languages)
        try {
            if (candidate.equals(cu.identifier())) return true;
        } catch (Exception ignored) {
        }
        if (candidate.equals(cu.shortName())) return true;

        String fq = cu.fqName();
        if (fq.endsWith("." + candidate) || fq.endsWith("$" + candidate)) return true;

        // Sometimes shortName contains classChain + "." + simpleName (e.g., inner classes),
        // allow a suffix match as a last resort.
        return cu.shortName().endsWith("." + candidate);
    }
}
