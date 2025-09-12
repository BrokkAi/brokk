package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;

/**
 * Shared utility for capturing source code from CodeUnits and adding them as workspace fragments. Used by both
 * ContextMenuBuilder and PreviewTextPanel to ensure consistent behavior.
 */
public class SourceCaptureUtil {

    /**
     * Captures source code for the given CodeUnit and adds it as a workspace fragment.
     *
     * @param codeUnit The CodeUnit to capture source for
     * @param contextManager The context manager to submit the task to
     */
    public static void captureSourceForCodeUnit(CodeUnit codeUnit, ContextManager contextManager) {
        contextManager.submitBackgroundTask(
                "Capture Source Code", () -> contextManager.sourceCodeForCodeUnit(codeUnit));
    }

    /**
     * Checks if source capture is available for the given CodeUnit based on analyzer capabilities.
     *
     * @param codeUnit The CodeUnit to check
     * @param hasSourceCapability Whether the analyzer has source code capability
     * @return true if source capture is available and supported
     */
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
