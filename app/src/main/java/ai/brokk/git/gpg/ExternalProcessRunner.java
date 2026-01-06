package ai.brokk.git.gpg;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.jspecify.annotations.NullMarked;

@NullMarked
class ExternalProcessRunner {
    private static final Logger logger = LogManager.getLogger(ExternalProcessRunner.class);

    private ExternalProcessRunner() {}

    static void run(ProcessBuilder process, InputStream in, ResultHandler stdout, ResultHandler stderr)
            throws IOException, CanceledException {
        String command = process.command().stream().collect(Collectors.joining(" "));
        FS.ExecutionResult result = null;
        int code = 0;

        try {
            logger.debug("Spawning process: {}", command);
            logger.trace("Environment: {}", process.environment());

            result = FS.DETECTED.execute(process, in);
            code = result.getRc();

            logger.trace("stderr:\n{}", toString(result.getStderr()));
            logger.trace("stdout:\n{}", toString(result.getStdout()));
            logger.debug("Spawned process exited with exit code {}", code);

            if (code != 0) {
                if (stderr != null) {
                    stderr.accept(result.getStderr());
                }
                throw new IOException("Process '%s' failed with exit code %d: %s"
                        .formatted(command, code, toString(result.getStderr())));
            }

            stdout.accept(result.getStdout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Process '%s' was interrupted".formatted(command), e);
        } catch (IOException e) {
            logger.debug("Spawned process failed: {}", command, e);

            if (code != 0) {
                throw e;
            }

            if (result != null) {
                throw new IOException(
                        "Process '%s' failed: %s".formatted(command, toString(result.getStderr())), e);
            }

            throw new IOException("Process '%s' failed: %s".formatted(command, e.getMessage()), e);
        } finally {
            if (result != null) {
                if (result.getStderr() != null) {
                    result.getStderr().destroy();
                }

                if (result.getStdout() != null) {
                    result.getStdout().destroy();
                }
            }
        }
    }

    static String toString(TemporaryBuffer b) {
        if (b != null) {
            try {
                return new String(b.toByteArray(4000), SystemReader.getInstance().getDefaultCharset());
            } catch (IOException e) {
                logger.warn("Error reading process buffer", e);
            }
        }

        return "";
    }

    interface ResultHandler {
        void accept(TemporaryBuffer buffer) throws IOException, CanceledException;
    }
}
