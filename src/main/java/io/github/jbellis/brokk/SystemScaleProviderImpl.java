package io.github.jbellis.brokk;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/* package-private */ final class SystemScaleProviderImpl implements SystemScaleProvider {
    private static final Logger logger = LogManager.getLogger(SystemScaleProviderImpl.class);

    @Override
    public @Nullable Double getGraphicsConfigScale() {
        try {
            var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            var gd = ge.getDefaultScreenDevice();
            if (gd != null) {
                var gc = gd.getDefaultConfiguration();
                if (gc != null) {
                    AffineTransform tx = gc.getDefaultTransform();
                    double scaleX = tx.getScaleX();
                    double scaleY = tx.getScaleY();
                    if (scaleX > 0.0 && Math.abs(scaleX - scaleY) < 1e-6) {
                        logger.debug("GraphicsConfiguration-derived scale: {}", scaleX);
                        return scaleX;
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("GraphicsConfiguration provider failed: {}", t.toString());
        }
        return null;
    }

    @Override
    public @Nullable Integer getToolkitDpi() {
        try {
            int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
            return dpi > 0 ? Integer.valueOf(dpi) : null;
        } catch (Throwable t) {
            logger.debug("Toolkit DPI provider failed: {}", t.toString());
            return null;
        }
    }

    @Override
    public @Nullable List<String> runCommand(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var proc = pb.start();
            boolean finished = proc.waitFor(1, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                logger.warn("Command timed out: {}", String.join(" ", command));
                return null;
            }
            var lines = new ArrayList<String>();
            try (var is = proc.getInputStream();
                    var isr = new InputStreamReader(is, UTF_8);
                    var br = new BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (proc.exitValue() != 0) {
                logger.debug("Command exited with non-zero status {}: {}", proc.exitValue(), String.join(" ", command));
            }
            return lines;
        } catch (IOException | InterruptedException e) {
            logger.debug("Failed running command '{}': {}", String.join(" ", command), e.toString());
            return null;
        }
    }
}
