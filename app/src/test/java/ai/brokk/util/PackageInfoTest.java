package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

/** Ensures all non-test packages are annotated with @NullMarked. */
class PackageInfoTest {

    @Test
    void allPackagesShouldBeNullMarked() throws IOException {
        var projectRoot = getProjectRoot();

        List<Path> javaSrcDirs;
        try (var walk = Files.walk(projectRoot)) {
            javaSrcDirs = walk.filter(p -> p.toFile().isDirectory() && p.endsWith("app/src/main/java"))
                    .collect(Collectors.toList());
        }

        assertFalse(
                javaSrcDirs.isEmpty(), "No 'app/src/main/java' directories found. Wrong project root? " + projectRoot);

        var packagesWithoutAnnotation = javaSrcDirs.stream()
                .flatMap(srcDir -> findUnannotatedPackagesIn(srcDir, projectRoot))
                .collect(Collectors.toList());

        if (!packagesWithoutAnnotation.isEmpty()) {
            fail(
                    "The following packages are missing a package-info.java with `@org.jspecify.annotations.NullMarked` annotation:\n"
                            + String.join("\n", packagesWithoutAnnotation));
        }
    }

    private Stream<String> findUnannotatedPackagesIn(Path srcDir, Path projectRoot) {
        try (var walk = Files.walk(srcDir)) {
            return walk
                    .filter(p -> p.toFile().isDirectory())
                    .filter(this::isJavaPackage)
                    .filter(packageDir -> isNotExcluded(packageDir, srcDir))
                    .filter(packageDir -> !hasNullMarkedAnnotation(packageDir))
                    .map(packageDir -> {
                        var moduleName = projectRoot.relativize(srcDir).getName(0);
                        var packagePath =
                                srcDir.relativize(packageDir).toString().replace(File.separator, ".");
                        return moduleName + ": " + packagePath;
                    })
                    .collect(Collectors.toList())
                    .stream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isNotExcluded(Path packageDir, Path srcDir) {
        var relativePath = srcDir.relativize(packageDir);

        var packagePath = relativePath.toString().replace(File.separator, ".");
        if (packagePath.isEmpty()) {
            return true;
        }

        // Packages that are not yet @NullMarked.
        var excludedPrefixes = List.of("dev", "eu");

        return excludedPrefixes.stream().noneMatch(packagePath::startsWith);
    }

    private Path getProjectRoot() {
        var currentDir = Paths.get("").toAbsolutePath();
        var projectRoot = currentDir;
        while (projectRoot.getParent() != null
                && Files.exists(projectRoot.getParent().resolve("settings.gradle.kts"))) {
            projectRoot = projectRoot.getParent();
        }
        return projectRoot;
    }

    private boolean isJavaPackage(Path dir) {
        // A directory is considered a package if it contains any .java files.
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasNullMarkedAnnotation(Path packageDir) {
        var packageInfoFile = packageDir.resolve("package-info.java");
        if (!Files.exists(packageInfoFile)) {
            return false;
        }

        try {
            var content = Files.readString(packageInfoFile);
            var nullMarkedAnnotation = "@" + NullMarked.class.getName();
            var nullMarkedSimple = "@" + NullMarked.class.getSimpleName();

            return content.contains(nullMarkedAnnotation) || content.contains(nullMarkedSimple);
        } catch (IOException e) {
            fail("Could not read " + packageInfoFile, e);
            return false; // unreachable
        }
    }
}
