package io.github.jbellis.brokk.util;

/**
 * Compile-time-free flag container.
 * Activate with  -Dbrokk.debugFileBadges=true
 */
public final class DebugFlags {
    public static final boolean FILE_BADGE_DIAGNOSTICS =
            Boolean.getBoolean("brokk.debugFileBadges");
    private DebugFlags() {}
}
