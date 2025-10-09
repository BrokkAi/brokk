package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IProject;
import java.util.*;
import java.util.Optional;
import java.util.regex.Pattern;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
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
     * Java-specific implementation to compute direct supertypes by parsing the class header signature.
     * Preserves Java's meaningful order: superclass (if any) first, then implemented interfaces in source order.
     * Only returns supertypes that can be resolved to known CodeUnits in the project.
     */
    @Override
    protected List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        var sigs = signaturesOf(cu);
        if (sigs.isEmpty()) return List.of();

        // Use the first signature line (class header)
        String header = sigs.getFirst();
        if (header.isBlank()) return List.of();

        // Work on the first line only; strip trailing class body opener if present
        String line = header.lines().findFirst().orElse(header);
        int brace = line.indexOf('{');
        if (brace >= 0) {
            line = line.substring(0, brace).trim();
        }

        String lower = line.toLowerCase(Locale.ROOT);
        int extendsIdx = lower.indexOf(" extends ");
        int implementsIdx = lower.indexOf(" implements ");

        String extendsPart = null;
        String implementsPart = null;

        if (extendsIdx >= 0) {
            int start = extendsIdx + " extends ".length();
            int end = implementsIdx >= 0 ? implementsIdx : line.length();
            extendsPart = line.substring(start, end).trim();
        }
        if (implementsIdx >= 0) {
            int start = implementsIdx + " implements ".length();
            implementsPart = line.substring(start).trim();
        }

        List<String> candidates = new ArrayList<>();
        if (extendsPart != null && !extendsPart.isEmpty()) {
            candidates.add(extendsPart);
        }
        if (implementsPart != null && !implementsPart.isEmpty()) {
            for (String iface : Splitter.on(",").split(implementsPart)) {
                String t = iface.trim();
                if (!t.isEmpty()) candidates.add(t);
            }
        }

        if (candidates.isEmpty()) return List.of();

        List<CodeUnit> resolved = new ArrayList<>(candidates.size());
        for (String raw : candidates) {
            if (raw.isEmpty()) continue;

            // Strip generic arguments, normalize nesting separators
            String stripped = stripGenericTypeArguments(raw).trim();
            String normalized = normalizeFullName(stripped).trim();

            // Try fully qualified lookup first if it appears qualified, else try package+name then simple name
            List<String> fqCandidates = new ArrayList<>(3);
            if (normalized.contains(".")) {
                fqCandidates.add(normalized);
            } else {
                if (!cu.packageName().isEmpty()) {
                    fqCandidates.add(cu.packageName() + "." + normalized);
                }
                fqCandidates.add(normalized);
            }

            CodeUnit match = null;
            for (String fq : fqCandidates) {
                var def = getDefinition(fq);
                if (def.isPresent() && def.get().isClass()) {
                    match = def.get();
                    break;
                }
            }
            if (match != null) {
                resolved.add(match);
            }
        }

        return List.copyOf(resolved);
    }
}
