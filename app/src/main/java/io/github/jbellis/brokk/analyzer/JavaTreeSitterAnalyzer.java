package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import io.github.jbellis.brokk.IProject;
import java.nio.file.Files;
import java.util.*;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

public class JavaTreeSitterAnalyzer extends TreeSitterAnalyzer {

    private static final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");
    private static final String LAMBDA_EXPRESSION = "lambda_expression";

    public JavaTreeSitterAnalyzer(IProject project) {
        super(project, Languages.JAVA, project.getExcludedDirectories());
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
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "interface.definition", SkeletonType.CLASS_LIKE,
                    "enum.definition", SkeletonType.CLASS_LIKE,
                    "record.definition", SkeletonType.CLASS_LIKE,
                    "annotation.definition", SkeletonType.CLASS_LIKE, // for @interface
                    "method.definition", SkeletonType.FUNCTION_LIKE,
                    "constructor.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE,
                    "lambda.definition", SkeletonType.FUNCTION_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JAVA_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        final String fqName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;

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

        return new CodeUnit(file, type, packageName, fqName);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // Java packages are either present or not, and will be the immediate child of the `program`
        // if they are present at all
        final List<String> namespaceParts = new ArrayList<>();

        // The package may not be the first thing in the file, so we should iterate until either we find it, or we are
        // at a type node.
        TSNode maybeDeclaration = null;
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            final var child = rootNode.getChild(i);
            if (PACKAGE_DECLARATION.equals(child.getType())) {
                maybeDeclaration = child;
                break;
            } else if (JAVA_SYNTAX_PROFILE.classLikeNodeTypes().contains(child.getType())) {
                break;
            }
        }

        if (maybeDeclaration != null && PACKAGE_DECLARATION.equals(maybeDeclaration.getType())) {
            for (int i = 0; i < maybeDeclaration.getNamedChildCount(); i++) {
                final TSNode nameNode = maybeDeclaration.getNamedChild(i);
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice(nameNode, src);
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
    private String stripGenericTypeArguments(String name) {
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
     * using file imports, then package-local names, then global search.
     */
    @Override
    protected List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Obtain the cached parse tree for this file
        TSTree tree = getCachedTree(cu.source());
        if (tree == null) {
            return List.of();
        }
        TSNode root = tree.getRootNode();

        // Load source text for slice operations
        final String src;
        try {
            src = Files.readString(cu.source().absPath());
        } catch (Exception e) {
            return List.of();
        }

        // Find the class-like node that matches this CodeUnit's class chain (Outer.Inner...)
        final String targetChain = cu.fqName();
        TSNode targetNode = null;

        ArrayDeque<TSNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            TSNode n = stack.pop();

            if (isClassLike(n)) {
                List<String> names = new ArrayList<>();
                TSNode cur = n;
                while (cur != null && !cur.isNull()) {
                    if (isClassLike(cur)) {
                        TSNode nameNode = cur.getChildByFieldName("name");
                        if (nameNode != null && !nameNode.isNull()) {
                            String name = textSlice(nameNode, src).strip();
                            if (!name.isEmpty()) {
                                names.add(name);
                            }
                        }
                    }
                    cur = cur.getParent();
                }
                Collections.reverse(names);
                String chain = String.join(".", names);
                if (targetChain.equals(chain)) {
                    targetNode = n;
                    break;
                }
            }

            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                TSNode c = n.getChild(i);
                if (c != null && !c.isNull()) {
                    stack.push(c);
                }
            }
        }

        if (targetNode == null) {
            return List.of();
        }

        // Helper to strip leading keywords and generic args
        Function<String, String> cleanTypeText = (String s) -> {
            String cleaned = s.strip();
            if (cleaned.startsWith("extends "))
                cleaned = cleaned.substring("extends ".length()).strip();
            if (cleaned.startsWith("implements "))
                cleaned = cleaned.substring("implements ".length()).strip();
            cleaned = stripGenericTypeArguments(cleaned);
            return cleaned.strip();
        };

        // Helper to split a list of types on commas, ignoring commas inside generics
        Function<String, List<String>> splitTopLevelCommas = (String s) -> {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '<') {
                    depth++;
                    cur.append(ch);
                } else if (ch == '>') {
                    if (depth > 0) depth--;
                    cur.append(ch);
                } else if (ch == ',' && depth == 0) {
                    String p = cur.toString().trim();
                    if (!p.isEmpty()) parts.add(p);
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            String last = cur.toString().trim();
            if (!last.isEmpty()) parts.add(last);
            return parts;
        };

        // Extract super class and interfaces in Java order
        List<String> rawNames = new ArrayList<>();

        TSNode superclassNode = targetNode.getChildByFieldName("superclass");
        if (superclassNode != null && !superclassNode.isNull()) {
            String t = cleanTypeText.apply(textSlice(superclassNode, src));
            if (!t.isEmpty()) rawNames.add(t);
        }

        TSNode interfacesNode = targetNode.getChildByFieldName("interfaces");
        if (interfacesNode == null || interfacesNode.isNull()) {
            interfacesNode = targetNode.getChildByFieldName("super_interfaces");
        }
        if (interfacesNode == null || interfacesNode.isNull()) {
            interfacesNode = targetNode.getChildByFieldName("extends_interfaces");
        }

        if (interfacesNode != null && !interfacesNode.isNull()) {
            String seg = cleanTypeText.apply(textSlice(interfacesNode, src));
            if (!seg.isEmpty()) {
                for (String part : splitTopLevelCommas.apply(seg)) {
                    String p = cleanTypeText.apply(part);
                    if (!p.isEmpty()) rawNames.add(p);
                }
            }
        }

        if (rawNames.isEmpty()) {
            return List.of();
        }

        // Parse import statements from this file for resolution assistance.
        List<String> importLines = importStatementsOf(cu.source());
        // explicit imports: simpleName -> fully.qualified.Name
        Map<String, String> explicitImports = new LinkedHashMap<>();
        // wildcard packages: a.b.c
        List<String> wildcardPackages = new ArrayList<>();
        for (String line : importLines) {
            if (line == null || line.isBlank()) continue;
            String t = line.strip();
            if (!t.startsWith("import ")) continue; // be defensive across grammars
            // ignore static imports for type resolution
            if (t.startsWith("import static ")) continue;

            if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            // strip "import "
            t = t.substring("import ".length()).trim();

            if (t.endsWith(".*")) {
                String pkg = t.substring(0, t.length() - 2).trim();
                if (!pkg.isEmpty()) wildcardPackages.add(pkg);
                continue;
            }

            // Non-wildcard explicit import
            if (!t.isEmpty()) {
                String fq = t;
                int lastDot = fq.lastIndexOf('.');
                if (lastDot > 0 && lastDot < fq.length() - 1) {
                    String simple = fq.substring(lastDot + 1);
                    explicitImports.putIfAbsent(simple, fq);
                }
            }
        }

        // Resolve collected raw names to known CodeUnits using imports/package/search
        List<CodeUnit> resolved = new ArrayList<>(rawNames.size());
        for (String raw : rawNames) {
            if (raw.isEmpty()) continue;

            String normalized = normalizeFullName(raw).trim();
            String simpleName =
                    normalized.contains(".") ? normalized.substring(normalized.lastIndexOf('.') + 1) : normalized;

            // Ordered candidate FQNs to try
            LinkedHashSet<String> candidates = new LinkedHashSet<>();

            // 1) If already fully-qualified, try it directly first
            if (normalized.contains(".")) {
                candidates.add(normalized);
            }

            // 2) Explicit imports that match simpleName
            String explicit = explicitImports.get(simpleName);
            if (explicit != null && !explicit.isBlank()) {
                candidates.add(explicit);
            }

            // 3) Wildcard imports: package.* -> package.simpleName
            for (String wp : wildcardPackages) {
                candidates.add(wp + "." + simpleName);
            }

            // 4) Same package resolution for simple names
            if (!cu.packageName().isEmpty()) {
                candidates.add(cu.packageName() + "." + simpleName);
            }

            // 5) As a last simple candidate, try the simple name itself
            candidates.add(simpleName);

            CodeUnit match = null;
            for (String fq : candidates) {
                var def = getDefinition(fq);
                if (def.isPresent() && def.get().isClass()) {
                    match = def.get();
                    break;
                }
            }

            // 6) Fallback: global search by simple name suffix
            if (match == null) {
                String pattern = ".*\\." + Pattern.quote(simpleName) + "$";
                var options = searchDefinitions(pattern).stream()
                        .filter(CodeUnit::isClass)
                        .toList();

                if (!options.isEmpty()) {
                    // Prefer same-package first, else first match
                    Optional<CodeUnit> samePkg = options.stream()
                            .filter(o -> o.packageName().equals(cu.packageName()))
                            .findFirst();
                    match = samePkg.orElse(options.getFirst());
                }
            }

            if (match != null) {
                resolved.add(match);
            }
        }

        return List.copyOf(resolved);
    }
}
