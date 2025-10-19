package io.github.jbellis.brokk.gui.util;

import java.nio.file.Path;

/**
 * Interface for providing JDeploy configuration directory location. This abstraction allows for easier testing by
 * enabling dependency injection.
 */
public interface JDeployConfig {
    /**
     * Returns the directory where JDeploy configuration files should be stored.
     *
     * @return the configuration directory path
     */
    Path getConfigDir();
}
