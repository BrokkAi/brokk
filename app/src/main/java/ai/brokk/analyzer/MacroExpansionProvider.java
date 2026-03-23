package ai.brokk.analyzer;

import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroTemplateExpander;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

/**
 * Interface for analyzers that support macro expansion discovery and handling.
 */
@NullMarked
public interface MacroExpansionProvider extends CapabilityProvider {
    Logger log = LoggerFactory.getLogger(MacroExpansionProvider.class);

    /**
     * Discovers macro invocations in the given tree using the analyzer's MACROS query.
     */
    default void discoverMacros(
            TreeSitterAnalyzer analyzer,
            TSNode rootNode,
            ProjectFile file,
            SourceContent sourceContent,
            FileAnalysisAccumulator acc) {

        Language lang = analyzer.languages().iterator().next();
        MacroPolicy policy = analyzer.getProject().getMacroPolicies().get(lang);
        Map<String, MacroPolicy.MacroMatch> policyMap = policy == null
                ? Collections.emptyMap()
                : policy.macros().stream()
                        .collect(Collectors.toMap(MacroPolicy.MacroMatch::name, mm -> mm, (a, b) -> a));

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

                                String macroName =
                                        sourceContent.substringFrom(node).strip();

                                boolean handled = false;
                                MacroPolicy.MacroMatch mm = policyMap.get(macroName);
                                if (mm != null && isParentRequirementMet(macroName, mm, acc)) {
                                    handled = true;
                                    if (mm.options() instanceof MacroPolicy.TemplateConfig tc) {
                                        expandTemplate(analyzer, file, node, tc.template(), acc);
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

    private boolean isParentRequirementMet(String macroName, MacroPolicy.MacroMatch mm, FileAnalysisAccumulator acc) {
        String parent = mm.parent();
        if (parent == null) {
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
            return explicitImport.get().rawSnippet().contains(parent);
        }

        // 2. Fallback: If no explicit import, check if ANY wildcard imports the parent.
        return infos.stream()
                .anyMatch(info -> info.isWildcard() && info.rawSnippet().contains(parent));
    }

    private void expandTemplate(
            TreeSitterAnalyzer analyzer, ProjectFile file, TSNode node, String template, FileAnalysisAccumulator acc) {

        CodeUnit parentCu = findTargetCodeUnit(node, acc);
        if (parentCu == null) {
            log.warn(
                    "Could not find CodeUnit for macro attribute at {}-{} in {}",
                    node.getStartByte(),
                    node.getEndByte(),
                    file.getFileName());
            return;
        }

        Map<String, Object> context = Map.of("code_unit", parentCu, "children", acc.getChildren(parentCu));
        String expanded = MacroTemplateExpander.expand(template, context);

        FileAnalysisAccumulator snippetAcc = new FileAnalysisAccumulator();
        analyzer.analyzeSnippet(expanded, file, snippetAcc);

        mergeSyntheticUnits(parentCu, snippetAcc, acc, node);
    }

    private void mergeSyntheticUnits(
            CodeUnit parentCu, FileAnalysisAccumulator snippetAcc, FileAnalysisAccumulator acc, TSNode node) {

        List<CodeUnit> snippetUnits =
                snippetAcc.cuByFqName().values().stream().distinct().toList();
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

            CodeUnit rescopedCu = new CodeUnit(
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
            boolean isSnippetRoot = snippetAcc.topLevelCUs().contains(orig)
                    || snippetUnits.stream()
                            .noneMatch(pUnit -> snippetAcc.getChildren(pUnit).contains(orig));
            if (isSnippetRoot) {
                acc.addChild(parentCu, rescoped);
            }
        }
    }

    private @Nullable CodeUnit findTargetCodeUnit(TSNode node, FileAnalysisAccumulator acc) {
        // For attribute macros like #[derive(Is)], the node is the identifier inside the attribute.
        // We need to find the declaration that this attribute decorates.
        TSNode attributeItem = node;
        TSNode p = node.getParent();
        while (p != null && !p.isNull()) {
            if (p.getType().contains("attribute")) {
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

        for (CodeUnit cu : acc.cuByFqName().values().stream().distinct().toList()) {
            for (IAnalyzer.Range range : acc.getRanges(cu)) {
                boolean containsAttr = range.startByte() <= attrStartByte && range.endByte() >= attrEndByte;
                boolean matchesSibling = sibStart != -1 && range.startByte() == sibStart;
                boolean startsAfterAttr = range.startByte() >= attrEndByte && range.startByte() <= attrEndByte + 20;

                if (containsAttr || matchesSibling || startsAfterAttr) {
                    int len = range.endByte() - range.startByte();
                    if (len < bestLength) {
                        bestLength = len;
                        bestCu = cu;
                    }
                }
            }
        }
        return bestCu;
    }
}
