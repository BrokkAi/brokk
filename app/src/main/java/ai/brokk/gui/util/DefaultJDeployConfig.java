package ai.brokk.gui.util;

import ai.brokk.util.BrokkConfigPaths;
import java.nio.file.Path;

/**
 * Default implementation of JDeployConfig that uses BrokkConfigPaths to determine the configuration directory location.
 */
public class DefaultJDeployConfig implements JDeployConfig {
    @Override
    public Path getConfigDir() {
        return BrokkConfigPaths.getGlobalConfigDir();
    }
}
