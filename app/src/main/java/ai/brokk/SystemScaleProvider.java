package ai.brokk;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Abstraction for platform-specific access used by {@link SystemScaleDetector} to determine UI scale.
 *
 * <p>The default implementation is {@link SystemScaleProviderImpl}, but tests can provide their own
 * implementation to simulate various environments.
 */
public interface SystemScaleProvider {
    /** Return the scale derived from GraphicsConfiguration default transform (or null if unavailable). */
    @Nullable
    Double getGraphicsConfigScale();

    /** Return toolkit DPI (Toolkit.getDefaultToolkit().getScreenResolution()) or null if unavailable. */
    @Nullable
    Integer getToolkitDpi();

    /** Run an external command synchronously and return textual output as a list of lines, or null on timeout/error. */
    @Nullable
    List<String> runCommand(String... command);
}
