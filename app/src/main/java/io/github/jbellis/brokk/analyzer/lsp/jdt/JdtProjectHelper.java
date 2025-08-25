package io.github.jbellis.brokk.analyzer.lsp.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdtProjectHelper {

    private static final Logger logger = LoggerFactory.getLogger(JdtProjectHelper.class.getName());

    /**
     * Identifies if a configuration file from a build tool supported by JDT is in place. If no build file is available,
     * attempts to generate a temporary Eclipse one.
     *
     * @param projectPath the absolute project location.
     * @return true if the project contains a Eclipse configuration files, false if otherwise.
     */
    public static boolean ensureProjectConfiguration(Path projectPath) throws IOException {
        // If a JDT supported build file already exists, we're good.
        if (Files.exists(projectPath.resolve(".project"))) {
            return true;
        } else if (Files.exists(projectPath.resolve("pom.xml"))
                || Files.exists(projectPath.resolve("build.gradle"))
                || Files.exists(projectPath.resolve("build.gradle.kts"))
                || Files.exists(projectPath.resolve("build.xml"))) {
            return false;
        }

        logger.debug("No standard build file found. Generating default Eclipse configuration.");
        return generateDefaultEclipseFiles(projectPath);
    }

    private static Optional<String> findSourcePath(Path dir) {
        if (Files.isDirectory(dir.resolve("src/main/java"))) {
            return Optional.of("src/main/java");
        }
        if (Files.isDirectory(dir.resolve("src/java"))) {
            return Optional.of("src/java");
        }
        if (Files.isDirectory(dir.resolve("src"))) {
            return Optional.of("src");
        }
        return Optional.empty();
    }

    /**
     * Checks for a build file (pom.xml, build.gradle) or a .classpath file. If none exist, it generates a default
     * .classpath file by guessing the source directory. This is absolutely required for the LSP server to import code.
     *
     * @param projectPath The root of the project workspace.
     * @throws IOException If file I/O fails.
     */
    public static boolean generateDefaultEclipseFiles(Path projectPath) throws IOException {
        // Guess the common source directory path. This is not multi-module
        final String sourcePath = findSourcePath(projectPath).orElseGet(() -> {
            // As a last resort, assume sources are in the root.
            logger.warn(
                    "Could not find a 'src' directory for {}. Defaulting source path to project root.", projectPath);
            return ".";
        });

        // Dynamically determine the JRE version from the current runtime.
        final int javaVersion = Runtime.version().feature();
        String classpathContent = generateClassPathContent(javaVersion, sourcePath);
        String projectFileContent =
                generateProjectFileContent(projectPath.getFileName().toString());

        // Write the new .classpath and .project file.
        final var projectFile = projectPath.resolve(".project");
        final var classPathFile = projectPath.resolve(".classpath");
        try {
            Files.writeString(
                    projectFile, projectFileContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(
                    classPathFile, classpathContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } finally {
            projectFile.toFile().deleteOnExit();
            classPathFile.toFile().deleteOnExit();
        }
        logger.debug("Generated default .classpath for {} with source path '{}'", projectPath, sourcePath);
        return true; // exceptions would prevent this return
    }

    private static @NotNull String generateClassPathContent(int javaVersion, String sourcePath) {
        final String jreVersionString = (javaVersion >= 9) ? "JavaSE-" + javaVersion : "JavaSE-1." + javaVersion;
        final String jreContainerPath =
                "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/"
                        + jreVersionString;

        // Generate the .classpath content with the dynamic JRE path.
        return String.format(
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <classpath>
                            <classpathentry kind="src" path="%s"/>
                            <classpathentry kind="con" path="%s"/>
                        </classpath>
                        """,
                sourcePath, jreContainerPath);
    }

    private static @NotNull String generateProjectFileContent(String projectName) {
        return String.format(
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <projectDescription>
                            <name>%s</name>
                            <comment></comment>
                            <projects></projects>
                            <buildSpec>
                                <buildCommand>
                                    <name>org.eclipse.jdt.core.javabuilder</name>
                                    <arguments></arguments>
                                </buildCommand>
                            </buildSpec>
                            <natures>
                                <nature>org.eclipse.jdt.core.javanature</nature>
                            </natures>
                        </projectDescription>
                        """,
                projectName);
    }
}
