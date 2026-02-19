package ai.brokk.analyzer.rust.macro;

import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.ATTRIBUTE_ITEM;
import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.ENUM_ITEM;
import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.ENUM_VARIANT;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.util.CaseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.treesitter.TSNode;

/**
 * Expander for the `is_macro::Is` derive macro which generates `is_variant()` methods for enums.
 */
public class IsMacroExpander implements RustMacroExpander {

    private static final Pattern IS_ATTRIBUTE_PATTERN =
            Pattern.compile("(?s).*(?::|\\(|\\b)Is(?:\\b|\\)|,).*", Pattern.DOTALL);

    @Override
    public boolean supports(TSNode targetNode, SourceContent source) {
        if (!ENUM_ITEM.equals(targetNode.getType())) {
            return false;
        }

        // Check children (attributes are often children of the enum_item in rust grammar)
        for (int i = 0; i < targetNode.getChildCount(); i++) {
            TSNode child = targetNode.getChild(i);
            if (ATTRIBUTE_ITEM.equals(child.getType())) {
                if (IS_ATTRIBUTE_PATTERN.matcher(source.substringFrom(child)).matches()) {
                    return true;
                }
            }
        }

        // Check preceding siblings
        TSNode prev = targetNode.getPrevSibling();
        while (prev != null && !prev.isNull()) {
            if (ATTRIBUTE_ITEM.equals(prev.getType())) {
                if (IS_ATTRIBUTE_PATTERN.matcher(source.substringFrom(prev)).matches()) {
                    return true;
                }
            } else if (!isWhitespaceOrComment(prev)) {
                break;
            }
            prev = prev.getPrevSibling();
        }

        return false;
    }

    private boolean isWhitespaceOrComment(TSNode node) {
        String type = node.getType();
        return type.equals("line_comment") || type.equals("block_comment") || node.getStartByte() == node.getEndByte();
    }

    @Override
    public List<CodeUnit> expand(TSNode targetNode, SourceContent source, ProjectFile file, String packageName) {
        if (!ENUM_ITEM.equals(targetNode.getType())) {
            return List.of();
        }

        TSNode nameNode = targetNode.getChildByFieldName("name");
        if (nameNode == null || nameNode.isNull()) {
            return List.of();
        }

        String enumName = source.substringFrom(nameNode);
        List<CodeUnit> expanded = new ArrayList<>();

        // Create the impl block CodeUnit
        expanded.add(CodeUnit.cls(file, packageName, enumName));

        // Find variants in the body
        TSNode body = targetNode.getChildByFieldName("body");
        if (body != null && !body.isNull()) {
            for (int i = 0; i < body.getChildCount(); i++) {
                TSNode child = body.getChild(i);
                if (ENUM_VARIANT.equals(child.getType())) {
                    TSNode variantNameNode = child.getChildByFieldName("name");
                    if (variantNameNode != null && !variantNameNode.isNull()) {
                        String variantName = source.substringFrom(variantNameNode);
                        String methodName = "is_" + CaseUtil.toSnakeCase(variantName);
                        // Function CodeUnit: EnumName.is_variant
                        expanded.add(CodeUnit.fn(file, packageName, enumName + "." + methodName));
                    }
                }
            }
        }

        return expanded;
    }
}
