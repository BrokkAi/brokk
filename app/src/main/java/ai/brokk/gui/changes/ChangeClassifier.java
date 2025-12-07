package ai.brokk.gui.changes;

import ai.brokk.analyzer.BrokkFile;
import java.util.Objects;

/**
 * Classifies a file change pair (left/right content) as TEXT, BINARY, or OVERSIZED using simple, deterministic
 * heuristics:
 *
 * <ul>
 *   <li>Binary detection: uses {@link BrokkFile#isBinary(String)} on either side</li>
 *   <li>Size heuristics:
 *       <ul>
 *         <li>If combined length exceeds {@code maxCombined} => OVERSIZED</li>
 *         <li>If either side exceeds {@code maxSingle} => OVERSIZED</li>
 *         <li>Otherwise => TEXT</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * This centralizes the logic so callers can consistently decide whether to run an expensive diff or show a placeholder.
 */
public final class ChangeClassifier {

    private ChangeClassifier() {
        throw new AssertionError("no instances");
    }

    /**
     * Classify a pair of file contents.
     *
     * @param left        content of the "old" side; may be null (treated as empty)
     * @param right       content of the "new" side; may be null (treated as empty)
     * @param maxCombined maximum combined length (left+right) beyond which the file is considered OVERSIZED
     * @param maxSingle   maximum length for either side beyond which the file is considered OVERSIZED
     * @return the computed {@link ChangeFileStatus}
     */
    public static ChangeFileStatus classify(String left, String right, int maxCombined, int maxSingle) {
        String l = Objects.requireNonNullElse(left, "");
        String r = Objects.requireNonNullElse(right, "");

        try {
            // Binary heuristic
            if (BrokkFile.isBinary(l) || BrokkFile.isBinary(r)) {
                return ChangeFileStatus.BINARY;
            }
        } catch (Throwable t) {
            // Defensive: if binary detection fails, log at debug in callers; here fall through to size checks.
        }

        int combined = l.length() + r.length();
        if (combined > maxCombined) {
            return ChangeFileStatus.OVERSIZED;
        }

        if (l.length() > maxSingle || r.length() > maxSingle) {
            return ChangeFileStatus.OVERSIZED;
        }

        return ChangeFileStatus.TEXT;
    }
}
