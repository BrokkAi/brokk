package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
import org.jetbrains.annotations.Nullable;

public final class TreeSitterAnalyzerStateMigrator {

    /**
     * Threshold for Java: snapshots below this version are rejected so strict languages rebuild after
     * incompatible analyzer-state changes (including schema 3.0.0 CodeUnitProperties annotations/attributes).
     */
    public static final SemVer JAVA_REBUILD_THRESHOLD = SemVer.parse("3.0.0");

    /**
     * Threshold for TypeScript: same strict rebuild policy as Java for schema-line transitions.
     */
    public static final SemVer TYPESCRIPT_REBUILD_THRESHOLD = SemVer.parse("3.0.0");

    /**
     * Threshold for Rust: same strict rebuild policy as Java for schema-line transitions.
     */
    public static final SemVer RUST_REBUILD_THRESHOLD = SemVer.parse("3.0.0");

    private TreeSitterAnalyzerStateMigrator() {}

    static boolean shouldForceRebuild(@Nullable Language language, @Nullable SemVer fromVer, SemVer currentVer) {
        if (language == null) {
            return false;
        }

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
