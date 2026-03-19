package ai.brokk.analyzer;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroTemplateExpander;
import java.util.Map;
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
            TreeSitterAnalyzer analyzer, TSNode rootNode, ProjectFile file, SourceContent sourceContent) {
        Language lang = analyzer.languages().iterator().next();
        MacroPolicy policy = analyzer.getProject().getMacroPolicies().get(lang);

        analyzer.withCachedQuery(
                TreeSitterAnalyzer.QueryType.MACROS,
                macrosQuery -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(macrosQuery, rootNode);
                        TSQueryMatch match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = macrosQuery.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node != null && !node.isNull() && captureName.endsWith(".invocation")) {
                                    String macroText =
                                            sourceContent.substringFrom(node).strip();

                                    boolean handled = false;
                                    if (policy != null) {
                                        int bangIndex = macroText.indexOf('!');
                                        String macroName = bangIndex > 0
                                                ? macroText.substring(0, bangIndex).strip()
                                                : macroText;

                                        for (MacroPolicy.MacroMatch mm : policy.macros()) {
                                            if (mm.name().equals(macroName)) {
                                                if (mm.strategy() == MacroPolicy.MacroStrategy.TEMPLATE
                                                        && mm.options()
                                                                instanceof MacroPolicy.TemplateConfig tc) {
                                                    expandTemplate(analyzer, file, node, tc.template());
                                                    handled = true;
                                                }
                                                break;
                                            }
                                        }
                                    }

                                    if (!handled) {
                                        log.warn(
                                                "[{}] Unhandled macro invocation found in {}: {}",
                                                lang.name(),
                                                file.getFileName(),
                                                macroText);
                                    }
                                }
                            }
                        }
                    }
                    return true;
                },
                false);
    }

    private void expandTemplate(TreeSitterAnalyzer analyzer, ProjectFile file, TSNode node, String template) {
        analyzer.enclosingCodeUnit(file, node.getStartPoint().getRow(), node.getEndPoint().getRow())
                .ifPresent(cu -> {
                    String expanded = MacroTemplateExpander.expand(template, Map.of("code_unit", cu));
                    log.debug("Expanded macro template for {}: {}", cu.fqName(), expanded);
                    // Implementation note: The expanded code would typically be injected into the analysis
                    // accumulator, but since the accumulator is not currently passed to discoverMacros
                    // in the provider interface, we log it for now as per the instruction focus.
                });
    }
}
