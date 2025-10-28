package ai.brokk.analyzer;

import ai.brokk.IProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SqlAnalyzer implements IAnalyzer, SkeletonProvider {
    private static final Logger logger = LogManager.getLogger(SqlAnalyzer.class);

    private final IProject project;
    private final Map<ProjectFile, List<CodeUnit>> declarationsByFile;
    final Map<CodeUnit, List<Range>> rangesByCodeUnit; // Made package-private for testing
    private final List<CodeUnit> allDeclarationsList;
    private final Map<String, List<CodeUnit>> definitionsByFqName;
    private final Set<Path> normalizedExcludedPaths;
    private long lastAnalysisTimeNanos;

    // Regex to find "CREATE [OR REPLACE] [TEMPORARY] TABLE|VIEW [IF NOT EXISTS] schema.name"
    // Group 1: TABLE or VIEW
    // Group 2: Fully qualified name (e.g., my_table, my_schema.my_table)
    private static final Pattern CREATE_STMT_PATTERN = Pattern.compile(
            "CREATE(?:\\s+OR\\s+REPLACE)?(?:\\s+TEMPORARY)?\\s+(TABLE|VIEW)(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([a-zA-Z_0-9]+(?:\\.[a-zA-Z_0-9]+)*)",
            Pattern.CASE_INSENSITIVE);

    public SqlAnalyzer(
            IProject projectInstance,
            Set<Path> excludedFiles) { // Renamed parameter to avoid confusion with unused field
        this.project = projectInstance;
        this.declarationsByFile = new HashMap<>();
        this.rangesByCodeUnit = new HashMap<>();
        this.allDeclarationsList = new ArrayList<>();
        this.definitionsByFqName = new HashMap<>();
        this.normalizedExcludedPaths = excludedFiles.stream()
                .map(p -> projectInstance.getRoot().resolve(p).toAbsolutePath().normalize())
                .collect(Collectors.toSet());
        this.lastAnalysisTimeNanos = System.nanoTime();

        analyzeSqlFiles(this.normalizedExcludedPaths);
    }

    private void analyzeSqlFiles(Set<Path> normalizedExclusions) {

        var filesToAnalyze = project.getAllFiles().stream()
                .filter(pf -> {
                    // Check extension
                    if (!pf.absPath().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                        return false;
                    }
                    // Check exclusions
                    Path absPfPath = pf.absPath().normalize();
                    if (normalizedExclusions.stream().anyMatch(absPfPath::startsWith)) {
                        logger.debug("Skipping excluded SQL file: {}", pf.absPath());
                        return false;
                    }
                    return true;
                })
                .toList();

        logger.info("Found {} SQL files to analyze for project {}", filesToAnalyze.size(), project.getRoot());

        for (var pf : filesToAnalyze) {
            try {
                String content = Files.readString(pf.absPath(), StandardCharsets.UTF_8);
                // byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8); // Unused variable removed

                var matcher = CREATE_STMT_PATTERN.matcher(content);
                int searchOffset = 0;
                while (matcher.find(searchOffset)) {
                    int statementStartOffsetInChars = matcher.start();
                    // String type = matcher.group(1).toUpperCase(Locale.ROOT); // TABLE or VIEW
                    String fqName = matcher.group(2);

                    // Find the end of the statement (next semicolon not in comments/strings)
                    // This is a naive search, as requested.
                    int semicolonCharPos = content.indexOf(';', matcher.end());
                    if (semicolonCharPos == -1) {
                        // No semicolon found, statement might be malformed or end of file. Ignore.
                        searchOffset = matcher.end(); // Advance search past current non-terminated match
                        continue;
                    }
                    int statementEndOffsetInChars = semicolonCharPos; // inclusive of semicolon

                    // Convert char offsets to byte offsets
                    int statementStartByte = new String(
                                    content.substring(0, statementStartOffsetInChars)
                                            .getBytes(StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8)
                            .length();
                    int statementEndByte = new String(
                                    content.substring(0, statementEndOffsetInChars + 1)
                                            .getBytes(StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8)
                            .length();

                    String packageName;
                    String shortName;
                    int lastDot = fqName.lastIndexOf('.');
                    if (lastDot != -1) {
                        packageName = fqName.substring(0, lastDot);
                        shortName = fqName.substring(lastDot + 1);
                    } else {
                        packageName = "";
                        shortName = fqName;
                    }

                    // Using CodeUnitType.CLASS for both TABLE and VIEW as per initial interpretation
                    var cu = CodeUnit.cls(pf, packageName, shortName);

                    declarationsByFile
                            .computeIfAbsent(pf, k -> new ArrayList<>())
                            .add(cu);
                    allDeclarationsList.add(cu);
                    definitionsByFqName
                            .computeIfAbsent(cu.fqName(), k -> new ArrayList<>())
                            .add(cu);

                    int startLine = countLines(content, statementStartOffsetInChars);
                    int endLine = countLines(content, statementEndOffsetInChars);
                    var range = new TreeSitterAnalyzer.Range(
                            statementStartByte, statementEndByte, startLine, endLine, statementStartByte);
                    rangesByCodeUnit.computeIfAbsent(cu, k -> new ArrayList<>()).add(range);

                    searchOffset = statementEndOffsetInChars + 1; // Continue search after this statement
                }
            } catch (IOException e) {
                logger.warn("Failed to read or parse SQL file {}: {}", pf.absPath(), e.getMessage());
            }
        }
        this.lastAnalysisTimeNanos = System.nanoTime();
    }

    private int countLines(String text, int charEndOffset) {
        if (charEndOffset == 0) return 1;
        int lines = 1;
        // Ensure we don't go past the end of the string if charEndOffset is too large
        int effectiveEndOffset = Math.min(charEndOffset, text.length());
        for (int i = 0; i < effectiveEndOffset; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    @Override
    public IProject getProject() {
        return project;
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return Collections.unmodifiableList(allDeclarationsList);
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return Set.copyOf(declarationsByFile.getOrDefault(file, Collections.emptyList()));
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        var cus = definitionsByFqName.getOrDefault(fqName, Collections.emptyList());
        if (cus.size() == 1) {
            return Optional.of(cus.get(0));
        }
        return Optional.empty(); // Ambiguous or not found
    }

    @Override
    public List<String> importStatementsOf(ProjectFile file) {
        return List.of();
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        var declarations = declarationsByFile.get(file);
        if (declarations == null || declarations.isEmpty()) {
            logger.debug(
                    "No declarations found for file {} when searching for enclosing range [{}..{})",
                    file.absPath(),
                    range.startByte(),
                    range.endByte());
            return Optional.empty();
        }

        var best = declarations.stream()
                .flatMap(cu -> rangesByCodeUnit.getOrDefault(cu, List.of()).stream()
                        .filter(range::isContainedWithin)
                        .map(r -> Map.entry(cu, r)))
                .min(Comparator.comparingInt(
                        e -> e.getValue().endByte() - e.getValue().startByte()))
                .map(Map.Entry::getKey)
                .orElse(null);

        if (best != null) {
            logger.debug(
                    "Found enclosing SQL CodeUnit {} for range [{}..{}) in file {}",
                    best.fqName(),
                    range.startByte(),
                    range.endByte(),
                    file.absPath());
        } else {
            logger.debug(
                    "No enclosing SQL CodeUnit for range [{}..{}) in file {}",
                    range.startByte(),
                    range.endByte(),
                    file.absPath());
        }

        return Optional.ofNullable(best);
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        var cuOpt = getDefinition(fqName);
        if (cuOpt.isEmpty()) {
            return Optional.empty();
        }
        var cu = cuOpt.get();
        var ranges = rangesByCodeUnit.get(cu);

        if (ranges == null || ranges.isEmpty()) {
            // This should not happen if data is consistent
            logger.warn("No ranges found for CodeUnit: {}", cu.fqName());
            return Optional.empty();
        }

        // For simplicity, take the first range if multiple were stored (though unlikely for SQL CREATE)
        var range = ranges.get(0);

        try {
            // Read the specific part of the file using byte offsets
            // Note: Files.readString might be inefficient for very large files if called repeatedly.
            // Consider caching file contents if performance becomes an issue.
            byte[] allBytes = Files.readAllBytes(cu.source().absPath());
            if (range.endByte() > allBytes.length || range.startByte() > range.endByte()) {
                logger.error(
                        "Invalid range for skeleton for {}: start {}, end {}, file size {}",
                        fqName,
                        range.startByte(),
                        range.endByte(),
                        allBytes.length);
                return Optional.empty();
            }
            String statementText = new String(
                    allBytes, range.startByte(), range.endByte() - range.startByte(), StandardCharsets.UTF_8);
            return Optional.of(statementText);
        } catch (IOException e) {
            logger.warn(
                    "IOException while reading file for skeleton {}: {}",
                    cu.source().absPath(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getSkeletonHeader(String fqName) {
        // For SQL CREATE TABLE/VIEW, the "header" is the full statement.
        return getSkeleton(fqName);
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return List.copyOf(declarationsByFile.getOrDefault(file, Collections.emptyList()));
    }

    @Override
    public List<CodeUnit> getSubDeclarations(CodeUnit cu) {
        return List.of();
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        if (changedFiles.isEmpty()) {
            return this;
        }

        // Filter to only SQL files
        var relevantFiles = changedFiles.stream()
                .filter(pf -> pf.absPath().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                .collect(Collectors.toSet());

        if (relevantFiles.isEmpty()) {
            return this;
        }

        // Create a new analyzer with the same configuration
        var updatedAnalyzer = new SqlAnalyzer(
                project,
                normalizedExcludedPaths.stream()
                        .map(p -> {
                            // Denormalize back to relative path
                            try {
                                return project.getRoot().relativize(p);
                            } catch (IllegalArgumentException e) {
                                // If not relative to root, use as-is
                                return p;
                            }
                        })
                        .collect(Collectors.toSet()));

        return updatedAnalyzer;
    }

    @Override
    public IAnalyzer update() {
        // Detect changes by checking file modification times
        long mimeEpsilonNanos = 300_000_000; // 300ms tolerance

        Set<ProjectFile> changedFiles = new HashSet<>();

        // Check for modified or deleted files
        var sqlFiles = project.getAllFiles().stream()
                .filter(pf -> pf.absPath().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                .collect(Collectors.toSet());

        for (var file : sqlFiles) {
            if (!Files.exists(file.absPath())) {
                // File was deleted
                changedFiles.add(file);
                continue;
            }

            try {
                var instant = Files.getLastModifiedTime(file.absPath()).toInstant();
                long fileModTimeNanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();

                if (fileModTimeNanos > lastAnalysisTimeNanos - mimeEpsilonNanos) {
                    changedFiles.add(file);
                }
            } catch (IOException e) {
                logger.warn("Could not get modification time for {}: {}", file.absPath(), e.getMessage());
                changedFiles.add(file); // Treat as potentially changed
            }
        }

        // Also check for new files not yet analyzed
        for (var file : sqlFiles) {
            if (!declarationsByFile.containsKey(file) && Files.exists(file.absPath())) {
                changedFiles.add(file);
            }
        }

        if (changedFiles.isEmpty()) {
            return this;
        }

        return update(changedFiles);
    }

    // Other IAnalyzer methods (CPG, advanced summarization, etc.)
    // will throw UnsupportedOperationException as per IAnalyzer default implementations.
}
