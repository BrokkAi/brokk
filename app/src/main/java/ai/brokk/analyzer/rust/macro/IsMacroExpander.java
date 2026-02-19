package ai.brokk.analyzer.rust.macro;

import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.ENUM_ITEM;
import static ai.brokk.analyzer.rust.RustTreeSitterNodeTypes.ENUM_VARIANT;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.treesitter.TSNode;

/**
 * Expander for the `is_macro::Is` derive macro which generates `is_variant()` methods for enums.
 */
@NullMarked
public class IsMacroExpander implements RustMacroExpander {

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
                        String methodName = "is_" + toSnakeCase(variantName);
                        // Function CodeUnit: EnumName.is_variant
                        expanded.add(CodeUnit.fn(file, packageName, enumName + "." + methodName));
                    }
                }
            }
        }

        return expanded;
    }

    private String toSnakeCase(String input) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
