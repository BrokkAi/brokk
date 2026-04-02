package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
import org.jetbrains.annotations.Nullable;

public final class TreeSitterAnalyzerStateMigrator {

    /**
     * Threshold for Java that triggers a rebuild to ensure synthetic flags are correctly populated
     * in existing snapshots (transition from 2.0.0 -> 2.1.0).
     */
    public static final SemVer JAVA_REBUILD_THRESHOLD = SemVer.parse("2.1.0");

    /**
     * Threshold for TypeScript and Rust to ensure isTypeAlias flag is populated
     * in existing snapshots (transition from 2.1.0 -> 2.2.0).
     */
    public static final SemVer TYPESCRIPT_REBUILD_THRESHOLD = SemVer.parse("2.2.0");

    public static final SemVer RUST_REBUILD_THRESHOLD = SemVer.parse("2.2.0");

    private TreeSitterAnalyzerStateMigrator() {}

    static boolean shouldForceRebuild(@Nullable Language language, @Nullable SemVer fromVer, SemVer currentVer) {
        if (language == null) {
            return false;
        }

        // Java requires a rebuild to ensure synthetic flags are correctly populated
        // in existing snapshots (transition from 2.0.0 -> 2.1.0).
        if (language == Languages.JAVA) {
            return fromVer == null || fromVer.compareTo(JAVA_REBUILD_THRESHOLD) < 0;
        }

        if (language == Languages.TYPESCRIPT) {
            return fromVer == null || fromVer.compareTo(TYPESCRIPT_REBUILD_THRESHOLD) < 0;
        }

        if (language == Languages.RUST) {
            return fromVer == null || fromVer.compareTo(RUST_REBUILD_THRESHOLD) < 0;
        }

        return false;
    }

    static TreeSitterStateIO.AnalyzerStateDto migrate(TreeSitterStateIO.AnalyzerStateDto dto, SemVer from, SemVer to) {
        return dto;
    }
}
