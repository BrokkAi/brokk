package ai.brokk.analyzer;

import static ai.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import ai.brokk.IProject;
import ai.brokk.analyzer.java.JavaTypeAnalyzer;
import java.nio.file.Files;
import java.util.*;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

public class JavaAnalyzer extends TreeSitterAnalyzer {

    private static final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");
    private static final String LAMBDA_EXPRESSION = "lambda_expression";

    public JavaAnalyzer(IProject project) {
        super(project, Languages.JAVA);
    }

    private JavaAnalyzer(IProject project, AnalyzerState state) {
        super(project, Languages.JAVA, state);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new JavaAnalyzer(getProject(), state);
    }

    @Override
    public Optional<String> extractClassName(String reference) {
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
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        final String shortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);
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
    public Optional<CodeUnit> getDefinition(String fqName) {
        // Normalize generics/anon/location suffixes for both class and method lookups
        var normalized = normalizeFullName(fqName);
        return super.getDefinition(normalized);
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
            int col = 0;
            try {
                // Some bindings may not expose column; defensively handle absence
                col = decl.getStartPoint().getColumn();
            } catch (Throwable ignored) {
                // default to 0
            }
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
     * Java-specific implementation to compute direct supertypes by traversing the cached Tree-sitter AST. Preserves
     * Java order: superclass (if any) first, then implemented interfaces in source order. Attempts to resolve names
     * using file imports, then package-local names, then global search. First tries a focused in-code Tree-sitter query
     * (string literal) for fast extraction; falls back to manual field traversal if needed.
     */
    @Override
    public List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Obtain the cached parse tree for this file
        TSTree tree = treeOf(cu.source());
        if (tree == null) {
            return List.of();
        }
        // Load source text for slice operations
        final String src;
        try {
            src = Files.readString(cu.source().absPath());
        } catch (Exception e) {
            return List.of();
        }

        return JavaTypeAnalyzer.compute(cu, tree, src, getTSLanguage(), this::textSlice, this::searchDefinitions);

        // Parse import statements from this file for resolution assistance.
        //        List<String> importLines = importStatementsOf(cu.source());
        //        Map<String, String> explicitImports = new LinkedHashMap<>(); // simpleName -> FQCN
        //        List<String> wildcardPackages = new ArrayList<>(); // package prefixes from .* imports
        //        for (String line : importLines) {
        //            if (line.isBlank()) continue;
        //            String t = line.strip();
        //            if (!t.startsWith("import ")) continue;
        //            if (t.startsWith("import static ")) continue; // ignore static imports
        //
        //            if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
        //            t = t.substring("import ".length()).trim();
        //
        //            if (t.endsWith(".*")) {
        //                String pkg = t.substring(0, t.length() - 2).trim();
        //                if (!pkg.isEmpty()) wildcardPackages.add(pkg);
        //                continue;
        //            }
        //
        //            if (!t.isEmpty()) {
        //                String fq = t;
        //                int lastDot = fq.lastIndexOf('.');
        //                if (lastDot > 0 && lastDot < fq.length() - 1) {
        //                    String simple = fq.substring(lastDot + 1);
        //                    explicitImports.putIfAbsent(simple, fq);
        //                }
        //            }
        //        }
        //
        //        // Resolve collected raw names to known CodeUnits using imports/package/search
        //        List<CodeUnit> resolved = new ArrayList<>(rawNames.size());
        //        for (String raw : rawNames) {
        //            if (raw.isEmpty()) continue;
        //
        //            String normalized = normalizeFullName(raw).trim();
        //            String simpleName =
        //                    normalized.contains(".") ? normalized.substring(normalized.lastIndexOf('.') + 1) :
        // normalized;
        //
        //            LinkedHashSet<String> candidates = new LinkedHashSet<>();
        //
        //            if (normalized.contains(".")) {
        //                candidates.add(normalized);
        //            }
        //            String explicit = explicitImports.get(simpleName);
        //            if (explicit != null && !explicit.isBlank()) {
        //                candidates.add(explicit);
        //            }
        //            for (String wp : wildcardPackages) {
        //                candidates.add(wp + "." + simpleName);
        //            }
        //            if (!cu.packageName().isEmpty()) {
        //                candidates.add(cu.packageName() + "." + simpleName);
        //            }
        //            candidates.add(simpleName);
        //
        //            CodeUnit match = null;
        //            for (String fq : candidates) {
        //                var def = getDefinition(fq);
        //                if (def.isPresent() && def.get().isClass()) {
        //                    match = def.get();
        //                    break;
        //                }
        //            }
        //
        //            if (match == null) {
        //                String pattern = ".*\\." + Pattern.quote(simpleName) + "$";
        //                var options = searchDefinitions(pattern).stream()
        //                        .filter(CodeUnit::isClass)
        //                        .toList();
        //
        //                if (!options.isEmpty()) {
        //                    Optional<CodeUnit> samePkg = options.stream()
        //                            .filter(o -> o.packageName().equals(cu.packageName()))
        //                            .findFirst();
        //                    match = samePkg.orElse(options.getFirst());
        //                }
        //            }
        //
        //            if (match != null) {
        //                resolved.add(match);
        //            }
        //        }
        //
        //        return List.copyOf(resolved);
    }
}
