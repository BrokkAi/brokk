package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
import org.jetbrains.annotations.Nullable;

public final class TreeSitterAnalyzerStateMigrator {

    public static final SemVer JAVA_TS_REBUILD_THRESHOLD = SemVer.parse("1.2.0");

    private TreeSitterAnalyzerStateMigrator() {}

    static boolean shouldForceRebuild(@Nullable Language language, @Nullable SemVer fromVer, SemVer currentVer) {
        if (language == null) {
            return false;
        }

        if (language != Languages.JAVA && language != Languages.TYPESCRIPT) {
            return false;
        }

        return fromVer == null || fromVer.compareTo(JAVA_TS_REBUILD_THRESHOLD) < 0;
    }

    static TreeSitterStateIO.AnalyzerStateDto migrate(TreeSitterStateIO.AnalyzerStateDto dto, SemVer from, SemVer to) {
        return dto;
    }
}
