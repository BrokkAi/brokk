package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrokkConfigPathsTest {

    private String originalOsName;
    private String originalUserHome;

    @BeforeEach
    void saveOriginalProperties() {
        // Save original system properties that we might modify
        originalOsName = System.getProperty("os.name");
        originalUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void cleanup() {
        // Restore original system properties
        if (originalOsName != null) {
            System.setProperty("os.name", originalOsName);
        }
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void testWindowsConfigPath() {
        System.setProperty("os.name", "Windows 10");
        System.setProperty("user.home", "C:\\Users\\TestUser");

        // Mock APPDATA environment variable is not possible without native code,
        // so we test the fallback path
        Path result = BrokkConfigPaths.getGlobalConfigDir();

        // Should fall back to ~/AppData/Roaming/Brokk if APPDATA is not set
        assertTrue(
                result.toString().contains("AppData") && result.toString().contains("Brokk"),
                "Expected Windows config path to contain AppData and Brokk, got: " + result);
        assertTrue(result.toString().endsWith("Brokk"), "Expected path to end with 'Brokk' (capital B)");
    }

    @Test
    void testMacOSConfigPath() {
        System.setProperty("os.name", "Mac OS X");
        System.setProperty("user.home", "/Users/testuser");

        Path result = BrokkConfigPaths.getGlobalConfigDir();

        assertEquals(
                Path.of("/Users/testuser/Library/Application Support/Brokk"), result, "Expected macOS config path");
    }

    @Test
    void testLinuxConfigPath() {
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", "/home/testuser");

        Path result = BrokkConfigPaths.getGlobalConfigDir();

        // Without XDG_CONFIG_HOME, should fall back to ~/.config/Brokk
        assertEquals(Path.of("/home/testuser/.config/Brokk"), result, "Expected Linux config path");
    }

    @Test
    void testLinuxConfigPathWithXDG() {
        // Note: We can't easily mock environment variables in Java without native code or test frameworks
        // This test documents the expected behavior when XDG_CONFIG_HOME is set
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", "/home/testuser");

        Path result = BrokkConfigPaths.getGlobalConfigDir();

        // If XDG_CONFIG_HOME environment variable is set, it should be used
        // Otherwise falls back to ~/.config/Brokk
        assertTrue(result.toString().endsWith("Brokk"), "Expected path to end with 'Brokk' (capital B)");
    }

    @Test
    void testConsistentCapitalization() {
        // Ensure all platform paths use capital 'Brokk'
        String[] osNames = {"Windows 10", "Mac OS X", "Linux"};

        for (String osName : osNames) {
            System.setProperty("os.name", osName);
            System.setProperty("user.home", "/test/home");

            Path result = BrokkConfigPaths.getGlobalConfigDir();

            assertTrue(
                    result.toString().endsWith("Brokk"),
                    "Expected '" + osName + "' path to end with 'Brokk' (capital B), got: " + result);
        }
    }

    @Test
    void testLegacyConfigDir() {
        // Legacy dir should always be ~/.config/brokk (lowercase)
        System.setProperty("user.home", "/home/testuser");

        Path result = BrokkConfigPaths.getLegacyConfigDir();

        assertEquals(
                Path.of("/home/testuser/.config/brokk"), result, "Expected legacy config dir with lowercase 'brokk'");
    }

    @Test
    void testLegacyConfigDirDifferentFromGlobalOnLinux() {
        // On Linux, legacy and global config dirs should be different (case-sensitive filesystem)
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", "/home/testuser");

        Path global = BrokkConfigPaths.getGlobalConfigDir();
        Path legacy = BrokkConfigPaths.getLegacyConfigDir();

        assertNotEquals(global, legacy, "Global and legacy config dirs should differ on case-sensitive filesystems");
        assertEquals(Path.of("/home/testuser/.config/Brokk"), global, "Expected global dir with capital 'Brokk'");
        assertEquals(Path.of("/home/testuser/.config/brokk"), legacy, "Expected legacy dir with lowercase 'brokk'");
    }

    @Test
    void testInvalidOverrideParameter() {
        // Test that invalid paths in override parameter don't cause crashes
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", "/home/testuser");

        // Should fall back to platform-specific logic without crashing
        assertDoesNotThrow(() -> {
            Path result = BrokkConfigPaths.getGlobalConfigDir(Optional.of("\0invalid\0path"));
            assertNotNull(result);
        });
    }

    /**
     * Migration tests with real filesystem operations.
     * Uses temporary directories to ensure tests are isolated and don't affect user config.
     */
    @Nested
    class MigrationTests {

        @TempDir
        Path tempDir;

        Path newConfigDir;
        Path legacyConfigDir;

        @BeforeEach
        void setup() {
            // Set up temporary directories for testing
            // Override user.home to point to temp directory for legacy dir resolution
            System.setProperty("user.home", tempDir.toString());

            // New config dir will be passed as Optional parameter to migration methods
            newConfigDir = tempDir.resolve("Brokk");

            // Legacy config dir follows the hardcoded pattern in BrokkConfigPaths.getLegacyConfigDir()
            // which is always ~/.config/brokk
            legacyConfigDir = tempDir.resolve(".config").resolve("brokk");
        }

        @AfterEach
        void cleanup() {
            System.clearProperty("user.home");
        }

        @Test
        void testNoMigrationWhenLegacyDoesNotExist() throws IOException {
            // Given: only new config dir exists (or neither exists)
            assertFalse(Files.exists(legacyConfigDir));

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: no migration performed
            assertFalse(migrated, "Should not migrate when legacy dir doesn't exist");
        }

        @Test
        void testMigrationWithBothDirectoriesExisting() throws IOException {
            // Given: both directories exist, but target directory has no files
            Files.createDirectories(newConfigDir);
            Files.createDirectories(legacyConfigDir);

            // Create a file in legacy directory
            Path legacyFile = legacyConfigDir.resolve("brokk.properties");
            Files.writeString(legacyFile, "test.property=value");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds (file-by-file migration)
            assertTrue(migrated, "Should migrate files even when both directories exist");
            assertTrue(
                    Files.exists(newConfigDir.resolve("brokk.properties")),
                    "Should copy file when it doesn't exist in target");
            assertEquals(
                    "test.property=value",
                    Files.readString(newConfigDir.resolve("brokk.properties")).trim());
        }

        @Test
        void testSuccessfulMigrationOfSingleFile() throws IOException {
            // Given: legacy directory exists with one config file
            Files.createDirectories(legacyConfigDir);
            Path legacyFile = legacyConfigDir.resolve("brokk.properties");

            Properties props = new Properties();
            props.setProperty("test.key", "test.value");
            props.setProperty("another.key", "another.value");
            AtomicWrites.atomicSaveProperties(legacyFile, props, "Test properties");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration successful
            assertTrue(migrated, "Migration should succeed");
            assertTrue(Files.exists(newConfigDir), "New config dir should be created");
            assertTrue(Files.exists(newConfigDir.resolve("brokk.properties")), "File should be migrated");

            // Verify file contents
            Properties migratedProps = new Properties();
            try (var reader = Files.newBufferedReader(newConfigDir.resolve("brokk.properties"))) {
                migratedProps.load(reader);
            }
            assertEquals("test.value", migratedProps.getProperty("test.key"), "Property should be preserved");
            assertEquals("another.value", migratedProps.getProperty("another.key"), "Property should be preserved");
        }

        @Test
        void testSuccessfulMigrationOfMultipleFiles() throws IOException {
            // Given: legacy directory with multiple config files
            Files.createDirectories(legacyConfigDir);

            // Create multiple config files
            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key1=value1");
            Files.writeString(legacyConfigDir.resolve("projects.properties"), "key2=value2");
            Files.writeString(legacyConfigDir.resolve("oom.flag"), "2024-01-01");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: all files migrated
            assertTrue(migrated, "Migration should succeed");
            assertTrue(Files.exists(newConfigDir.resolve("brokk.properties")), "brokk.properties should be migrated");
            assertTrue(
                    Files.exists(newConfigDir.resolve("projects.properties")),
                    "projects.properties should be migrated");
            assertTrue(Files.exists(newConfigDir.resolve("oom.flag")), "oom.flag should be migrated");

            // Verify contents
            assertEquals(
                    "key1=value1",
                    Files.readString(newConfigDir.resolve("brokk.properties")).trim());
            assertEquals(
                    "key2=value2",
                    Files.readString(newConfigDir.resolve("projects.properties"))
                            .trim());
            assertEquals(
                    "2024-01-01",
                    Files.readString(newConfigDir.resolve("oom.flag")).trim());
        }

        @Test
        void testMigrationOnlyMigratesExpectedFiles() throws IOException {
            // Given: legacy directory with both expected and unexpected files
            Files.createDirectories(legacyConfigDir);

            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key1=value1");
            Files.writeString(legacyConfigDir.resolve("unexpected.txt"), "should not migrate");
            Files.writeString(legacyConfigDir.resolve("other.config"), "also should not migrate");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: only expected files migrated
            assertTrue(migrated, "Migration should succeed");
            assertTrue(Files.exists(newConfigDir.resolve("brokk.properties")), "Expected file should be migrated");
            assertFalse(Files.exists(newConfigDir.resolve("unexpected.txt")), "Unexpected file should not be migrated");
            assertFalse(Files.exists(newConfigDir.resolve("other.config")), "Unexpected file should not be migrated");
        }

        @Test
        void testMigrationWithPartialFiles() throws IOException {
            // Given: legacy directory with only some of the expected files
            Files.createDirectories(legacyConfigDir);

            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key1=value1");
            // projects.properties and oom.flag don't exist

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds with available files
            assertTrue(migrated, "Migration should succeed even with partial files");
            assertTrue(Files.exists(newConfigDir.resolve("brokk.properties")), "Available file should be migrated");
            assertFalse(Files.exists(newConfigDir.resolve("projects.properties")), "Missing file should not appear");
            assertFalse(Files.exists(newConfigDir.resolve("oom.flag")), "Missing file should not appear");
        }

        @Test
        void testMigrationCreatesNewDirectoryIfNeeded() throws IOException {
            // Given: legacy directory exists, new directory doesn't
            Files.createDirectories(legacyConfigDir);
            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key=value");

            assertFalse(Files.exists(newConfigDir), "New config dir should not exist yet");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: new directory created
            assertTrue(migrated, "Migration should succeed");
            assertTrue(Files.exists(newConfigDir), "New config dir should be created");
            assertTrue(Files.isDirectory(newConfigDir), "New config dir should be a directory");
        }

        @Test
        void testMigrationIsIdempotent() throws IOException {
            // Given: legacy directory with config file
            Files.createDirectories(legacyConfigDir);
            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key=value");

            // When: attempt migration twice
            boolean firstMigration = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));
            boolean secondMigration = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: first succeeds, second skips (files already exist in target after first migration)
            assertTrue(firstMigration, "First migration should succeed");
            assertFalse(secondMigration, "Second migration should be skipped (file-level idempotency)");
        }

        @Test
        void testMigrationPreservesFilePermissions() throws IOException {
            // Given: legacy directory with a file
            Files.createDirectories(legacyConfigDir);
            Path legacyFile = legacyConfigDir.resolve("brokk.properties");
            Files.writeString(legacyFile, "key=value");

            // Make file readable only (on Unix-like systems)
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                legacyFile.toFile().setReadOnly();
            }

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds and file is accessible
            assertTrue(migrated, "Migration should succeed");
            Path migratedFile = newConfigDir.resolve("brokk.properties");
            assertTrue(Files.exists(migratedFile), "File should be migrated");
            assertTrue(Files.isReadable(migratedFile), "Migrated file should be readable");
        }

        @Test
        void testNoMigrationWithEmptyLegacyDirectory() throws IOException {
            // Given: legacy directory exists but is empty
            Files.createDirectories(legacyConfigDir);

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration returns false (no files to migrate)
            assertFalse(migrated, "Should not migrate when legacy directory is empty");
        }

        @Test
        void testMigrationWithLargePropertyFile() throws IOException {
            // Given: legacy directory with a large properties file
            Files.createDirectories(legacyConfigDir);
            Path legacyFile = legacyConfigDir.resolve("brokk.properties");

            // Create a large properties file
            Properties props = new Properties();
            for (int i = 0; i < 1000; i++) {
                props.setProperty("key" + i, "value" + i + "_".repeat(100)); // Add some bulk
            }
            AtomicWrites.atomicSaveProperties(legacyFile, props, "Large test properties");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds and all properties preserved
            assertTrue(migrated, "Migration should succeed");

            Properties migratedProps = new Properties();
            try (var reader = Files.newBufferedReader(newConfigDir.resolve("brokk.properties"))) {
                migratedProps.load(reader);
            }

            assertEquals(1000, migratedProps.size(), "All properties should be migrated");
            assertEquals(
                    "value500" + "_".repeat(100), migratedProps.getProperty("key500"), "Property content preserved");
        }

        @Test
        void testMigrationDoesNotOverwriteExistingFiles() throws IOException {
            // Given: both legacy and new directories with same file but different content
            Files.createDirectories(legacyConfigDir);
            Files.createDirectories(newConfigDir);

            // Legacy has old value
            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "old.property=old_value");

            // New directory already has newer value
            Files.writeString(newConfigDir.resolve("brokk.properties"), "new.property=new_value");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration returns false (no files copied)
            assertFalse(migrated, "Should not migrate when all files already exist in target");

            // Verify existing file was not overwritten
            String content = Files.readString(newConfigDir.resolve("brokk.properties"));
            assertTrue(content.contains("new.property=new_value"), "Existing file should not be overwritten");
            assertFalse(content.contains("old.property"), "Old content should not be present");
        }

        @Test
        void testPartialMigration() throws IOException {
            // Given: legacy has multiple files, new directory has some of them
            Files.createDirectories(legacyConfigDir);
            Files.createDirectories(newConfigDir);

            // Legacy has all three files
            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "legacy.key1=value1");
            Files.writeString(legacyConfigDir.resolve("projects.properties"), "legacy.key2=value2");
            Files.writeString(legacyConfigDir.resolve("oom.flag"), "2024-01-01");

            // New directory already has projects.properties
            Files.writeString(newConfigDir.resolve("projects.properties"), "existing.key=value");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds, but only migrates files not already in target
            assertTrue(migrated, "Should migrate files that don't exist in target");

            // Verify brokk.properties was migrated
            assertTrue(Files.exists(newConfigDir.resolve("brokk.properties")), "brokk.properties should be migrated");
            assertEquals(
                    "legacy.key1=value1",
                    Files.readString(newConfigDir.resolve("brokk.properties")).trim());

            // Verify oom.flag was migrated
            assertTrue(Files.exists(newConfigDir.resolve("oom.flag")), "oom.flag should be migrated");
            assertEquals(
                    "2024-01-01",
                    Files.readString(newConfigDir.resolve("oom.flag")).trim());

            // Verify projects.properties was NOT overwritten
            String projectsContent = Files.readString(newConfigDir.resolve("projects.properties"));
            assertTrue(
                    projectsContent.contains("existing.key=value"),
                    "Existing projects.properties should not be overwritten");
            assertFalse(
                    projectsContent.contains("legacy.key2"), "Legacy content should not be present in existing file");
        }

        @Test
        void testMigrationWithNewDirExistingButEmpty() throws IOException {
            // Given: new directory exists but is empty, legacy has files
            Files.createDirectories(newConfigDir);
            Files.createDirectories(legacyConfigDir);

            Files.writeString(legacyConfigDir.resolve("brokk.properties"), "key=value");
            Files.writeString(legacyConfigDir.resolve("projects.properties"), "project.key=project_value");

            // When: attempt migration
            boolean migrated = BrokkConfigPaths.attemptMigration(Optional.of(newConfigDir.toString()));

            // Then: migration succeeds, files are copied despite directory existing
            assertTrue(migrated, "Should migrate when target directory exists but files don't");
            assertTrue(
                    Files.exists(newConfigDir.resolve("brokk.properties")),
                    "brokk.properties should be migrated to existing directory");
            assertTrue(
                    Files.exists(newConfigDir.resolve("projects.properties")),
                    "projects.properties should be migrated to existing directory");
        }
    }
}
