package ai.brokk.analyzer;

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
            TreeSitterAnalyzer analyzer, TSNode rootNode, ProjectFile file, SourceContent sourceContent) {
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
                                    log.warn(
                                            "[{}] Unhandled macro invocation found in {}: {}",
                                            analyzer.languages()
                                                    .iterator()
                                                    .next()
                                                    .name(),
                                            file.getFileName(),
                                            macroText);
                                }
                            }
                        }
                    }
                    return true;
                },
                false);
    }
}
