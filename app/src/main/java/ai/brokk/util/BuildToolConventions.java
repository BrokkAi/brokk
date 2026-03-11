package ai.brokk.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BuildToolConventions {

    public enum BuildSystem {
        MAVEN,
        GRADLE,
        SBT,
        NPM,
        CARGO,
        BAZEL,
        CMAKE,
        POETRY,
        PYTHON,
        RUBY,
        PHP,
        DOTNET,
        UNKNOWN
    }

    /**
     * Determines the build system based on files present in the project root.
     * Uses first-match precedence: Maven → Gradle → SBT → NPM → Cargo → CMake → Ruby → PHP → DOTNET → Poetry → Python → Bazel.
     * In monorepos with multiple build files, the first matching system wins.
     */
    public static BuildSystem determineBuildSystem(List<String> rootFilenames) {
        Set<String> names = rootFilenames.stream().map(String::toLowerCase).collect(Collectors.toSet());

        if (names.contains("pom.xml")) {
            return BuildSystem.MAVEN;
        }
        if (names.contains("build.gradle") || names.contains("build.gradle.kts")) {
            return BuildSystem.GRADLE;
        }
        if (names.contains("build.sbt")) {
            return BuildSystem.SBT;
        }
        if (names.contains("package.json")) { // NPM, Yarn, PNPM all use package.json
            return BuildSystem.NPM;
        }
        if (names.contains("cargo.toml")) {
            return BuildSystem.CARGO;
        }
        if (names.contains("cmakelists.txt")) {
            return BuildSystem.CMAKE;
        }
        if (names.contains("gemfile")) {
            return BuildSystem.RUBY;
        }
        if (names.contains("composer.json")) {
            return BuildSystem.PHP;
        }
        if (names.stream().anyMatch(n -> n.endsWith(".sln") || n.endsWith(".csproj"))) {
            return BuildSystem.DOTNET;
        }
        if (names.contains("poetry.lock")) {
            return BuildSystem.POETRY;
        }
        if (names.contains("setup.py") || names.contains("pyproject.toml") || names.contains("requirements.txt")) {
            return BuildSystem.PYTHON;
        }
        if (names.contains("workspace.bazel") || names.contains("module.bazel") || names.contains("build.bazel")) {
            return BuildSystem.BAZEL;
        }

        return BuildSystem.UNKNOWN;
    }

    public static List<String> getDefaultExcludes(BuildSystem system) {
        return switch (system) {
            case MAVEN -> List.of("target/");
            case GRADLE -> List.of("build/", ".gradle/");
            case SBT -> List.of("target/");
            case NPM -> List.of("node_modules/", "dist/");
            case CARGO -> List.of("target/");
            case BAZEL -> List.of("bazel-out/");
            case CMAKE -> List.of("build/", "out/");
            case POETRY, PYTHON ->
                List.of("__pycache__/", ".pytest_cache/", ".mypy_cache/", ".tox/", ".egg-info/", "build/", "dist/");
            case RUBY -> List.of("vendor/bundle/", ".bundle/", "coverage/");
            case PHP -> List.of("vendor/", "cache/");
            case DOTNET -> List.of("bin/", "obj/", "packages/");
            default -> List.of();
        };
    }

    private static final Set<String> IDE_AND_VENV_DIRECTORIES = Set.of(
            ".venv", "venv", ".idea", ".vscode");

    /**
     * Returns directory names that should be skipped during file traversal when
     * git-aware filtering is unavailable. This is the union of all build-system
     * excludes (with trailing slashes stripped) plus IDE and virtual environment directories.
     */
    public static Set<String> getAllDefaultExcludedDirectories() {
        var result = new HashSet<>(IDE_AND_VENV_DIRECTORIES);
        for (var system : BuildSystem.values()) {
            for (var exclude : getDefaultExcludes(system)) {
                result.add(exclude.endsWith("/") ? exclude.substring(0, exclude.length() - 1) : exclude);
            }
        }
        return Set.copyOf(result);
    }
}
