package io.github.jbellis.brokk.analyzer.usages;

import io.github.jbellis.brokk.util.Environment;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Centralized environment-driven configuration for usage classification.
 *
 * <p>Controls whether usage relevance is requested/handled as a boolean (yes/no) or as a real-number score. Default is
 * score mode (false).
 */
public final class UsageConfig {
    private static final Logger logger = LogManager.getLogger(UsageConfig.class);

    private static final String ENV_VAR = "BRK_USAGE_BOOL";
    private static final Set<String> TRUE_SET = Set.of("1", "true", "t", "yes", "y", "on");
    private static final Set<String> FALSE_SET = Set.of("0", "false", "f", "no", "n", "off");

    private UsageConfig() {}

    private static boolean computeBooleanMode() {
        // Use Environment to expand env map (aligns with project conventions)
        Map<String, String> env = Environment.expandEnvMap(System.getenv());
        String raw = env.get(ENV_VAR);
        if (raw == null) {
            return false; // default to numeric score mode
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return true; // empty string treated as true
        }
        if (TRUE_SET.contains(v)) {
            return true;
        }
        if (FALSE_SET.contains(v)) {
            return false;
        }
        logger.warn("Unrecognized {}='{}'; defaulting to score mode (false).", ENV_VAR, raw);
        return false;
    }

    // Snapshot at class initialization; env is not expected to change during process lifetime.
    private static final boolean BOOLEAN_MODE = computeBooleanMode();

    public static boolean isBooleanUsageMode() {
        return BOOLEAN_MODE;
    }
}
