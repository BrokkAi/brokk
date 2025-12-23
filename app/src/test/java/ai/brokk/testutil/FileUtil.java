package ai.brokk.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileUtil {

    private FileUtil() {}

    /**
     * Zips the contents of the source directory to the zipFile location.
     */
    public static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            try (Stream<Path> stream = Files.walk(sourceDir)) {
                stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                    ZipEntry zipEntry =
                            new ZipEntry(sourceDir.relativize(path).toString().replace('\\', '/'));
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    /**
     * A recursive copy from the source directory to the destination directory.
     */
    public static void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path destinationPath = destination.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(destinationPath);
                    } else {
                        Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Creates an empty file at root/relativePath. Equivalent to the Unix "touch" command.
     */
    public static void createDummyFile(Path root, String relativePath) throws IOException {
        Path filePath = root.resolve(relativePath);
        Files.createDirectories(filePath.getParent());
        Files.createFile(filePath);
    }
}
