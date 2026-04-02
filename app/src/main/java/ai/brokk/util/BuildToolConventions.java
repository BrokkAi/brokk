package ai.brokk.util;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BuildToolConventions {

    public enum BuildSystem {
        MAVEN(Set.of("pom.xml"), Set.of()),
        GRADLE(Set.of("build.gradle", "build.gradle.kts"), Set.of()),
        SBT(Set.of("build.sbt"), Set.of()),
        NPM(Set.of("package.json"), Set.of()),
        CARGO(Set.of("cargo.toml"), Set.of()),
        CMAKE(Set.of("cmakelists.txt"), Set.of()),
        RUBY(Set.of("gemfile"), Set.of()),
        PHP(Set.of("composer.json"), Set.of()),
        DOTNET(Set.of(), Set.of(".sln", ".csproj")),
        POETRY(Set.of("poetry.lock"), Set.of()),
        PYTHON(Set.of("setup.py", "pyproject.toml", "requirements.txt"), Set.of()),
        BAZEL(Set.of("workspace.bazel", "module.bazel", "build.bazel"), Set.of()),
        GO(Set.of("go.mod"), Set.of()),
        ANT(Set.of("build.xml"), Set.of()),
        MAKE(Set.of("makefile"), Set.of()),
        UNKNOWN(Set.of(), Set.of());

        private final ImmutableSet<String> markerFiles;
        private final ImmutableSet<String> markerSuffixes;

        BuildSystem(Set<String> markerFiles, Set<String> markerSuffixes) {
            this.markerFiles = ImmutableSet.copyOf(markerFiles);
            this.markerSuffixes = ImmutableSet.copyOf(markerSuffixes);
        }

        private boolean matches(String filename) {
            String lower = filename.toLowerCase(Locale.ROOT);
            return markerFiles.contains(lower) || markerSuffixes.stream().anyMatch(lower::endsWith);
        }
    }

    /**
     * Determines the build system based on files present in the project root.
     * Uses first-match precedence as defined by the order of Enum values.
     * In monorepos with multiple build files, the first matching system wins.
     */
    public static BuildSystem determineBuildSystem(List<String> rootFilenames) {
        for (BuildSystem system : BuildSystem.values()) {
            if (system == BuildSystem.UNKNOWN) continue;
            if (rootFilenames.stream().anyMatch(system::matches)) {
                return system;
            }
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * Returns true if the filename is recognized as a primary build configuration file
     * or project file for any supported build system.
     */
    public static boolean isBuildFile(String filename) {
        for (BuildSystem system : BuildSystem.values()) {
            if (system != BuildSystem.UNKNOWN && system.matches(filename)) {
                return true;
            }
        }
        return false;
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
            case GO -> List.of("vendor/");
            case ANT -> List.of("dist/", "build/", "bin/");
            case MAKE -> List.of("out/", "build/");
            default -> List.of();
        };
    }
}
