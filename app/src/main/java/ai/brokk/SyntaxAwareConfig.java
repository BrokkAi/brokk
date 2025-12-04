package ai.brokk;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central configuration for syntax-aware BRK_* markers.
 *
 * <p>Extensions for which we will advertise and allow syntax-aware SEARCH (BRK_CLASS / BRK_[REPLACE|NEW]_FUNCTION)
 * are controlled by the {@code BRK_SYNTAX_EXTENSIONS} environment variable.
 *
 * <ul>
 *   <li>If the env var is {@code null}, the default set is {@code ["java"]}.</li>
 *   <li>If the env var is present but blank (after trimming), the resulting set is empty,
 *       which completely disables syntax-aware prompts and edits.</li>
 *   <li>Otherwise, the env var should be a comma-separated list of extensions, e.g. {@code "java,kt,scala"}.
 *       Values are case-insensitive and normalized to lower-case.</li>
 * </ul>
 */
public final class SyntaxAwareConfig {

    private static final Logger logger = LogManager.getLogger(SyntaxAwareConfig.class);

    private static final String ENV_VAR = "BRK_SYNTAX_EXTENSIONS";

    /**
     * Normalized, lower-case set of extensions for which syntax-aware BRK markers are enabled.
     */
    private static final Set<String> SYNTAX_AWARE_EXTENSIONS = initSyntaxAwareExtensions();

    private SyntaxAwareConfig() {
        // utility class
    }

    private static Set<String> initSyntaxAwareExtensions() {
        String env = System.getenv(ENV_VAR);
        if (env == null) {
            // Default: Java-only
            return Set.of("java");
        }

        var exts = Arrays.stream(env.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        logger.info("{} override in effect; syntax-aware extensions: {}", ENV_VAR, exts);
        return exts;
    }

    /**
     * @return the configured syntax-aware extensions (lower-case, unmodifiable).
     */
    public static Set<String> syntaxAwareExtensions() {
        return SYNTAX_AWARE_EXTENSIONS;
    }

    /**
     * Returns true if the given extension is enabled for syntax-aware BRK markers
     * based on {@link #syntaxAwareExtensions()}.
     *
     * @param extension file extension without leading dot (e.g. "java", "kt")
     */
    public static boolean isSyntaxAwareExtension(String extension) {
        if (extension.isBlank()) {
            return false;
        }
        var normalized = extension.toLowerCase(Locale.ROOT);
        return SYNTAX_AWARE_EXTENSIONS.contains(normalized);
    }
}
