package io.github.jbellis.brokk;

import com.google.common.base.Splitter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Extraction of HiDPI detection/parsing logic that is testable via injection of SystemScaleProvider. */
public final class SystemScaleDetector {

    private SystemScaleDetector() {}

    public static final Pattern GNOME_DBUS_SCALE_PATTERN =
            Pattern.compile("\\(\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*([\\d.]+)\\s*,[^,]+,\\s*true[,)]");

    public static @Nullable Double detectLinuxUiScale(SystemScaleProvider provider) {
        var kde = tryDetectScaleViaKscreenDoctor(provider);
        if (kde != null) {
            return normalizeUiScaleToAllowed(kde);
        }

        var gnomeModern = tryDetectScaleViaGnomeDBus(provider);
        if (gnomeModern != null) {
            return normalizeUiScaleToAllowed(gnomeModern);
        }

        var gnome = tryDetectScaleViaGsettings(provider);
        if (gnome != null) {
            return normalizeUiScaleToAllowed(gnome);
        }
        return null;
    }

    public static double normalizeUiScaleToAllowed(double v) {
        int rounded = (int) Math.round(v);
        if (rounded < 1) rounded = 1;
        if (rounded > 5) rounded = 5;
        return (double) rounded;
    }

    public static @Nullable Double tryDetectScaleViaKscreenDoctor(SystemScaleProvider provider) {
        var lines = provider.runCommand("kscreen-doctor", "-o");
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        Double firstScale = null;
        Double currentScale = null;
        boolean currentPrimary = false;

        for (var raw : lines) {
            var line = raw.trim();
            if (line.startsWith("Output")) {
                // New block: if previous block was primary and had a scale, prefer it
                if (currentPrimary && currentScale != null) {
                    return currentScale;
                }
                if (firstScale == null && currentScale != null) {
                    firstScale = currentScale;
                }
                // reset for new block
                currentScale = null;
                currentPrimary = false;
                continue;
            }

            if (line.regionMatches(true, 0, "Scale:", 0, "Scale:".length())) {
                var val = line.substring("Scale:".length()).trim();
                try {
                    var tokens = Splitter.on(Pattern.compile("\\s+"))
                            .omitEmptyStrings()
                            .splitToList(val);
                    if (!tokens.isEmpty()) {
                        currentScale = Double.parseDouble(tokens.get(0));
                    }
                } catch (NumberFormatException nfe) {
                    // ignore parse errors, keep behavior consistent with prior code
                }
                continue;
            }

            if (line.toLowerCase(Locale.ROOT).startsWith("primary:")) {
                var val = line.substring("primary:".length()).trim().toLowerCase(Locale.ROOT);
                currentPrimary = val.startsWith("yes") || val.startsWith("true");
            }
        }

        // After loop, last block check
        if (currentPrimary && currentScale != null) {
            return currentScale;
        }
        if (firstScale != null) {
            return firstScale;
        }
        return null;
    }

    public static @Nullable Double tryDetectScaleViaGsettings(SystemScaleProvider provider) {
        var lines = provider.runCommand("gsettings", "get", "org.gnome.desktop.interface", "scaling-factor");
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        var out = String.join(" ", lines).trim();
        // Expected formats: "uint32 2" or "2"
        String numberPart = out;
        if (out.startsWith("uint32")) {
            var parts = Splitter.on(Pattern.compile("\\s+")).omitEmptyStrings().splitToList(out);
            if (parts.size() >= 2) {
                numberPart = parts.get(1);
            }
        }
        try {
            int i = Integer.parseInt(numberPart);
            if (i >= 1) {
                return (double) i;
            }
        } catch (NumberFormatException nfe) {
            // ignore, return null below
        }
        return null;
    }

    public static @Nullable Double tryDetectScaleViaGnomeDBus(SystemScaleProvider provider) {
        var lines = provider.runCommand(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.gnome.Mutter.DisplayConfig",
                "--object-path",
                "/org/gnome/Mutter/DisplayConfig",
                "--method",
                "org.gnome.Mutter.DisplayConfig.GetCurrentState");
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        var output = String.join(" ", lines);

        // This regex looks for a logical monitor tuple that is marked as primary.
        // It captures the scale factor from that tuple.
        // e.g., `... [(0, 0, 2.0, uint32 0, true, ...)] ...`
        // The scale is the 3rd element in the tuple, and the primary flag is the 5th.
        var matcher = GNOME_DBUS_SCALE_PATTERN.matcher(output);

        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                // ignore, will return null
            }
        }

        return null;
    }

    public static @Nullable Double tryDetectScaleViaWindows(SystemScaleProvider provider) {
        // Try GraphicsConfiguration first (preferred)
        var fromGc = provider.getGraphicsConfigScale();
        if (fromGc != null) {
            return fromGc;
        }

        // Fallback: Toolkit DPI (96 DPI == 1.0)
        var toolkitDpi = provider.getToolkitDpi();
        if (toolkitDpi != null && toolkitDpi > 0) {
            return toolkitDpi / 96.0;
        }

        // Last resort: registry AppliedDPI
        var lines =
                provider.runCommand("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "AppliedDPI");
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        for (var raw : lines) {
            var line = raw.trim();
            if (line.toLowerCase(Locale.ROOT).contains("applieddpi")) {
                var parts =
                        Splitter.on(Pattern.compile("\\s+")).omitEmptyStrings().splitToList(line);
                if (parts.size() >= 3) {
                    var valStr = parts.get(parts.size() - 1);
                    try {
                        int appliedDpi;
                        if (valStr.startsWith("0x")) {
                            appliedDpi = Integer.parseInt(valStr.substring(2), 16);
                        } else {
                            appliedDpi = Integer.parseInt(valStr);
                        }
                        if (appliedDpi > 0) {
                            return appliedDpi / 96.0;
                        }
                    } catch (NumberFormatException nfe) {
                        // ignore parse errors
                    }
                }
            }
        }
        return null;
    }
}
