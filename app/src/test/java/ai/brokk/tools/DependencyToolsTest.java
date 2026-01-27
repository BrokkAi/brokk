package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.MavenArtifactFetcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DependencyToolsTest {

    @TempDir
    Path tempDir;

    // ========== isSupported Tests ==========

    @Test
    void isSupported_JavaProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.JAVA);
        assertTrue(DependencyTools.isSupported(project));
    }

    @Test
    void isSupported_PythonProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.PYTHON);
        assertTrue(DependencyTools.isSupported(project));
    }

    @Test
    void isSupported_RustProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.RUST);
        assertTrue(DependencyTools.isSupported(project));
    }

    @Test
    void isSupported_TypeScriptProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        assertTrue(DependencyTools.isSupported(project));
    }

    @Test
    void isSupported_JavaScriptProject_ReturnsTrue() {
        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        assertTrue(DependencyTools.isSupported(project));
    }

    @Test
    void isSupported_GoProject_ReturnsFalse() {
        var project = new TestProject(tempDir, Languages.GO);
        assertFalse(DependencyTools.isSupported(project));
    }

    // ========== importDependency Routing Tests ==========

    @Test
    void importDependency_EmptySpec_ReturnsError() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("  ");
        assertTrue(result.contains("Invalid dependency specification"));
    }

    @Test
    void importDependency_MavenCoords_RoutesToJava() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var recordedCoords = new AtomicReference<String>();
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<Path> fetch(String coordinates, String classifier) {
                recordedCoords.set(coordinates);
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        tools.importDependency("org.example:lib:1.2.3");

        assertEquals("org.example:lib:1.2.3", recordedCoords.get());
    }

    @Test
    void importDependency_PythonPackage_RoutesToPython() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.PYTHON));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("requests");
        assertTrue(
                result.contains("No Python packages found") || result.contains("virtual environment"),
                "Should route to Python importer");
    }

    @Test
    void importDependency_RustCrate_RoutesToRust() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.RUST));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("serde");
        assertTrue(
                result.contains("No Rust crates found") || result.contains("Cargo"), "Should route to Rust importer");
    }

    @Test
    void importDependency_NpmPackage_RoutesToNode() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.TYPESCRIPT));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("lodash");
        assertTrue(
                result.contains("No npm packages found") || result.contains("node_modules"),
                "Should route to Node importer");
    }

    @Test
    void importDependency_JavaWithoutColons_GivesHint() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("guava");
        assertTrue(
                result.contains("Maven coordinates format") || result.contains("groupId:artifactId"),
                "Should hint about Maven format");
    }

    // ========== Java/Maven Tests ==========

    @Test
    void importDependency_Java_InvalidCoordinates_ReturnsErrorMessage() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var tools = new DependencyTools(cm);

        String[] invalidInputs = {"a:", "too:many:parts:here:extra"};

        for (String input : invalidInputs) {
            String result = tools.importDependency(input);
            assertTrue(
                    result.contains("Invalid") || result.contains("coordinates"),
                    "Should fail for: " + input + ", got: " + result);
        }
    }

    @Test
    void importDependency_Java_ThreeParts_SkipsVersionResolution() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var recordedCoords = new AtomicReference<String>();
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                throw new AssertionError("Should not resolve version when provided");
            }

            @Override
            public Optional<Path> fetch(String coordinates, String classifier) {
                recordedCoords.set(coordinates);
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importDependency("org.example:lib:1.2.3");

        assertEquals("org.example:lib:1.2.3", recordedCoords.get());
        assertTrue(result.contains("Could not find artifact"));
    }

    @Test
    void importDependency_Java_TwoParts_TriggersVersionResolution() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var recordedCoords = new AtomicReference<String>();
        var resolveCount = new AtomicInteger(0);
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                resolveCount.incrementAndGet();
                if ("org.example".equals(groupId) && "lib".equals(artifactId)) {
                    return Optional.of("2.0.0");
                }
                return Optional.empty();
            }

            @Override
            public Optional<Path> fetch(String coordinates, String classifier) {
                recordedCoords.set(coordinates);
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importDependency("org.example:lib");

        assertEquals(1, resolveCount.get(), "Should resolve version exactly once");
        assertEquals("org.example:lib:2.0.0", recordedCoords.get());
    }

    @Test
    void importDependency_Java_VersionResolutionFails_ReturnsErrorMessage() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importDependency("org.example:unknown");

        assertTrue(result.contains("Could not resolve latest version"));
    }

    @Test
    void importDependency_Java_LocalCacheHasPriority() throws Exception {
        var m2Repo = tempDir.resolve(".m2/repository/org/localcache/testlib");
        var versionDir = m2Repo.resolve("1.5.0");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("testlib-1.5.0.jar"), "dummy");

        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
            var mavenResolveCalled = new AtomicInteger(0);
            var mavenFetchCalled = new AtomicInteger(0);
            var mockFetcher = new MavenArtifactFetcher() {
                @Override
                public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                    mavenResolveCalled.incrementAndGet();
                    return Optional.of("99.0.0");
                }

                @Override
                public Optional<Path> fetch(String coordinates, String classifier) {
                    mavenFetchCalled.incrementAndGet();
                    return Optional.empty();
                }
            };

            var tools = new DependencyTools(cm, mockFetcher);
            tools.importDependency("org.localcache:testlib");

            assertEquals(0, mavenResolveCalled.get(), "Should not call Maven Central for version when local exists");
            assertEquals(0, mavenFetchCalled.get(), "Should not call Maven Central for fetch when local JAR exists");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    // ========== Python Tests ==========

    @Test
    void importDependency_Python_NoVenv_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("requests");
        assertTrue(result.contains("No Python packages found"));
    }

    @Test
    void importDependency_Python_PackageNotFound() throws Exception {
        var venvDir = tempDir.resolve(".venv");
        var libDir = venvDir.resolve("lib/python3.11/site-packages");
        Files.createDirectories(libDir);

        var distInfo = libDir.resolve("dummy_pkg-1.0.0.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("METADATA"), "Name: dummy_pkg\nVersion: 1.0.0\n");
        Files.writeString(distInfo.resolve("RECORD"), "dummy_pkg/__init__.py,sha256=abc,0\n");

        var pkgDir = libDir.resolve("dummy_pkg");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("__init__.py"), "# dummy package");

        var project = new TestProject(tempDir, Languages.PYTHON);
        var cm = new TestContextManager(project);
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("requests");
        assertTrue(result.contains("not found") || result.contains("pip install"));
    }

    // ========== Rust Tests ==========

    @Test
    void importDependency_Rust_NoCargo_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.RUST);
        var cm = new TestContextManager(project);
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("serde");
        assertTrue(result.contains("No Rust crates found") || result.contains("not found"));
    }

    // ========== Node.js Tests ==========

    @Test
    void importDependency_Node_NoNodeModules_ReturnsErrorMessage() throws InterruptedException {
        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("lodash");
        assertTrue(result.contains("No npm packages found"));
    }

    @Test
    void importDependency_Node_PackageNotFound() throws Exception {
        var nodeModules = tempDir.resolve("node_modules");
        var pkgDir = nodeModules.resolve("express");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"), "{\"name\": \"express\", \"version\": \"4.18.2\"}");
        Files.writeString(pkgDir.resolve("index.js"), "module.exports = {};");

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var cm = new TestContextManager(project);
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("lodash");
        assertTrue(result.contains("not found") || result.contains("npm install"));
    }

    // ========== Integration Tests ==========

    @Disabled("Slow integration test - downloads from Maven Central")
    @Test
    void importDependency_Java_RealLibrary_DownloadsAndDecompiles() throws Exception {
        var cm = new TestContextManager(new TestProject(tempDir, Languages.JAVA));
        var tools = new DependencyTools(cm);

        String result = tools.importDependency("org.slf4j:slf4j-api:2.0.9");

        assertTrue(result.contains("Successfully imported"));
        assertTrue(result.contains("slf4j-api"));
        assertTrue(result.contains(".brokk") && result.contains("dependencies"));

        var depsDir = tempDir.resolve(".brokk/dependencies");
        assertTrue(Files.exists(depsDir));

        try (var dirs = Files.list(depsDir)) {
            var artifactDir = dirs.filter(p -> p.getFileName().toString().contains("slf4j"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No slf4j directory found"));

            try (var walk = Files.walk(artifactDir)) {
                long javaFiles =
                        walk.filter(p -> p.toString().endsWith(".java")).count();
                assertTrue(javaFiles > 0, "Should have extracted Java files");
            }
        }
    }
}
