package ai.brokk.tools;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility to extract all Java CodeUnits from a directory and save them to a CSV.
 */
public class CodeUnitExtractor {
    private static final Logger logger = LogManager.getLogger(CodeUnitExtractor.class);
    private static final String DEFAULT_APP_PATH = "app";
    private static final String DEFAULT_OUTPUT_PATH = "app/test/resources/codeunits.csv";

    public static void main(String[] args) {
        Path projectRoot = Path.of(args.length > 0 ? args[0] : DEFAULT_APP_PATH).toAbsolutePath().normalize();
        Path outputPath = Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT_PATH);

        try {
            extract(projectRoot, outputPath);
            logger.info("Extraction complete.");
        } catch (Exception e) {
            logger.error("Failed to extract CodeUnits: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public static void extract(Path projectRoot, Path outputPath) throws IOException {
        logger.info("Extracting CodeUnits from: {}", projectRoot);

        if (!Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Provided path is not a directory: " + projectRoot);
        }

        IProject project = createMinimalProject(projectRoot);
        JavaAnalyzer analyzer = new JavaAnalyzer(project);

        logger.info("Analyzing files...");
        analyzer.update();

        List<CodeUnit> declarations = analyzer.getAllDeclarations();
        logger.info("Found {} declarations. Writing to {}...", declarations.size(), outputPath);

        List<String> lines = declarations.stream()
                .map(cu -> String.format("%s,%s", cu.kind(), cu.fqName()))
                .sorted()
                .toList();

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputPath, lines);
    }

    private static IProject createMinimalProject(Path root) {
        return new IProject() {
            @Override
            public Path getRoot() {
                return root;
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                try (Stream<Path> stream = Files.walk(root)) {
                    return stream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .map(p -> new ProjectFile(root, root.relativize(p)))
                            .collect(Collectors.toSet());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to list files in " + root, e);
                }
            }

            @Override
            public void close() {
                // No-op for this tool
            }
        };
    }
}
