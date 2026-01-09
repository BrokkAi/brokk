package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.util.BuildToolConventions.BuildSystem;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildToolConventionsTest {

    @Test
    void testDetermineBuildSystem() {
        assertEquals(BuildSystem.MAVEN, BuildToolConventions.determineBuildSystem(List.of("pom.xml", "src")));
        assertEquals(BuildSystem.GRADLE, BuildToolConventions.determineBuildSystem(List.of("build.gradle", "settings.gradle")));
        assertEquals(BuildSystem.GRADLE, BuildToolConventions.determineBuildSystem(List.of("build.gradle.kts")));
        assertEquals(BuildSystem.SBT, BuildToolConventions.determineBuildSystem(List.of("build.sbt", "project")));
        assertEquals(BuildSystem.NPM, BuildToolConventions.determineBuildSystem(List.of("package.json", "node_modules")));
        assertEquals(BuildSystem.CARGO, BuildToolConventions.determineBuildSystem(List.of("Cargo.toml", "src")));
        assertEquals(BuildSystem.CMAKE, BuildToolConventions.determineBuildSystem(List.of("CMakeLists.txt", "main.cpp")));
        assertEquals(BuildSystem.PYTHON, BuildToolConventions.determineBuildSystem(List.of("pyproject.toml")));
        assertEquals(BuildSystem.PYTHON, BuildToolConventions.determineBuildSystem(List.of("setup.py")));
        assertEquals(BuildSystem.PYTHON, BuildToolConventions.determineBuildSystem(List.of("requirements.txt")));
        assertEquals(BuildSystem.BAZEL, BuildToolConventions.determineBuildSystem(List.of("MODULE.bazel")));
        assertEquals(BuildSystem.BAZEL, BuildToolConventions.determineBuildSystem(List.of("WORKSPACE.bazel")));
        assertEquals(BuildSystem.BAZEL, BuildToolConventions.determineBuildSystem(List.of("BUILD.bazel")));
        assertEquals(BuildSystem.UNKNOWN, BuildToolConventions.determineBuildSystem(List.of("README.md", "random.txt")));
        assertEquals(BuildSystem.UNKNOWN, BuildToolConventions.determineBuildSystem(List.of("BUILD"))); // Too ambiguous
        assertEquals(BuildSystem.UNKNOWN, BuildToolConventions.determineBuildSystem(List.of("WORKSPACE"))); // Too ambiguous
    }

    @Test
    void testCaseInsensitivity() {
        assertEquals(BuildSystem.MAVEN, BuildToolConventions.determineBuildSystem(List.of("POM.XML")));
        assertEquals(BuildSystem.GRADLE, BuildToolConventions.determineBuildSystem(List.of("Build.Gradle.KTS")));
        assertEquals(BuildSystem.PYTHON, BuildToolConventions.determineBuildSystem(List.of("PyProject.Toml")));
    }

    @Test
    void testPriority() {
        // Maven > Gradle
        assertEquals(BuildSystem.MAVEN, BuildToolConventions.determineBuildSystem(List.of("pom.xml", "build.gradle")));
        // Gradle > NPM
        assertEquals(BuildSystem.GRADLE, BuildToolConventions.determineBuildSystem(List.of("build.gradle", "package.json")));
        // NPM > Python
        assertEquals(BuildSystem.NPM, BuildToolConventions.determineBuildSystem(List.of("package.json", "pyproject.toml")));
    }

    @Test
    void testGetDefaultExcludes() {
        assertEquals(List.of("target/"), BuildToolConventions.getDefaultExcludes(BuildSystem.MAVEN));
        assertEquals(List.of("build/", ".gradle/"), BuildToolConventions.getDefaultExcludes(BuildSystem.GRADLE));
        assertEquals(List.of("node_modules/", "dist/"), BuildToolConventions.getDefaultExcludes(BuildSystem.NPM));
        assertEquals(List.of(), BuildToolConventions.getDefaultExcludes(BuildSystem.UNKNOWN));
    }
}
