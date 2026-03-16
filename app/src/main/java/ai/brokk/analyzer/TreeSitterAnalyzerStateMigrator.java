package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
import org.jetbrains.annotations.Nullable;

public final class TreeSitterAnalyzerStateMigrator {

    /**
     * Threshold within the current major version line that triggers a rebuild for strict languages.
     */
    public static final SemVer REBUILD_THRESHOLD = SemVer.parse("2.1.0");

    private TreeSitterAnalyzerStateMigrator() {}

    static boolean shouldForceRebuild(@Nullable Language language, @Nullable SemVer fromVer, SemVer currentVer) {
        if (language == null) {
            return false;
        }

        // Java requires a rebuild to ensure synthetic flags are correctly populated
        // in existing snapshots (transition from 2.0.0 -> 2.1.0).
        if (language == Languages.JAVA) {
            return fromVer == null || fromVer.compareTo(REBUILD_THRESHOLD) < 0;
        }

        // TypeScript remains strict but doesn't necessarily need a 2.1.0 force-rebuild
        // if its definitions haven't changed in a way that requires it.
        // For now, only Java is targeted for the 2.1.0 synthetic flag migration.
        return false;
    }

    static TreeSitterStateIO.AnalyzerStateDto migrate(TreeSitterStateIO.AnalyzerStateDto dto, SemVer from, SemVer to) {
        return dto;
    }
}
