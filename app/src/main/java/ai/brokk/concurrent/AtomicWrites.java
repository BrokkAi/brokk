package ai.brokk.concurrent;

import org.jetbrains.annotations.Blocking;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public class AtomicWrites {

    @FunctionalInterface
    public interface WriterAction {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Overwrites the content of a file with the provided text data.
     *
     * <p>This method writes the new content to a temporary file in the same directory as the target file, then attempts
     * to atomically move the temporary file to the target file location. If the atomic move is not supported by the
     * underlying filesystem, it falls back to a non-atomic move.
     *
     * @param targetPath the path to the target file that will be overwritten.
     * @param content the text content to write.
     * @throws IOException if an I/O error occurs during writing or moving the file.
     */
    @Blocking
    public static void save(Path targetPath, String content) throws IOException {
        save(targetPath, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Overwrites the content of a file with the provided data.
     *
     * <p>This method writes the new content to a temporary file in the same directory as the target file, then attempts
     * to atomically move the temporary file to the target file location. If the atomic move is not supported by the
     * underlying filesystem, it falls back to a non-atomic move.
     *
     * @param targetPath the path to the target file that will be overwritten.
     * @param content the text content to write.
     * @throws IOException if an I/O error occurs during writing or moving the file.
     */
    @Blocking
    public static void save(Path targetPath, byte[] content) throws IOException {
        // Create a temporary file in the same directory as the target file.
        Path tempFile = Files.createTempFile(targetPath.getParent(), "temp-", ".tmp");

        try {
            // Write the content to the temporary file using UTF-8 encoding.
            Files.write(tempFile, content);

            try {
                // Try to atomically move the temporary file to the target location.
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Fall back to a non-atomic move if atomic moves are not supported.
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // If something goes wrong, attempt to delete the temporary file.
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Atomically saves a Properties object to a file.
     *
     * <p>This method serializes the Properties and uses the atomicOverwrite method to safely write the content to the
     * specified file.
     *
     * @param path the path to the target file
     * @param properties the Properties to save
     * @param comment optional comment for the properties file
     * @throws IOException if an I/O error occurs
     */
    @Blocking
    public static void save(Path path, Properties properties, String comment) throws IOException {
        // Serialize the properties to a string
        StringWriter writer = new StringWriter();
        properties.store(writer, comment);
        String content = writer.toString();

        // Atomically write the content to the file
        save(path, content);
    }

    /**
     * Atomically writes data to a file.
     *
     * <p>This method writes the new content to a temporary file in the same directory as the target file, then attempts
     * to atomically move the temporary file to the target file location. If the atomic move is not supported by the
     * underlying filesystem, it falls back to a non-atomic move.
     *
     * @param targetPath the path to the target file that will be overwritten.
     * @param writerAction the action that writes content to the output stream of the temporary file.
     * @throws IOException if an I/O error occurs during writing or moving the file.
     */
    @Blocking
    public static void save(Path targetPath, WriterAction writerAction) throws IOException {
        // Create a temporary file in the same directory as the target file.
        Path tempFile = Files.createTempFile(targetPath.getParent(), "temp-", ".tmp");

        try {
            // Write the content to the temporary file.
            try (var out = Files.newOutputStream(tempFile)) {
                writerAction.write(out);
            }

            try {
                // Try to atomically move the temporary file to the target location.
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Fall back to a non-atomic move if atomic moves are not supported.
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // If something goes wrong, attempt to delete the temporary file.
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
