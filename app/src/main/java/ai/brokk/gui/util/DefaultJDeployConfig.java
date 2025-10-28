package ai.brokk.gui.util;

import ai.brokk.util.GlobalUiSettings;
import java.nio.file.Path;

/**
 * Default implementation of JDeployConfig that uses GlobalUiSettings to determine the configuration directory location.
 */
public class DefaultJDeployConfig implements JDeployConfig {
    @Override
    public Path getConfigDir() {
        return GlobalUiSettings.getConfigDir();
    }
}
