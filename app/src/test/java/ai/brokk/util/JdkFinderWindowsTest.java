package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test for issue #1714: JDK discovery fails on Windows with StringIndexOutOfBoundsException
 *
 * <p>This test reproduces the bug where Finder.checkForDistribution() throws
 * StringIndexOutOfBoundsException when processing paths without a file separator.
 */
class JdkFinderWindowsTest {

    @BeforeEach
    void onlyRunOnWindows() {
        // Only run this test on Windows where the bug manifests
        String os = System.getProperty("os.name").toLowerCase();
        assumeTrue(os.contains("win"), "Test only runs on Windows");
    }

    @Test
    @DisplayName("Should handle java.exe path without separator in checkForDistribution")
    void shouldHandlePathWithoutSeparator() throws Exception {
        // This test reproduces the bug by calling checkForDistribution with a path
        // that will result in parentPath without a file separator

        Finder finder = new Finder();
        Set<Distro> distros = new HashSet<>();

        // Get access to the private checkForDistribution method
        Method checkForDistribution = Finder.class.getDeclaredMethod(
                "checkForDistribution", String.class, Set.class, boolean.class);
        checkForDistribution.setAccessible(true);

        // Simulate a problematic path - this could be "java.exe" or a path
        // where replaceAll("bin\\\\java.exe", "") doesn't match
        // For example, a malformed path or edge case like "C:\java.exe"
        String problematicPath = "java.exe"; // No directory component

        // This should throw StringIndexOutOfBoundsException with the current bug
        // because parentPath will be "java.exe" (or empty after replaceAll),
        // lastIndexOf(File.separator) returns -1, and substring(0, -1) throws
        assertThrows(
                Exception.class,
                () -> {
                    try {
                        checkForDistribution.invoke(finder, problematicPath, distros, false);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        // Unwrap the reflection exception
                        throw e.getCause();
                    }
                },
                "Should throw exception when processing path without separator");
    }

    @Test
    @DisplayName("Should handle edge case path that doesn't match bin\\\\java.exe pattern")
    void shouldHandleNonStandardWindowsPath() throws Exception {
        // Another edge case: path exists but doesn't match the expected pattern
        // so replaceAll doesn't remove anything, leaving a path without proper separators

        Finder finder = new Finder();
        Set<Distro> distros = new HashSet<>();

        Method checkForDistribution = Finder.class.getDeclaredMethod(
                "checkForDistribution", String.class, Set.class, boolean.class);
        checkForDistribution.setAccessible(true);

        // A path that exists but doesn't match "bin\\java.exe" pattern
        // This simulates cases where the regex replacement fails
        String edgeCasePath = "C:" + File.separator + "java.exe";

        // This will likely fail with current implementation
        assertThrows(
                Exception.class,
                () -> {
                    try {
                        checkForDistribution.invoke(finder, edgeCasePath, distros, false);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                },
                "Should handle path that doesn't match expected pattern");
    }

    @Test
    @DisplayName("JDK discovery should not crash on Windows")
    void jdkDiscoveryShouldNotCrash() {
        // Higher-level integration test: getDistributions() should not crash
        // even if there are problematic paths in the system

        Finder finder = new Finder();

        // This currently catches the exception and logs it, returning empty set
        // But we want to verify it doesn't crash the application
        assertDoesNotThrow(
                () -> {
                    Set<Distro> distros = finder.getDistributions();
                    assertNotNull(distros, "Should return a set, even if empty");
                },
                "getDistributions() should not throw exceptions");
    }
}