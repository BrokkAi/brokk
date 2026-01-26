package ai.brokk.tools;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

@NullMarked
@CommandLine.Command(
        name = "UsageBenchEval",
        mixinStandardHelpOptions = true,
        description = "Evaluates FuzzyUsageFinder against a labeled dataset")
public class UsageBenchEval implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--input-dir"},
            required = true,
            description = "Path to dataset root directory")
    private Path inputDir = Path.of(".");

    @CommandLine.Option(
            names = {"--language"},
            defaultValue = "ALL",
            description = "Filter by Language.internalName (default: ALL)")
    private String language = "ALL";

    @CommandLine.Option(
            names = {"--output"},
            defaultValue = "./usage-results.json",
            description = "Output file path for results (default: ./usage-results.json)")
    private Path output = Path.of("./usage-results.json");

    @CommandLine.Option(
            names = {"--projects"},
            description = "Specific project names to target (repeatable)")
    private List<String> projects = List.of();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new UsageBenchEval()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        List<ProjectEntry> projectEntries = discoverProjects();
        System.out.printf("Discovered %d projects for evaluation.%n", projectEntries.size());

        for (ProjectEntry entry : projectEntries) {
            System.out.printf("Processing %s [%s]...%n", entry.projectDir().getFileName(), entry.language().name());
            try {
                IProject project = loadProject(entry);
                ProgramUsages groundTruth = loadGroundTruth(entry.usagesJsonPath());
                // TODO: Implement evaluation logic in next step
            } catch (Exception e) {
                System.err.printf("Failed to process %s: %s%n", entry.projectDir(), e.getMessage());
            }
        }

        return 0;
    }

    private List<ProjectEntry> discoverProjects() throws IOException {
        List<ProjectEntry> entries = new ArrayList<>();
        if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
            return entries;
        }

        try (Stream<Path> langDirs = Files.list(inputDir)) {
            langDirs.filter(Files::isDirectory).forEach(langDir -> {
                String langName = langDir.getFileName().toString().toUpperCase(Locale.ROOT);
                if (!"ALL".equals(language) && !langName.equals(language)) {
                    return;
                }

                Language lang;
                try {
                    lang = Languages.valueOf(langName);
                } catch (IllegalArgumentException e) {
                    return;
                }

                try (Stream<Path> projectPaths = Files.list(langDir)) {
                    projectPaths.filter(Files::isDirectory).forEach(projectDir -> {
                        String projectName = projectDir.getFileName().toString();
                        if (projectName.endsWith("-usages.json")) {
                            return;
                        }

                        if (!projects.isEmpty() && !projects.contains(projectName)) {
                            return;
                        }

                        Path usagesJson = langDir.resolve(projectName + "-usages.json");
                        if (Files.exists(usagesJson)) {
                            entries.add(new ProjectEntry(projectDir, usagesJson, lang));
                        }
                    });
                } catch (IOException e) {
                    System.err.println("Error scanning language directory: " + langDir);
                }
            });
        }
        return entries;
    }

    private IProject loadProject(ProjectEntry entry) throws IOException {
        Set<String> extensions = entry.language().getExtensions();
        try (Stream<Path> walk = Files.walk(entry.projectDir())) {
            List<ProjectFile> projectFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> extensions.stream().anyMatch(ext -> p.toString().endsWith("." + ext)))
                    .map(p -> new ProjectFile(entry.projectDir(), entry.projectDir().relativize(p)))
                    .toList();

            return new SimpleProject(entry.projectDir(), entry.language(), projectFiles);
        }
    }

    private ProgramUsages loadGroundTruth(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(path.toFile(), ProgramUsages.class);
    }

    private record ProjectEntry(Path projectDir, Path usagesJsonPath, Language language) {}

    private static class SimpleProject implements IProject {
        private final Path root;
        private final Language language;
        private final Set<ProjectFile> files;

        SimpleProject(Path root, Language language, List<ProjectFile> files) {
            this.root = root;
            this.language = language;
            this.files = Set.copyOf(files);
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(language);
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            return files;
        }

        @Override
        public void close() {
            // No-op
        }
    }

    // --- JSON Input Records (matching Scala domain) ---

    public record ProgramUsages(@JsonProperty("codeUnits") List<CodeUnitUsages> codeUnits) {}

    public record CodeUnitUsages(
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("type") String type,
            @JsonProperty("usages") List<UsageLocation> usages) {}

    public record UsageLocation(
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("lineNumber") int lineNumber) {}

    // --- Result Records ---

    public record EvalResults(List<ProjectResult> projects, AggregateMetrics aggregate) {}

    public record ProjectResult(
            String project,
            String language,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1) {}

    public record AggregateMetrics(
            int totalTP, int totalFP, int totalFN, double precision, double recall, double f1) {}
}
