package ai.brokk.util;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class EnvironmentJava {
    private static final Logger logger = LogManager.getLogger(EnvironmentJava.class);

    public static final String JAVA_HOME_SENTINEL = "$JAVA_HOME";

    /**
     * Detect a JDK to use for this project. - If JAVA_HOME is set and points to a JDK (has bin/javac), return the
     * sentinel so it stays dynamic. - Otherwise, choose the most suitable JDK using Finder (by latest version/release
     * date) and return its path. - If nothing is found, fall back to the sentinel.
     */
    public static @Nullable String detectJdk() {
        var env = System.getenv("JAVA_HOME");
        try {
            if (env != null && !env.isBlank()) {
                var home = Path.of(env);
                if (isJdkHome(home)) {
                    return JAVA_HOME_SENTINEL;
                }
            }
        } catch (Exception e) {
            logger.debug("Invalid JAVA_HOME '{}': {}", env, e.getMessage());
        }

        // Fallback: use Finder to locate installed JDKs and pick the most suitable one
        try {
            var finder = new Finder();
            var distros = finder.getDistributions();
            if (distros != null && !distros.isEmpty()) {
                Comparator<Distro> distroComparator = Comparator.comparing(Distro::getVersionNumber)
                        .thenComparing(
                                d -> d.getReleaseDate().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()));
                var best = distros.stream().max(distroComparator).orElseThrow();
                var p = best.getPath();
                if (p != null && !p.isBlank()) return p;
                var loc = best.getLocation();
                if (loc != null && !loc.isBlank()) return loc;
            }
        } catch (Throwable t) {
            logger.warn("Failed to detect JDK via Finder", t);
        }

        // user will need to download something, leave it alone in the meantime
        return null;
    }

    private static boolean isJdkHome(Path home) {
        var bin = home.resolve("bin");
        var javac = bin.resolve(Environment.exeName("javac"));
        return Files.isRegularFile(javac);
    }
}
