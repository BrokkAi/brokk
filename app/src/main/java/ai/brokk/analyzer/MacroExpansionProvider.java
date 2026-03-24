package ai.brokk.analyzer;

import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroTemplateExpander;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

/** Interface for analyzers that support macro expansion discovery and handling. */
@NullMarked
public interface MacroExpansionProvider extends CapabilityProvider {
    Logger log = LoggerFactory.getLogger(MacroExpansionProvider.class);

    /** Discovers macro invocations in the given tree using the analyzer's MACROS query. */
    default void discoverMacros(
            TreeSitterAnalyzer analyzer,
            TSNode rootNode,
            ProjectFile file,
            SourceContent sourceContent,
            FileAnalysisAccumulator acc) {

        Language lang = analyzer.languages().iterator().next();

        Map<String, MacroPolicy.MacroMatch> policyMap = new HashMap<>();

        // 1. Add default policies
        for (MacroPolicy defaultPolicy : lang.getDefaultMacroPolicies()) {
            for (MacroPolicy.MacroMatch mm : defaultPolicy.macros()) {
                policyMap.put(mm.name(), mm);
            }
        }

        // 2. Overwrite with project-specific policies
        MacroPolicy projectPolicy = analyzer.getProject().getMacroPolicies().get(lang);
        if (projectPolicy != null) {
            for (MacroPolicy.MacroMatch mm : projectPolicy.macros()) {
                policyMap.put(mm.name(), mm);
            }
        }

        analyzer.withCachedQuery(
                TreeSitterAnalyzer.QueryType.MACROS,
                macrosQuery -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(macrosQuery, rootNode, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = macrosQuery.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node == null || node.isNull()) {
                                    continue;
                                }
                                // Only process captures that identify macro names
                                // .name = regular macro invocation
                                // .derive.name = derive macro argument (e.g., Is from #[derive(Is)])
                                // .attribute.name = other attribute macro
                                boolean isMacroNameCapture = captureName.endsWith(".name")
                                        || captureName.endsWith(".derive.name")
                                        || captureName.endsWith(".attribute.name");
                                if (!isMacroNameCapture) {
                                    continue;
                                }

                                String macroName = sourceContent.substringFrom(node).strip();

                                boolean handled = false;
                                MacroPolicy.MacroMatch mm = policyMap.get(macroName);
                                if (mm != null && isMacroPolicyMatch(macroName, mm, acc)) {
                                    handled = true;
                                    if (mm.options() instanceof MacroPolicy.TemplateConfig tc) {
                                        expandTemplate(
                                                analyzer, rootNode, file, node, sourceContent, tc.template(), acc);
                                    }
                                }

                                if (!handled && !"derive".equals(macroName)) {
                                    log.warn(
                                            "[{}] Unhandled macro invocation found in {}: {}",
                                            lang.name(),
                                            file.getFileName(),
                                            macroName);
                                }
                            }
                        }
                    }
                    return true;
                },
                false);
    }

    /**
     * Hook to allow subclasses to implement language-specific macro policy matching. For example, Rust macro policies
     * might need to check both underscores and hyphens in crate names.
     */
    default boolean isMacroPolicyMatch(String macroName, MacroPolicy.MacroMatch mm, FileAnalysisAccumulator acc) {
        String parent = mm.parent();
        if (parent == null || parent.isBlank()) {
            return true;
        }

        List<ImportInfo> infos = acc.importInfos();

        // 1. Direct/Explicit Import Check
        var explicitImport = infos.stream()
                .filter(info -> !info.isWildcard() && Objects.equals(info.identifier(), macroName))
                .findFirst();

        if (explicitImport.isPresent()) {
            // If explicitly imported, it MUST match the parent requirement.
            // Shadowing means we don't look at wildcards if an explicit match exists.
            String snippet = explicitImport.get().rawSnippet();
            return snippet.contains(parent);
        }

        // 2. Fallback: If no explicit import, check if ANY wildcard imports the parent.
        return infos.stream().anyMatch(info -> {
            String snippet = info.rawSnippet();
            return info.isWildcard() && snippet.contains(parent);
        });
    }

    private void expandTemplate(
            TreeSitterAnalyzer analyzer,
            TSNode rootNode,
            ProjectFile file,
            TSNode node,
            SourceContent sourceContent,
            String template,
            FileAnalysisAccumulator acc) {

        CodeUnit parentCu = findTargetCodeUnit(node, file, acc);
        if (parentCu == null) {
            log.warn(
                    "Could not find CodeUnit for macro attribute at {}-{} in {}",
                    node.getStartByte(),
                    node.getEndByte(),
                    file.getFileName());
            return;
        }

        Map<String, Object> context = buildMacroContext(analyzer, parentCu, acc, node, sourceContent, rootNode);
        enrichMacroContext(analyzer, parentCu, context, acc, node, sourceContent, rootNode);

        String expanded = MacroTemplateExpander.expand(template, context);

        FileAnalysisAccumulator snippetAcc = new FileAnalysisAccumulator();
        analyzer.analyzeSnippet(expanded, file, snippetAcc);

        mergeSyntheticUnits(parentCu, snippetAcc, acc, node);
    }

    private void mergeSyntheticUnits(
            CodeUnit parentCu, FileAnalysisAccumulator snippetAcc, FileAnalysisAccumulator acc, TSNode node) {

        List<CodeUnit> snippetUnits = snippetAcc.cuByFqName().values().stream().distinct().toList();
        Map<CodeUnit, CodeUnit> rescopedMap = new HashMap<>();

        // Phase 1: Create rescoped versions
        for (CodeUnit syntheticCu : snippetUnits) {
            if (Objects.equals(syntheticCu.fqName(), parentCu.fqName())) {
                continue;
            }

            String parentShortName = parentCu.shortName();
            String syntheticShortName = syntheticCu.shortName();
            boolean needsPrefix = parentCu.isClass() && !syntheticShortName.startsWith(parentShortName + ".");
            String newShortName = needsPrefix ? parentShortName + "." + syntheticShortName : syntheticShortName;

            CodeUnit rescopedCu =
                    new CodeUnit(
                            parentCu.source(),
                            syntheticCu.kind(),
                            parentCu.packageName(),
                            newShortName,
                            syntheticCu.signature(),
                            true);
            rescopedMap.put(syntheticCu, rescopedCu);
        }

        // Phase 2: Register and attach
        for (Map.Entry<CodeUnit, CodeUnit> entry : rescopedMap.entrySet()) {
            CodeUnit orig = entry.getKey();
            CodeUnit rescoped = entry.getValue();

            acc.registerCodeUnit(rescoped);
            acc.addLookupKey(rescoped.fqName(), rescoped);
            acc.addSymbolIndex(rescoped.identifier(), rescoped);
            if (!rescoped.shortName().equals(rescoped.identifier())) {
                acc.addSymbolIndex(rescoped.shortName(), rescoped);
            }
            acc.setHasBody(rescoped, snippetAcc.getHasBody(orig, false));
            snippetAcc.getSignatures(orig).forEach(sig -> acc.addSignature(rescoped, sig));

            // Use attribute range as a placeholder
            acc.addRange(
                    rescoped,
                    new IAnalyzer.Range(
                            node.getStartByte(),
                            node.getEndByte(),
                            node.getStartPoint().getRow(),
                            node.getStartPoint().getRow(),
                            node.getStartByte()));

            // Attach internal hierarchy
            for (CodeUnit child : snippetAcc.getChildren(orig)) {
                CodeUnit rescopedChild = rescopedMap.get(child);
                if (rescopedChild != null) {
                    acc.addChild(rescoped, rescopedChild);
                }
            }

            // Attach roots of snippet to parent
            boolean isSnippetRoot =
                    snippetAcc.topLevelCUs().contains(orig)
                            || snippetUnits.stream()
                                    .noneMatch(pUnit -> snippetAcc.getChildren(pUnit).contains(orig));
            if (isSnippetRoot) {
                // If the snippet root is a container (like an impl block) and the target is a class,
                // we often want to flatten its children directly into the target.
                if (rescoped.isClass()
                        && parentCu.isClass()
                        && rescoped.identifier().equals(parentCu.identifier())) {
                    for (CodeUnit child : acc.getChildren(rescoped)) {
                        acc.addChild(parentCu, child);
                    }
                } else {
                    // Attach to the same parent as the target code unit (parentCu)
                    // If parentCu has a parent in the accumulator, we use that.
                    Optional<CodeUnit> targetParent =
                            acc.cuByFqName().values().stream()
                                    .filter(pUnit -> acc.getChildren(pUnit).contains(parentCu))
                                    .findFirst();

                    if (targetParent.isPresent()) {
                        acc.addChild(targetParent.get(), rescoped);
                    } else {
                        acc.addChild(parentCu, rescoped);
                    }
                }
            }
        }
    }

    /** Builds the context map for macro template expansion. */
    default Map<String, Object> buildMacroContext(
            TreeSitterAnalyzer analyzer,
            CodeUnit targetCu,
            FileAnalysisAccumulator acc,
            TSNode macroNode,
            SourceContent sourceContent,
            TSNode rootNode) {

        Map<String, Object> context = new HashMap<>();
        context.put("code_unit", targetCu);

        List<Map<String, Object>> childContexts =
                acc.getChildren(targetCu).stream()
                        .map(
                                childCu -> {
                                    Map<String, Object> childMap = new HashMap<>();
                                    String identifier = childCu.identifier();
                                    childMap.put("code_unit", childCu);
                                    childMap.put("identifier", identifier);
                                    childMap.put("snake_case_name", toSnakeCase(identifier));

                                    enrichChildContext(
                                            analyzer, targetCu, childCu, childMap, acc, sourceContent, rootNode);
                                    return childMap;
                                })
                        .toList();

        context.put("children", childContexts);

        return context;
    }

    /** Hook to allow subclasses to add language-specific variables to the root macro context. */
    default void enrichMacroContext(
            TreeSitterAnalyzer analyzer,
            CodeUnit targetCu,
            Map<String, Object> context,
            FileAnalysisAccumulator acc,
            TSNode macroNode,
            SourceContent sourceContent,
            TSNode rootNode) {
        // Default no-op
    }

    /** Hook to allow subclasses to add language-specific variables to a child's macro context. */
    default void enrichChildContext(
            TreeSitterAnalyzer analyzer,
            CodeUnit targetCu,
            CodeUnit childCu,
            Map<String, Object> childMap,
            FileAnalysisAccumulator acc,
            SourceContent sourceContent,
            TSNode rootNode) {
        // Default no-op
    }

    private String toSnakeCase(String input) {
        if (input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private @Nullable CodeUnit findTargetCodeUnit(
            TSNode node, ProjectFile file, FileAnalysisAccumulator acc) {
        // For attribute macros like #[derive(Is)], the node is the identifier inside the attribute.
        // We need to find the declaration that this attribute decorates.
        TSNode attributeItem = node;
        TSNode p = node.getParent();
        while (p != null && !p.isNull()) {
            // Note: Rust top-level bang macros like lazy_static! are often wrapped in macro_invocation
            if (p.getType().contains("attribute") || p.getType().contains("macro_invocation")) {
                attributeItem = p;
            }
            p = p.getParent();
        }

        // Find next sibling that isn't an attribute or comment
        TSNode sibling = attributeItem.getNextSibling();
        while (sibling != null
                && !sibling.isNull()
                && (sibling.getType().contains("attribute") || sibling.getType().equals("comment"))) {
            sibling = sibling.getNextSibling();
        }
        int sibStart = (sibling != null && !sibling.isNull()) ? sibling.getStartByte() : -1;
        int attrStartByte = attributeItem.getStartByte();
        int attrEndByte = attributeItem.getEndByte();

        CodeUnit bestCu = null;
        int bestLength = Integer.MAX_VALUE;

        List<CodeUnit> allCus = acc.cuByFqName().values().stream().distinct().toList();
        for (CodeUnit cu : allCus) {
            for (IAnalyzer.Range range : acc.getRanges(cu)) {
                boolean containsAttr =
                        range.startByte() <= attrStartByte && range.endByte() >= attrEndByte;
                boolean matchesSibling = sibStart != -1 && range.startByte() == sibStart;
                boolean startsAfterAttr =
                        range.startByte() >= attrEndByte && range.startByte() <= attrEndByte + 20;

                if (containsAttr || matchesSibling || startsAfterAttr) {
                    int len = range.endByte() - range.startByte();
                    if (len < bestLength) {
                        bestLength = len;
                        bestCu = cu;
                    }
                }
            }
        }

        // Fallback: If no specific CodeUnit was found (e.g. top-level bang macro),
        // target the Module CodeUnit for this file.
        if (bestCu == null) {
            return allCus.stream()
                    .filter(cu -> cu.isModule() && Objects.equals(cu.source(), file))
                    .findFirst()
                    .orElse(null);
        }

        return bestCu;
    }
}
