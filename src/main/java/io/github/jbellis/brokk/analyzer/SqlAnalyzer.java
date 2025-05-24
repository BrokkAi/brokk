package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.Project; // Changed from common.IProject to Project
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream; // Added import for Stream

public class SqlAnalyzer implements IAnalyzer {
    private static final Logger logger = LogManager.getLogger(SqlAnalyzer.class);

    private final Project project; // Changed from IProject to Project
    private final Map<ProjectFile, List<CodeUnit>> declarationsByFile;
    final Map<CodeUnit, List<TreeSitterAnalyzer.Range>> rangesByCodeUnit; // Made package-private for testing
    private final List<CodeUnit> allDeclarationsList;
    private final Map<String, List<CodeUnit>> definitionsByFqName;

    // Regex to find "CREATE [OR REPLACE] [TEMPORARY] TABLE|VIEW [IF NOT EXISTS] schema.name"
    // Group 1: TABLE or VIEW
    // Group 2: Fully qualified name (e.g., my_table, my_schema.my_table)
    private static final Pattern CREATE_STMT_PATTERN = Pattern.compile(
            "CREATE(?:\\s+OR\\s+REPLACE)?(?:\\s+TEMPORARY)?\\s+(TABLE|VIEW)(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([a-zA-Z_0-9]+(?:\\.[a-zA-Z_0-9]+)*)",
            Pattern.CASE_INSENSITIVE);

    public SqlAnalyzer(Project project, Set<Path> excludedFiles) { // Changed from IProject to Project
        this.project = project;
        this.declarationsByFile = new HashMap<>();
        this.rangesByCodeUnit = new HashMap<>();
        this.allDeclarationsList = new ArrayList<>();
        this.definitionsByFqName = new HashMap<>();

        var normalizedExclusions = excludedFiles.stream()
                .map(p -> project.getRoot().resolve(p).toAbsolutePath().normalize())
                .collect(Collectors.toSet());

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
                .collect(Collectors.toList());

        logger.info("Found {} SQL files to analyze for project {}", filesToAnalyze.size(), project.getRoot());

        for (var pf : filesToAnalyze) {
            try {
                String content = Files.readString(pf.absPath(), StandardCharsets.UTF_8);
                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8); // For byte offsets

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
                    int statementStartByte = new String(content.substring(0, statementStartOffsetInChars).getBytes(StandardCharsets.UTF_8)).length();
                    int statementEndByte = new String(content.substring(0, statementEndOffsetInChars + 1).getBytes(StandardCharsets.UTF_8)).length();


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

                    declarationsByFile.computeIfAbsent(pf, k -> new ArrayList<>()).add(cu);
                    allDeclarationsList.add(cu);
                    definitionsByFqName.computeIfAbsent(cu.fqName(), k -> new ArrayList<>()).add(cu);

                    int startLine = countLines(content, statementStartOffsetInChars);
                    int endLine = countLines(content, statementEndOffsetInChars);
                    var range = new TreeSitterAnalyzer.Range(statementStartByte, statementEndByte, startLine, endLine);
                    rangesByCodeUnit.computeIfAbsent(cu, k -> new ArrayList<>()).add(range);

                    searchOffset = statementEndOffsetInChars + 1; // Continue search after this statement
                }
            } catch (IOException e) {
                logger.warn("Failed to read or parse SQL file {}: {}", pf.absPath(), e.getMessage());
            }
        }
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
    public boolean isEmpty() {
        return allDeclarationsList.isEmpty();
    }

    @Override
    public boolean isCpg() {
        return false; // SQL Analyzer does not produce CPGs
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return Collections.unmodifiableList(allDeclarationsList);
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return Set.copyOf(declarationsByFile.getOrDefault(file, Collections.emptyList()));
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqName) {
        var cus = definitionsByFqName.getOrDefault(fqName, Collections.emptyList());
        if (cus.size() == 1) {
            return Optional.of(cus.get(0).source());
        }
        return Optional.empty(); // Ambiguous or not found
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
                 logger.error("Invalid range for skeleton for {}: start {}, end {}, file size {}",
                    fqName, range.startByte(), range.endByte(), allBytes.length);
                 return Optional.empty();
            }
            String statementText = new String(allBytes, range.startByte(), range.endByte() - range.startByte(), StandardCharsets.UTF_8);
            return Optional.of(statementText);
        } catch (IOException e) {
            logger.warn("IOException while reading file for skeleton {}: {}", cu.source().absPath(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getSkeletonHeader(String fqName) {
        // For SQL CREATE TABLE/VIEW, the "header" is the full statement.
        return getSkeleton(fqName);
    }

    // Other IAnalyzer methods (CPG, advanced summarization, etc.)
    // will throw UnsupportedOperationException as per IAnalyzer default implementations.
}
