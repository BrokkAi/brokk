package ai.brokk.util;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared concurrency utilities that do not depend on module-specific Environment classes.
 */
public final class ConcurrencyUtil {
    private static final Logger logger = LogManager.getLogger(ConcurrencyUtil.class);

    private ConcurrencyUtil() {}

    /**
     * Computes a reasonable upper bound on I/O-bound virtual threads by inspecting
     * available file descriptors (on Unix-like JDKs) and falling back to CPU count.
     * Falls back to a conservative CPU-bounded value when limits are unavailable.
     * You can override the computed value via the system property: -Dbrokk.io.maxConcurrency=N
     */
    public static int computeAdaptiveIoConcurrencyCap() {
        int cpuBound = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

        String prop = System.getProperty("brokk.io.maxConcurrency");
        if (prop != null) {
            try {
                int overridden = Integer.parseInt(prop);
                int cap = Math.max(1, overridden);
                logger.info("Using overridden IO virtual-thread cap from system property: {}", cap);
                return cap;
            } catch (NumberFormatException nfe) {
                logger.warn("Invalid brokk.io.maxConcurrency value '{}'; ignoring override", prop);
            }
        }

        try {
            var osMxBean = ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof UnixOperatingSystemMXBean unix) {
                long max = unix.getMaxFileDescriptorCount();
                long open = unix.getOpenFileDescriptorCount();
                if (max > 0L) {
                    long free = Math.max(0L, max - open);
                    long safety = Math.max(32L, (long) Math.ceil(max * 0.15));
                    long usable = Math.max(0L, free - safety);

                    int byFd = (int) Math.max(8L, Math.min(usable / 2L, 256L));
                    int cap = Math.min(byFd, cpuBound);

                    logger.info(
                            "Adaptive IO cap from FD limits: maxFD={}, openFD={}, freeFD={}, cap={}",
                            max,
                            open,
                            free,
                            cap);
                    return cap;
                }
            }
        } catch (Throwable t) {
            logger.debug("Could not compute Unix FD limits: {}", t.getMessage());
        }

        int fallback = Math.min(cpuBound, 64);
        logger.info("Using fallback IO virtual-thread cap: {}", fallback);
        return fallback;
    }
}
