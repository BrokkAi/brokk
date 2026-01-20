package ai.brokk.util.sandbox;

import java.util.Locale;

public enum Platform {
    MACOS,
    LINUX,
    WINDOWS,
    UNKNOWN;

    public static Platform getPlatform() {
        return fromOsName(System.getProperty("os.name"));
    }

    public static Platform fromOsName(String osName) {
        if (osName == null) {
            return UNKNOWN;
        }

        String normalized = osName.toLowerCase(Locale.ROOT);

        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return MACOS;
        }
        if (normalized.contains("linux")) {
            return LINUX;
        }
        if (normalized.contains("win")) {
            return WINDOWS;
        }

        return UNKNOWN;
    }

    public static boolean isSupportedPlatform(Platform platform) {
        return platform == MACOS || platform == LINUX;
    }
}
