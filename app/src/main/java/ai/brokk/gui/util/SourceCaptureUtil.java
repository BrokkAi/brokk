package ai.brokk.gui.util;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.SourceCodeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility for capturing source code from CodeUnits and adding them as workspace fragments. Used by both
 * ContextMenuBuilder and PreviewTextPanel to ensure consistent behavior.
 */
public class SourceCaptureUtil {

    private static final Logger logger = LoggerFactory.getLogger(SourceCaptureUtil.class);

    /**
     * Captures source code for the given CodeUnit and adds it as a workspace fragment.
     *
     * @param codeUnit The CodeUnit to capture source for
     * @param contextManager The context manager to submit the task to
     */
    public static void captureSourceForCodeUnit(CodeUnit codeUnit, ContextManager contextManager) {
        contextManager.submitBackgroundTask("Capture Source Code", () -> {
            try {
                var analyzer = contextManager.getAnalyzer();
                contextManager.sourceCodeForCodeUnit(analyzer, codeUnit);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while capturing code unit", e);
            }
        });
    }

    /**
     * Checks if source capture is available for the given CodeUnit based on analyzer capabilities.
     *
     * @param codeUnit The CodeUnit to check
     * @param hasSourceCapability Whether the analyzer has source code capability
     * @param analyzer The analyzer instance (used for language-specific source detection)
     * @return true if source capture is available and supported
     */
    public static boolean isSourceCaptureAvailable(CodeUnit codeUnit, boolean hasSourceCapability, IAnalyzer analyzer) {
        if (!hasSourceCapability) {
            return false;
        }

        return analyzer.as(SourceCodeProvider.class)
                .map(provider -> {
                    try {
                        return provider.getSourceForCodeUnit(codeUnit, true).isPresent();
                    } catch (Exception e) {
                        logger.warn("Unable to obtain source code for {}", codeUnit.fqName(), e);
                        return false;
                    }
                })
                .orElse(false);
    }

    /**
     * Checks if source capture is available for the given CodeUnit based on analyzer capabilities.
     *
     * @param codeUnit The CodeUnit to check
     * @param hasSourceCapability Whether the analyzer has source code capability
     * @return true if source capture is available and supported
     * @deprecated Use {@link #isSourceCaptureAvailable(CodeUnit, boolean, IAnalyzer)} for TypeScript type alias support
     */
    @Deprecated
    public static boolean isSourceCaptureAvailable(CodeUnit codeUnit, boolean hasSourceCapability) {
        return (codeUnit.isFunction() || codeUnit.isClass()) && hasSourceCapability;
    }

    /**
     * Gets the tooltip message for when source capture is not available.
     *
     * @return The tooltip message explaining why source capture is unavailable
     */
    public static String getSourceCaptureUnavailableTooltip() {
        return "Code intelligence does not support source code capturing for this language.";
    }
}
