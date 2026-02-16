package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
import org.jetbrains.annotations.Nullable;

public final class TreeSitterAnalyzerStateMigrator {

    /**
     * Threshold within the current major version line that triggers a rebuild for strict languages.
     */
    public static final SemVer REBUILD_THRESHOLD = SemVer.parse("2.0.0");

    private TreeSitterAnalyzerStateMigrator() {}

    static boolean shouldForceRebuild(@Nullable Language language, @Nullable SemVer fromVer, SemVer currentVer) {
        if (language == null) {
            return false;
        }

        // Java and TypeScript require exact version matches within the current major line
        // to ensure FQN and property consistency.
        if (language != Languages.JAVA && language != Languages.TYPESCRIPT) {
            return false;
        }

        return fromVer == null || fromVer.compareTo(REBUILD_THRESHOLD) < 0;
    }

    static TreeSitterStateIO.AnalyzerStateDto migrate(TreeSitterStateIO.AnalyzerStateDto dto, SemVer from, SemVer to) {
        return dto;
    }
}
