package ai.brokk.analyzer.go;

import static ai.brokk.analyzer.go.Constants.nodeField;
import static ai.brokk.analyzer.go.Constants.nodeType;
import static java.util.Objects.requireNonNull;
import static org.treesitter.GoNodeType.*;

import ai.brokk.analyzer.ASTTraversalUtils;
import ai.brokk.analyzer.IAnalyzer.SourceLookupAlias;
import ai.brokk.analyzer.SourceContent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.GoNodeField;
import org.treesitter.GoNodeType;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

public final class GoSourceLookupAliases {

    private GoSourceLookupAliases() {}

    public static Collection<SourceLookupAlias> create(TSParser parser, String requestedName) {
        LinkedHashSet<SourceLookupAlias> aliases = new LinkedHashSet<>();
        String normalizedName = requestedName.strip();
        addSourceLookupAlias(aliases, SourceLookupAlias.anySource(normalizedName));
        if (normalizedName.isEmpty()) {
            return aliases;
        }

        addGoPathAliases(aliases, normalizedName);
        normalizeGoLookupName(parser, normalizedName).ifPresent(parsedName -> {
            addSourceLookupAlias(aliases, SourceLookupAlias.anySource(parsedName));
            addGoPathAliases(aliases, parsedName);
        });

        return aliases;
    }

    private static Optional<String> normalizeGoLookupName(TSParser parser, String name) {
        String unixName = name.replace('\\', '/');
        int slashIdx = unixName.lastIndexOf('/');
        String pathPrefix = slashIdx >= 0 ? unixName.substring(0, slashIdx) : "";
        String tail = slashIdx >= 0 ? unixName.substring(slashIdx + 1) : unixName;
        if (!requiresGoSyntaxNormalization(tail)) {
            return Optional.empty();
        }
        return normalizeGoLookupTail(parser, tail)
                .map(normalizedTail -> pathPrefix.isEmpty() ? normalizedTail : pathPrefix + "/" + normalizedTail);
    }

    private static boolean requiresGoSyntaxNormalization(String tail) {
        return tail.indexOf('(') >= 0 || tail.indexOf('[') >= 0;
    }

    private static Optional<String> normalizeGoLookupTail(TSParser parser, String tail) {
        String source = "package lookup\nvar _ = " + tail + "\n";
        SourceContent sourceContent = SourceContent.of(source);
        try (TSTree tree = parser.parseString(null, source)) {
            if (tree == null) {
                return Optional.empty();
            }

            TSNode rootNode = tree.getRootNode();
            if (!ASTTraversalUtils.isValid(rootNode)) {
                return Optional.empty();
            }
            TSNode root = requireNonNull(rootNode);
            if (root.hasError()) {
                return Optional.empty();
            }

            TSNode expression = lookupInitializerExpression(root);
            if (!ASTTraversalUtils.isValid(expression)) {
                return Optional.empty();
            }

            String normalizedTail = renderGoLookupExpression(requireNonNull(expression), sourceContent);
            return normalizedTail.isBlank() ? Optional.empty() : Optional.of(normalizedTail);
        }
    }

    private static @Nullable TSNode lookupInitializerExpression(TSNode rootNode) {
        TSNode varSpec = ASTTraversalUtils.findNodeRecursive(
                rootNode, node -> nodeType(VAR_SPEC).equals(ASTTraversalUtils.typeOf(node)));
        if (!ASTTraversalUtils.isValid(varSpec)) {
            return null;
        }

        TSNode valueNode = requireNonNull(varSpec).getChildByFieldName(nodeField(GoNodeField.VALUE));
        if (!ASTTraversalUtils.isValid(valueNode)) {
            return null;
        }

        if (nodeType(EXPRESSION_LIST).equals(ASTTraversalUtils.typeOf(valueNode))
                && requireNonNull(valueNode).getNamedChildCount() > 0) {
            return valueNode.getNamedChild(0);
        }
        return valueNode;
    }

    private static String renderGoLookupExpression(TSNode node, SourceContent sourceContent) {
        return switch (GoNodeType.from(node)) {
            case SELECTOR_EXPRESSION -> renderGoSelectorExpression(node, sourceContent);
            case TYPE_ASSERTION_EXPRESSION -> renderGoTypeAssertionExpression(node, sourceContent);
            case POINTER_TYPE, PARENTHESIZED_TYPE, TYPE_INSTANTIATION_EXPRESSION, INDEX_EXPRESSION, GENERIC_TYPE ->
                renderFirstStructuralChild(node, sourceContent);
            case QUALIFIED_TYPE -> renderGoQualifiedType(node, sourceContent);
            default -> sourceContent.substringFrom(node).strip();
        };
    }

    private static String renderGoSelectorExpression(TSNode node, SourceContent sourceContent) {
        TSNode operand = node.getChildByFieldName(nodeField(GoNodeField.OPERAND));
        TSNode field = node.getChildByFieldName(nodeField(GoNodeField.FIELD));
        if (!ASTTraversalUtils.isValid(operand) || !ASTTraversalUtils.isValid(field)) {
            return sourceContent.substringFrom(node).strip();
        }

        String operandText = renderGoLookupExpression(requireNonNull(operand), sourceContent);
        String fieldText = sourceContent.substringFrom(requireNonNull(field)).strip();
        return joinGoLookupParts(operandText, fieldText);
    }

    private static String renderGoTypeAssertionExpression(TSNode node, SourceContent sourceContent) {
        TSNode operand = node.getChildByFieldName(nodeField(GoNodeField.OPERAND));
        TSNode type = node.getChildByFieldName(nodeField(GoNodeField.TYPE));
        if (!ASTTraversalUtils.isValid(operand) || !ASTTraversalUtils.isValid(type)) {
            return sourceContent.substringFrom(node).strip();
        }

        String operandText = renderGoLookupExpression(requireNonNull(operand), sourceContent);
        String typeText = renderGoLookupExpression(requireNonNull(type), sourceContent);
        return joinGoLookupParts(operandText, typeText);
    }

    private static String renderFirstStructuralChild(TSNode node, SourceContent sourceContent) {
        for (TSNode child : ASTTraversalUtils.namedChildren(node)) {
            String childType = ASTTraversalUtils.typeOf(child);
            if (nodeType(TYPE_ARGUMENTS).equals(childType)
                    || nodeType(ARGUMENT_LIST).equals(childType)) {
                continue;
            }
            return renderGoLookupExpression(child, sourceContent);
        }
        return sourceContent.substringFrom(node).strip();
    }

    private static String renderGoQualifiedType(TSNode node, SourceContent sourceContent) {
        TSNode packageNode = node.getChildByFieldName(nodeField(GoNodeField.PACKAGE_));
        TSNode nameNode = node.getChildByFieldName(nodeField(GoNodeField.NAME));
        if (!ASTTraversalUtils.isValid(packageNode) || !ASTTraversalUtils.isValid(nameNode)) {
            return sourceContent.substringFrom(node).strip();
        }

        String packageName =
                sourceContent.substringFrom(requireNonNull(packageNode)).strip();
        String typeName = sourceContent.substringFrom(requireNonNull(nameNode)).strip();
        return joinGoLookupParts(packageName, typeName);
    }

    private static String joinGoLookupParts(String first, String second) {
        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank()) {
            return first;
        }
        return first + "." + second;
    }

    private static void addGoPathAliases(Set<SourceLookupAlias> aliases, String name) {
        String unixName = name.replace('\\', '/');
        addGoPathAliasForSuffix(aliases, unixName);
    }

    private static void addGoPathAliasForSuffix(Set<SourceLookupAlias> aliases, String pathQualifiedName) {
        int slashIdx = pathQualifiedName.lastIndexOf('/');
        String pathPrefix = slashIdx >= 0 ? pathQualifiedName.substring(0, slashIdx) : "";
        String tail = slashIdx >= 0 ? pathQualifiedName.substring(slashIdx + 1) : pathQualifiedName;
        var parts = List.of(tail.split("\\."));
        if (parts.size() < 2) {
            return;
        }

        String packageName = parts.getFirst();
        String packageDirectory = pathPrefix.isEmpty() ? packageName : pathPrefix + "/" + packageName;
        if (slashIdx >= 0) {
            addSourceLookupAlias(
                    aliases,
                    SourceLookupAlias.sourceDirectory(
                            packageName + "." + String.join(".", parts.subList(1, parts.size())), packageDirectory));
        }

        if (parts.size() < 4) {
            return;
        }

        var aliasParts = new ArrayList<String>(parts.size() - 1);
        aliasParts.add(packageName);
        aliasParts.addAll(parts.subList(2, parts.size()));
        addSourceLookupAlias(
                aliases,
                SourceLookupAlias.sourceFile(
                        String.join(".", aliasParts), packageDirectory + "/" + parts.get(1) + ".go"));
    }

    private static void addSourceLookupAlias(Set<SourceLookupAlias> aliases, SourceLookupAlias alias) {
        if (!alias.lookupName().isBlank()) {
            aliases.add(alias);
        }
    }
}
