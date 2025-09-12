package io.github.jbellis.brokk;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/* package-private */ interface SystemScaleProvider {
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
