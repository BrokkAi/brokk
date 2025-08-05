package io.github.jbellis.brokk.analyzer.lsp.jdt;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class JdtProjectHelper {

    private static final Logger logger = LoggerFactory.getLogger(JdtProjectHelper.class.getName());

    /**
     * Aims to establish Eclipse project build files, i.e., `.project` and `.classpath` files, regardless of the build 
     * tool used. This is more reliable than letting the server importing a project based on the available build tool,
     * but this may be used as a fallback.
     *
     * @param projectPath the absolute project location.
     * @throws IOException if generating Eclipse project files failed.
     * @return true if the building Eclipse files was successful, false if otherwise and a fallback should be used.
     */
    public static boolean ensureProjectConfiguration(Path projectPath) throws IOException {
        // If a .project file already exists, we're good.
        if (Files.exists(projectPath.resolve(".project"))) {
            return true;
        }

        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return configureMavenProject(projectPath);
        } else if (Files.exists(projectPath.resolve("build.gradle")) || Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return configureGradleProject(projectPath);
        } else {
            logger.info("No standard build file found. Generating default Eclipse configuration.");
            return generateDefaultEclipseFiles(projectPath);
        }
    }

    private static boolean configureMavenProject(Path projectPath) throws IOException {
        logger.debug("Maven pom.xml found. Attempting to generate Eclipse configuration.");
        String wrapper = isWindows() ? "mvnw.cmd" : "mvnw";
        Path wrapperPath = projectPath.resolve(wrapper).toAbsolutePath();

        // Try to use the wrapper first.
        if (Files.isExecutable(wrapperPath)) {
            try {
                runCommand(projectPath, wrapperPath.toString(), "eclipse:eclipse");
                return true; // Success
            } catch (Exception e) {
                logger.warn("Maven wrapper failed to execute, falling back to system 'mvn'.", e);
            }
        }

        // If wrapper fails or doesn't exist, try the system command.
        try {
            logger.debug("Maven wrapper not found or failed. Trying system 'mvn'...");
            return runCommand(projectPath, "mvn", "eclipse:eclipse");
        } catch (Exception e) {
            logger.error("System 'mvn' command failed. Falling back to default file generation.", e);
            return generateDefaultEclipseFiles(projectPath);
        }
    }

    private static boolean configureGradleProject(Path projectPath) throws IOException {
        logger.debug("Gradle build.gradle found. Attempting to generate Eclipse configuration.");
        String wrapper = isWindows() ? "gradlew.bat" : "gradlew";
        Path wrapperPath = projectPath.resolve(wrapper).toAbsolutePath();

        // Try to use the wrapper first.
        if (Files.isExecutable(wrapperPath)) {
            try {
                runCommand(projectPath, wrapperPath.toString(), "eclipse");
                return true; // Success
            } catch (Exception e) {
                logger.warn("Gradle wrapper failed to execute, falling back to system 'gradle'.", e);
            }
        }

        // If wrapper fails or doesn't exist, try the system command.
        try {
            logger.debug("Gradle wrapper not found or failed. Trying system 'gradle'...");
            return runCommand(projectPath, "gradle", "eclipse");
        } catch (Exception e) {
            logger.error("System 'gradle' command failed. Falling back to default file generation.", e);
            return generateDefaultEclipseFiles(projectPath);
        }
    }

    private static boolean runCommand(Path workingDir, String... command) throws IOException, InterruptedException {
        final var pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.inheritIO(); // This will show the command's output in your console

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        } else {
            logger.info("Successfully ran command: {}", String.join(" ", command));
            return true;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Checks for a build file (pom.xml, build.gradle) or a .classpath file.
     * If none exist, it generates a default .classpath file by guessing the source directory. This is absolutely
     * required for the LSP server to import code.
     *
     * @param projectPath The root of the project workspace.
     * @throws IOException If file I/O fails.
     */
    public static boolean generateDefaultEclipseFiles(Path projectPath) throws IOException {
        // Guess the common source directory path. This is not multi-module
        String sourcePath;
        if (Files.isDirectory(projectPath.resolve("src/main/java"))) {
            sourcePath = "src/main/java";
        } else if (Files.isDirectory(projectPath.resolve("src"))) {
            sourcePath = "src";
        } else {
            // As a last resort, assume sources are in the root.
            sourcePath = ".";
            logger.warn("Could not find a 'src' directory for {}. Defaulting source path to project root.", projectPath);
        }

        // Dynamically determine the JRE version from the current runtime.
        final int javaVersion = Runtime.version().feature();
        String classpathContent = generateClassPathContent(javaVersion, sourcePath);
        String projectFileContent = generateProjectFileContent(projectPath.getFileName().toString());

        // Write the new .classpath and .project file.
        Files.writeString(projectPath.resolve(".project"), projectFileContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(projectPath.resolve(".classpath"), classpathContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Generated default .classpath for {} with source path '{}'", projectPath, sourcePath);
        return true; // exceptions would prevent this return
    }

    private static @NotNull String generateClassPathContent(int javaVersion, String sourcePath) {
        final String jreVersionString = (javaVersion >= 9) ? "JavaSE-" + javaVersion : "JavaSE-1." + javaVersion;
        final String jreContainerPath = "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/" + jreVersionString;

        // Generate the .classpath content with the dynamic JRE path.
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                    <classpathentry kind="src" path="%s"/>
                    <classpathentry kind="con" path="%s"/>
                </classpath>
                """, sourcePath, jreContainerPath);
    }

    private static @NotNull String generateProjectFileContent(String projectName) {
        return String.format("""
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
                """, projectName);
    }

}
