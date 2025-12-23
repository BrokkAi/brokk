package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JdkSelector} validation methods. Tests JDK path validation across different operating systems
 * and directory structures.
 */
class JdkSelectorTest {

    @TempDir
    Path tempDir;

    private Path validJdkDir;
    private Path jreDir;
    private Path invalidDir;
    private Path macOsJdkBundle;

    @BeforeEach
    void setUp() throws Exception {
        // Create a valid JDK structure
        validJdkDir = tempDir.resolve("valid-jdk");
        Path binDir = validJdkDir.resolve("bin");
        Files.createDirectories(binDir);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String javaExe = isWindows ? "java.exe" : "java";
        String javacExe = isWindows ? "javac.exe" : "javac";

        Files.createFile(binDir.resolve(javaExe));
        Files.createFile(binDir.resolve(javacExe));

        // Set executable permissions on Unix systems
        if (!isWindows) {
            Files.setPosixFilePermissions(binDir.resolve(javaExe), PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(binDir.resolve(javacExe), PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        // Create a JRE structure (only java, no javac)
        jreDir = tempDir.resolve("jre-only");
        Path jreBinDir = jreDir.resolve("bin");
        Files.createDirectories(jreBinDir);
        Files.createFile(jreBinDir.resolve(javaExe));
        if (!isWindows) {
            Files.setPosixFilePermissions(jreBinDir.resolve(javaExe), PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        // Create an invalid directory (no bin subdirectory)
        invalidDir = tempDir.resolve("invalid");
        Files.createDirectories(invalidDir);

        // Create macOS-style JDK bundle structure
        macOsJdkBundle = tempDir.resolve("openjdk-21.jdk");
        Path contentsHome = macOsJdkBundle.resolve("Contents").resolve("Home").resolve("bin");
        Files.createDirectories(contentsHome);
        Files.createFile(contentsHome.resolve(javaExe));
        Files.createFile(contentsHome.resolve(javacExe));
        if (!isWindows) {
            Files.setPosixFilePermissions(contentsHome.resolve(javaExe), PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(contentsHome.resolve(javacExe), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void testValidJdkPath() {
        assertTrue(JdkSelector.isValidJdkPath(validJdkDir));
        assertNull(JdkSelector.validateJdkPath(validJdkDir));
    }

    @Test
    void testValidJdkString() {
        assertTrue(JdkSelector.isValidJdk(validJdkDir.toString()));
    }

    @Test
    void testJreOnlyPath() {
        assertFalse(JdkSelector.isValidJdkPath(jreDir));
        String error = JdkSelector.validateJdkPath(jreDir);
        assertNotNull(error);
        assertTrue(error.contains("JRE (Java Runtime Environment)"));
        assertTrue(error.contains("not javac"));
    }

    @Test
    void testInvalidDirectoryStructure() {
        assertFalse(JdkSelector.isValidJdkPath(invalidDir));
        String error = JdkSelector.validateJdkPath(invalidDir);
        assertNotNull(error);
        assertTrue(error.contains("does not contain a 'bin' subdirectory"));
    }

    @Test
    void testNonExistentPath() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        assertFalse(JdkSelector.isValidJdkPath(nonExistent));
        String error = JdkSelector.validateJdkPath(nonExistent);
        assertNotNull(error);
        assertTrue(error.contains("does not exist"));
    }

    @Test
    void testNullPath() {
        assertFalse(JdkSelector.isValidJdkPath(null));
        String error = JdkSelector.validateJdkPath(null);
        assertNotNull(error);
        assertEquals("JDK path is null", error);
    }

    @Test
    void testNullStringPath() {
        assertFalse(JdkSelector.isValidJdk(null));
        assertFalse(JdkSelector.isValidJdk(""));
        assertFalse(JdkSelector.isValidJdk("   "));
    }

    @Test
    void testMacOsContentsHomeStructure() {
        // Test that macOS .jdk bundles are handled correctly
        assertTrue(JdkSelector.isValidJdkPath(macOsJdkBundle));
        assertNull(JdkSelector.validateJdkPath(macOsJdkBundle));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testNonExecutableFiles() throws Exception {
        // Create a JDK structure with non-executable files
        Path nonExecJdk = tempDir.resolve("non-executable-jdk");
        Path binDir = nonExecJdk.resolve("bin");
        Files.createDirectories(binDir);

        Files.createFile(binDir.resolve("java"));
        Files.createFile(binDir.resolve("javac"));

        // Set files as non-executable
        Files.setPosixFilePermissions(binDir.resolve("java"), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(binDir.resolve("javac"), PosixFilePermissions.fromString("rw-r--r--"));

        assertFalse(JdkSelector.isValidJdkPath(nonExecJdk));
        String error = JdkSelector.validateJdkPath(nonExecJdk);
        assertNotNull(error);
        assertTrue(error.contains("does not contain java or javac executables"));
    }

    @Test
    void testPartialJdkInstallation() throws Exception {
        // Create a directory with only javac, no java
        Path partialJdk = tempDir.resolve("partial-jdk");
        Path binDir = partialJdk.resolve("bin");
        Files.createDirectories(binDir);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String javacExe = isWindows ? "javac.exe" : "javac";

        Files.createFile(binDir.resolve(javacExe));
        if (!isWindows) {
            Files.setPosixFilePermissions(binDir.resolve(javacExe), PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertFalse(JdkSelector.isValidJdkPath(partialJdk));
        String error = JdkSelector.validateJdkPath(partialJdk);
        assertNotNull(error);
        assertTrue(error.contains("contains javac but not java"));
        assertTrue(error.contains("incomplete JDK installation"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testWindowsExecutableExtensions() throws Exception {
        // On Windows, test that .exe extensions are required
        Path winJdk = tempDir.resolve("windows-jdk");
        Path binDir = winJdk.resolve("bin");
        Files.createDirectories(binDir);

        // Create files without .exe extensions (should be invalid)
        Files.createFile(binDir.resolve("java"));
        Files.createFile(binDir.resolve("javac"));

        assertFalse(JdkSelector.isValidJdkPath(winJdk));

        // Now add proper .exe files
        Files.createFile(binDir.resolve("java.exe"));
        Files.createFile(binDir.resolve("javac.exe"));

        assertTrue(JdkSelector.isValidJdkPath(winJdk));
    }

    @Test
    void testFileInsteadOfDirectory() throws Exception {
        // Test validation when path points to a file instead of directory
        Path file = tempDir.resolve("not-a-directory.txt");
        Files.createFile(file);

        assertFalse(JdkSelector.isValidJdkPath(file));
        String error = JdkSelector.validateJdkPath(file);
        assertNotNull(error);
        assertTrue(error.contains("is not a directory"));
    }
}
