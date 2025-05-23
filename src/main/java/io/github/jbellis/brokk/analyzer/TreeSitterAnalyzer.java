package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, language-agnostic skeleton extractor backed by Tree-sitter.
 * Stores summarized skeletons for top-level definitions only.
 * <p>
 * Subclasses provide the language–specific bits: which Tree-sitter grammar,
 * which file extensions, which query, and how to map a capture to a {@link CodeUnit}.
 */
public abstract class TreeSitterAnalyzer implements IAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);
    // Native library loading is assumed automatic by the io.github.bonede.tree_sitter library.

    /* ---------- instance state ---------- */
    private final ThreadLocal<TSLanguage> threadLocalLanguage = ThreadLocal.withInitial(this::createTSLanguage);
    private final ThreadLocal<TSQuery> query;
    final Map<ProjectFile, List<CodeUnit>> topLevelDeclarations = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, List<CodeUnit>> childrenByParent = new ConcurrentHashMap<>(); // package-private for testing
    final Map<CodeUnit, List<String>> signatures = new ConcurrentHashMap<>(); // package-private for testing
    private final Map<CodeUnit, List<Range>> sourceRanges = new ConcurrentHashMap<>();
    private final IProject project;
    protected final Set<String> normalizedExcludedFiles;

    protected record LanguageSyntaxProfile(
        Set<String> classLikeNodeTypes,
        Set<String> functionLikeNodeTypes,
        Set<String> fieldLikeNodeTypes,
        Set<String> decoratorNodeTypes,
        String identifierFieldName,
        String bodyFieldName,
        String parametersFieldName,
        String returnTypeFieldName,
        Map<String, SkeletonType> captureConfiguration,
        String asyncKeywordNodeType,
        Set<String> modifierNodeTypes
    ) {
        public LanguageSyntaxProfile {
            Objects.requireNonNull(classLikeNodeTypes);
            Objects.requireNonNull(functionLikeNodeTypes);
            Objects.requireNonNull(fieldLikeNodeTypes);
            Objects.requireNonNull(decoratorNodeTypes);
            Objects.requireNonNull(identifierFieldName);
            Objects.requireNonNull(bodyFieldName);
            Objects.requireNonNull(parametersFieldName);
            Objects.requireNonNull(returnTypeFieldName);    // Can be empty string if not applicable
            Objects.requireNonNull(captureConfiguration);
            Objects.requireNonNull(asyncKeywordNodeType); // Can be empty string if not applicable
            Objects.requireNonNull(modifierNodeTypes);
        }
    }

    private static record Range(int startByte, int endByte, int startLine, int endLine) {}

    private record FileAnalysisResult(List<CodeUnit> topLevelCUs,
                                      Map<CodeUnit, List<CodeUnit>> children,
                                      Map<CodeUnit, List<String>> signatures,
                                      Map<CodeUnit, List<Range>> sourceRanges,
                                      List<String> importStatements // Added for module-level imports
                                      ) {}

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        this.project = project;
        // tsLanguage field removed, getTSLanguage().get() will provide it via ThreadLocal

        this.normalizedExcludedFiles = (excludedFiles != null ? excludedFiles : Collections.<String>emptySet())
                .stream()
                .map(p -> {
                    Path path = Path.of(p);
                    if (path.isAbsolute()) {
                        return path.normalize().toString();
                    } else {
                        return project.getRoot().resolve(p).toAbsolutePath().normalize().toString();
                    }
                })
                .collect(Collectors.toSet());
        if (!this.normalizedExcludedFiles.isEmpty()) {
            log.debug("Normalized excluded files: {}", this.normalizedExcludedFiles);
        }

        // Initialize query using a ThreadLocal for thread safety
        // The supplier will use the appropriate getQueryResource() from the subclass
        // and getTSLanguage() for the current thread.
        this.query = ThreadLocal.withInitial(() -> {
            String rawQueryString = loadResource(getQueryResource());
            return new TSQuery(getTSLanguage(), rawQueryString);
        });

        // Debug log using SLF4J
        log.debug("Initializing TreeSitterAnalyzer for language: {}, query resource: {}",
                 project.getAnalyzerLanguage(), getQueryResource());


        var validExtensions = project.getAnalyzerLanguage().getExtensions();
        log.trace("Filtering project files for extensions: {}", validExtensions);

        project.getAllFiles().stream()
                .filter(pf -> {
                    var pathStr = pf.absPath().toString();
                    if (this.normalizedExcludedFiles.contains(pathStr)) {
                        log.trace("Skipping excluded file: {}", pf);
                        return false;
                    }
                    return validExtensions.stream().anyMatch(pathStr::endsWith);
                })
                .parallel()
                .forEach(pf -> {
                    log.trace("Processing file: {}", pf);
                    // TSParser is not threadsafe, so we create a parser per thread
                    var localParser = new TSParser();
                    try {
                        if (!localParser.setLanguage(getTSLanguage())) {
                            log.error("Failed to set language on thread-local TSParser for language {} in file {}", getTSLanguage().getClass().getSimpleName(), pf);
                            return; // Skip this file if parser setup fails
                        }
                        var analysisResult = analyzeFileDeclarations(pf, localParser);
                        if (!analysisResult.topLevelCUs().isEmpty() || !analysisResult.signatures().isEmpty() || !analysisResult.sourceRanges().isEmpty()) {
                            topLevelDeclarations.put(pf, analysisResult.topLevelCUs()); // Already unmodifiable from result

                            analysisResult.children().forEach((parentCU, newChildCUs) -> childrenByParent.compute(parentCU, (p, existingChildCUs) -> {
                                if (existingChildCUs == null) {
                                    return newChildCUs; // Already unmodifiable
                                }
                                List<CodeUnit> combined = new ArrayList<>(existingChildCUs);
                                for (CodeUnit newKid : newChildCUs) {
                                    if (!combined.contains(newKid)) {
                                        combined.add(newKid);
                                    }
                                }
                                if (combined.size() == existingChildCUs.size()) {
                                    boolean changed = false;
                                    for (int i = 0; i < combined.size(); ++i) {
                                        if (!combined.get(i).equals(existingChildCUs.get(i))) {
                                            changed = true;
                                            break;
                                        }
                                    }
                                    if (!changed) return existingChildCUs;
                                }
                                return Collections.unmodifiableList(combined);
                            }));

                            analysisResult.signatures().forEach((cu, newSignaturesList) -> signatures.compute(cu, (key, existingSignaturesList) -> {
                                if (existingSignaturesList == null) {
                                    return newSignaturesList; // Already unmodifiable from result
                                }
                                List<String> combined = new ArrayList<>(existingSignaturesList);
                                combined.addAll(newSignaturesList);
                                // Assuming order from newSignaturesList is appropriate to append.
                                // If global ordering or deduplication of signatures for a CU is needed, add here.
                                return Collections.unmodifiableList(combined);
                            }));

                            analysisResult.sourceRanges().forEach((cu, newRangesList) -> sourceRanges.compute(cu, (key, existingRangesList) -> {
                                if (existingRangesList == null) {
                                    return newRangesList; // Already unmodifiable
                                }
                                List<Range> combined = new ArrayList<>(existingRangesList);
                                combined.addAll(newRangesList);
                                return Collections.unmodifiableList(combined);
                            }));

                            log.trace("Processed file {}: {} top-level CUs, {} signatures, {} parent-child relationships, {} source range entries.",
                                      pf, analysisResult.topLevelCUs().size(), analysisResult.signatures().size(), analysisResult.children().size(), analysisResult.sourceRanges().size());
                        } else {
                            log.trace("analyzeFileDeclarations returned empty result for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error analyzing {}: {}", pf, e, e);
                    }
                });
    }

    protected TreeSitterAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    /* ---------- Helper methods for accessing CodeUnits ---------- */
    /**  All CodeUnits we know about (top-level + children). */
    private Stream<CodeUnit> allCodeUnits() {
        // Stream top-level declarations
        Stream<CodeUnit> topLevelStream = topLevelDeclarations.values().stream().flatMap(Collection::stream);

        // Stream parents from childrenByParent (they might not be in topLevelDeclarations if they are nested)
        Stream<CodeUnit> parentStream = childrenByParent.keySet().stream();

        // Stream children from childrenByParent
        Stream<CodeUnit> childrenStream = childrenByParent.values().stream().flatMap(Collection::stream);

        return Stream.of(topLevelStream, parentStream, childrenStream).flatMap(s -> s);
    }

    /**  De-duplicate and materialise into a List once. */
    private List<CodeUnit> uniqueCodeUnitList() {
        return allCodeUnits().distinct().toList();
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return topLevelDeclarations.isEmpty() && signatures.isEmpty() && childrenByParent.isEmpty() && sourceRanges.isEmpty(); }

    @Override public boolean isCpg() { return false; }

    @Override
    public Optional<String> getSkeletonHeader(String fqName) {
        return getSkeleton(fqName).map(s ->
            s.lines().findFirst().orElse("").stripTrailing()
        );
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        Optional<CodeUnit> parent = uniqueCodeUnitList().stream()
                                                        .filter(cu -> cu.fqName().equals(fqClass) && cu.isClass())
                                                        .findFirst();
        return parent.map(p -> List.copyOf(childrenByParent.getOrDefault(p, List.of())))
                     .orElse(List.of());
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqName) {
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().equals(fqName))
                                   .map(CodeUnit::source)
                                   .findFirst();
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().equals(fqName))
                                   .findFirst();
    }

    @Override
    public List<CodeUnit> searchDefinitions(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return List.of();
        }
        return uniqueCodeUnitList().stream()
                                   .filter(cu -> cu.fqName().contains(pattern))
                                   .toList();
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        Set<CodeUnit> allClasses = new HashSet<>();
        topLevelDeclarations.values().forEach(allClasses::addAll);
        childrenByParent.values().forEach(allClasses::addAll); // Children lists
        allClasses.addAll(childrenByParent.keySet());          // Parent CUs themselves
        return allClasses.stream().filter(CodeUnit::isClass).distinct().toList();
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
        if (topCUs.isEmpty()) return Map.of();

        Map<CodeUnit, String> resultSkeletons = new HashMap<>();
        List<CodeUnit> sortedTopCUs = new ArrayList<>(topCUs);
        // Sort CUs: MODULE CUs (for imports) should ideally come first.
        // This simple sort puts them first if their fqName sorts before others.
        // A more explicit sort could check cu.isModule().
        Collections.sort(sortedTopCUs);


        for (CodeUnit cu : sortedTopCUs) {
            resultSkeletons.put(cu, reconstructFullSkeleton(cu));
        }
        log.trace("getSkeletons: file={}, count={}", file, resultSkeletons.size());
        return Collections.unmodifiableMap(resultSkeletons);
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        List<CodeUnit> topCUs = topLevelDeclarations.getOrDefault(file, List.of());
        if (topCUs.isEmpty()) return Set.of();

        Set<CodeUnit> allDeclarationsInFile = new HashSet<>();
        Queue<CodeUnit> toProcess = new LinkedList<>(topCUs);
        Set<CodeUnit> visited = new HashSet<>(topCUs); // Track visited to avoid cycles and redundant processing

        while(!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            allDeclarationsInFile.add(current); // Add all encountered CodeUnits

            childrenByParent.getOrDefault(current, List.of()).forEach(child -> {
                if (visited.add(child)) { // Add to queue only if not visited
                    toProcess.add(child);
                }
            });
        }
        log.trace("getDeclarationsInFile: file={}, count={}", file, allDeclarationsInFile.size());
        return Collections.unmodifiableSet(allDeclarationsInFile);
    }

    private String reconstructFullSkeleton(CodeUnit cu) {
        StringBuilder sb = new StringBuilder();
        reconstructSkeletonRecursive(cu, "", sb);
        return sb.toString().stripTrailing();
    }

    private void reconstructSkeletonRecursive(CodeUnit cu, String indent, StringBuilder sb) {
        List<String> sigList = signatures.get(cu);
        if (sigList == null || sigList.isEmpty()) {
            log.warn("Missing or empty signature list for CU: {}. Skipping in skeleton reconstruction.", cu);
            return;
        }

        for (String individualFullSignature : sigList) {
            if (individualFullSignature == null || individualFullSignature.isBlank()) {
                log.warn("Encountered null or blank signature in list for CU: {}. Skipping this signature.", cu);
                continue;
            }
            // Apply indent to each line of the current signature
            String[] signatureLines = individualFullSignature.split("\n", -1); // Use -1 limit
            for (String line : signatureLines) {
                sb.append(indent).append(line).append('\n');
            }
        }

        List<CodeUnit> kids = childrenByParent.getOrDefault(cu, List.of());
        // Only add children and closer if the CU can have them (e.g. class, or function that can nest)
        // For simplicity now, always check for children. Specific languages might refine this.
        if (!kids.isEmpty() || (cu.isClass() && getLanguageSpecificCloser(cu).length() > 0)) { // also add closer for empty classes
            String childIndent = indent + getLanguageSpecificIndent();
            for (CodeUnit kid : kids) {
                reconstructSkeletonRecursive(kid, childIndent, sb);
            }
            String closer = getLanguageSpecificCloser(cu);
            if (!closer.isEmpty()) {
                sb.append(indent).append(closer).append('\n');
            }
        }
    }


    @Override
    public Optional<String> getSkeleton(String fqName) {
        Optional<CodeUnit> cuOpt = signatures.keySet().stream()
                                          .filter(c -> c.fqName().equals(fqName))
                                          .findFirst();
        if (cuOpt.isPresent()) {
            String skeleton = reconstructFullSkeleton(cuOpt.get());
            log.trace("getSkeleton: fqName='{}', found=true", fqName);
            return Optional.of(skeleton);
        }
        log.trace("getSkeleton: fqName='{}', found=false", fqName);
        return Optional.empty();
    }

    @Override
    public String getClassSource(String fqName) {
        var cu = getDefinition(fqName)
                         .filter(CodeUnit::isClass)
                         .orElseThrow(() -> new SymbolNotFoundException("Class not found: " + fqName));

        var ranges = sourceRanges.get(cu);
        if (ranges == null || ranges.isEmpty()) {
            throw new SymbolNotFoundException("Source range not found for class: " + fqName);
        }

        // For classes, expect one primary definition range.
        var range = ranges.getFirst();
        String src = null;
        try {
            src = cu.source().read();
        } catch (IOException e) {
            return "";
        }
        return src.substring(range.startByte(), range.endByte());
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return getDefinition(fqName) // Finds the single CodeUnit representing this FQN (due to CodeUnit equality for overloads)
            .filter(CodeUnit::isFunction)
            .flatMap(cu -> {
                List<Range> rangesForOverloads = sourceRanges.get(cu);
                if (rangesForOverloads == null || rangesForOverloads.isEmpty()) {
                    log.warn("No source ranges found for CU {} (fqName {}) although definition was found.", cu, fqName);
                    return Optional.empty();
                }

                String fileContent;
                try {
                    fileContent = cu.source().read();
                } catch (IOException e) {
                    log.warn("Could not read source for CU {} (fqName {}): {}", cu, fqName, e.getMessage());
                    return Optional.empty();
                }

                List<String> individualMethodSources = new ArrayList<>();
                for (Range range : rangesForOverloads) {
                    // Ensure range is within fileContent bounds, though it should be by construction.
                    int startByte = Math.max(0, range.startByte());
                    int endByte = Math.min(fileContent.length(), range.endByte());
                    if (startByte < endByte) {
                        individualMethodSources.add(fileContent.substring(startByte, endByte));
                    } else {
                        log.warn("Invalid range [{}, {}] for CU {} (fqName {}) in file of length {}. Skipping this range.",
                                 range.startByte(), range.endByte(), cu, fqName, fileContent.length());
                    }
                }

                if (individualMethodSources.isEmpty()) {
                    log.warn("After processing ranges, no valid method sources found for CU {} (fqName {}).", cu, fqName);
                    return Optional.empty();
                }
                return Optional.of(String.join("\n\n", individualMethodSources));
            });
    }


    /* ---------- abstract hooks ---------- */
    /** Creates a new TSLanguage instance for the specific language. Called by ThreadLocal initializer. */
    protected abstract TSLanguage createTSLanguage();

    /**
     * Provides a thread-safe TSLanguage instance.
     * @return A TSLanguage instance for the current thread.
     */
    protected TSLanguage getTSLanguage() {
        return threadLocalLanguage.get();
    }

    /** Provides the language-specific syntax profile. */
    protected abstract LanguageSyntaxProfile getLanguageSyntaxProfile();

    /** Class-path resource for the query (e.g. {@code "treesitter/python.scm"}). */
    protected abstract String getQueryResource();

    /**
     * Defines the general type of skeleton that should be built for a given capture.
     */
    public enum SkeletonType {
        CLASS_LIKE,
        FUNCTION_LIKE,
        FIELD_LIKE,
        MODULE_STATEMENT, // For individual import/directive lines if treated as CUs
        UNSUPPORTED
    }

    /**
     * Determines the {@link SkeletonType} for a given capture name.
     * This allows subclasses to map their specific query capture names (e.g., "class.definition", "method.declaration")
     * to a general category for skeleton building.
     *
     * @param captureName The name of the capture from the Tree-sitter query.
     * @return The {@link SkeletonType} indicating how to process this capture for skeleton generation.
     */
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        var profile = getLanguageSyntaxProfile();
        return profile.captureConfiguration().getOrDefault(captureName, SkeletonType.UNSUPPORTED);
    }

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}.
     * Return {@code null} to ignore this capture.
     */
    protected abstract CodeUnit createCodeUnit(ProjectFile file,
                                               String captureName,
                                               String simpleName,
                                               String packageName,
                                               String classChain);

    /**
     * Determines the package or namespace name for a given definition.
     *
     * @param file The project file being analyzed.
     * @param definitionNode The TSNode representing the definition (e.g., class, function).
     * @param rootNode The root TSNode of the file's syntax tree.
     * @param src The source code of the file.
     * @return The package or namespace name, or an empty string if not applicable.
     */
    protected abstract String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src);

    /**
     * Checks if the given AST node represents a class-like declaration
     * (e.g., class, interface, struct) in the specific language.
     * Subclasses must implement this to guide class chain extraction.
     *
     * @param node The TSNode to check.
     * @return true if the node is a class-like declaration, false otherwise.
     */
    protected boolean isClassLike(TSNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return getLanguageSyntaxProfile().classLikeNodeTypes().contains(node.getType());
    }

    /** Captures that should be ignored entirely. */
    protected Set<String> getIgnoredCaptures() { return Set.of(); }

    /** Language-specific indentation string, e.g., "  " or "    ". */
    protected String getLanguageSpecificIndent() { return "  "; } // Default

    /** Language-specific closing token for a class or namespace (e.g., "}"). Empty if none. */
    protected abstract String getLanguageSpecificCloser(CodeUnit cu);


    /**
     * Get the project this analyzer is associated with.
     */
    protected IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */
    /** Analyzes a single file and extracts declaration information. */
    private FileAnalysisResult analyzeFileDeclarations(ProjectFile file, TSParser localParser) throws IOException {
        log.trace("analyzeFileDeclarations: Parsing file: {}", file);

        // this is needed because especially Visual Studio is adding a UTF8 BOM at the beginning of files
        // Read as byte array first to preserve exact UTF-8 encoding
        byte[] fileBytes = Files.readAllBytes(file.absPath());
        // Strip UTF-8 BOM if present (EF BB BF)
        if (fileBytes.length >= 3 &&
            (fileBytes[0] & 0xFF) == 0xEF &&
            (fileBytes[1] & 0xFF) == 0xBB &&
            (fileBytes[2] & 0xFF) == 0xBF) {
            // Create a new array without the BOM
            byte[] bytesWithoutBom = new byte[fileBytes.length - 3];
            System.arraycopy(fileBytes, 3, bytesWithoutBom, 0, fileBytes.length - 3);
            fileBytes = bytesWithoutBom;
        }

        String src = new String(fileBytes, StandardCharsets.UTF_8);

        List<CodeUnit> localTopLevelCUs = new ArrayList<>();
        Map<CodeUnit, List<CodeUnit>> localChildren = new HashMap<>();
        Map<CodeUnit, List<String>> localSignatures = new HashMap<>();
        Map<CodeUnit, List<Range>> localSourceRanges = new HashMap<>();
        Map<String, CodeUnit> localCuByFqName = new HashMap<>(); // For parent lookup within the file
        List<String> localImportStatements = new ArrayList<>(); // For collecting import lines

        TSTree tree = localParser.parseString(null, src);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return new FileAnalysisResult(List.of(), Map.of(), Map.of(), Map.of(), List.of());
        }
        // Log root node type
        String rootNodeType = rootNode.getType();
        log.trace("Root node type for {}: {}", file, rootNodeType);


        // Map to store potential top-level declaration nodes found during the query.
        // The value is a Map.Entry: key = primary capture name (e.g., "class.definition"), value = simpleName.
        Map<TSNode, Map.Entry<String, String>> declarationNodes = new HashMap<>();

        TSQueryCursor cursor = new TSQueryCursor();
        TSQuery currentThreadQuery = this.query.get(); // Get thread-specific query instance
        cursor.exec(currentThreadQuery, rootNode);

        TSQueryMatch match = new TSQueryMatch(); // Reusable match object
        while (cursor.nextMatch(match)) {
            log.trace("Match ID: {}", match.getId());
            // Group nodes by capture name for this specific match
            Map<String, TSNode> capturedNodes = new HashMap<>();
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = currentThreadQuery.getCaptureNameForId(capture.getIndex());
                if (getIgnoredCaptures().contains(captureName)) continue;

                TSNode node = capture.getNode();
                if (node != null && !node.isNull()) {
                    log.trace("  Capture: '{}', Node: {} '{}'", captureName, node.getType(), textSlice(node, src).lines().findFirst().orElse("").trim());
                    // Store the first non-null node found for this capture name in this match
                    // Note: Overwrites if multiple nodes have the same capture name in one match.
                    // The old code implicitly took the first from a list; this takes the last encountered.
                    // If specific handling of multiple nodes per capture/match is needed, adjust here.
                    capturedNodes.put(captureName, node);
                }
            }

            // Handle module-level import statements first if present in this match
            TSNode importNode = capturedNodes.get("module.import_statement");
            if (importNode != null && !importNode.isNull()) {
                String importText = textSlice(importNode, src).strip();
                if (!importText.isEmpty()) {
                    localImportStatements.add(importText);
                }
                // Continue to next match if this was primarily an import, or process other captures in same match
                // For now, assume an import statement match won't also be a primary .definition capture.
                // If it can, then this 'if' should not 'continue' but allow further processing.
            }

            // Process each potential definition found in the match
            for (var captureEntry : capturedNodes.entrySet()) {
                String captureName = captureEntry.getKey();
                TSNode definitionNode = captureEntry.getValue();

                if (captureName.endsWith(".definition")) { // Ensure we only process definition captures here
                    String simpleName;
                    String expectedNameCapture = captureName.replace(".definition", ".name");
                    TSNode nameNode = capturedNodes.get(expectedNameCapture);

                    if (nameNode != null && !nameNode.isNull()) {
                        simpleName = textSlice(nameNode, src);
                        if (simpleName != null && simpleName.isBlank()) {
                            log.warn("Name capture '{}' for definition '{}' in file {} resulted in a BLANK string. NameNode text: [{}], type: [{}]. Will attempt fallback.",
                                     expectedNameCapture, captureName, file, textSlice(nameNode, src), nameNode.getType());
                            // Force fallback if primary name extraction yields blank.
                            simpleName = extractSimpleName(definitionNode, src).orElse(null);
                        }
                    } else {
                        log.warn("Expected name capture '{}' not found for definition '{}' in match for file {}. Current captures in this match: {}. Falling back to extractSimpleName on definition node.",
                                 expectedNameCapture, captureName, file, capturedNodes.keySet());
                        simpleName = extractSimpleName(definitionNode, src).orElse(null); // extractSimpleName is now non-static
                    }

                    if (simpleName != null && !simpleName.isBlank()) {
                        if ("interface.method.definition".equals(captureName) && file.getFileName().equals("declarations.go")) {
                            log.trace("[declarations.go DEBUG] About to add to declarationNodes: Node='{}' (line {} text:'{}'), Capture='{}', SimpleName='{}'",
                                     definitionNode.getType(), definitionNode.getStartPoint().getRow() + 1, textSlice(definitionNode, src).lines().findFirst().orElse(""),
                                     captureName, simpleName);
                        }
                        declarationNodes.putIfAbsent(definitionNode, Map.entry(captureName, simpleName));
                        log.trace("MATCH [{}]: Found potential definition: Capture [{}], Node Type [{}], Simple Name [{}] -> Storing with determined name.",
                                  match.getId(), captureName, definitionNode.getType(), simpleName);
                    } else {
                        // Expanded logging for null vs blank simpleName
                        if (simpleName == null) {
                            log.warn("Could not determine simple name (resulted in NULL) for definition capture {} (Node Type [{}], Line {}) in file {} using explicit capture and fallback.",
                                     captureName, definitionNode.getType(), definitionNode.getStartPoint().getRow() + 1, file);
                        } else { // simpleName is not null, but IS blank
                            String defNodeTextPreview = definitionNode.isNull() ? "NULL_DEF_NODE" : textSlice(definitionNode, src).lines().findFirst().orElse("");
                            String nameNodeTextPreview = (nameNode == null || nameNode.isNull()) ? "N/A (nameNode was null or fallback failed)" : textSlice(nameNode, src);
                            log.warn("Determined simple name for definition capture {} (Node Type [{}], Line {}) in file {} is BLANK. Definition will be skipped. DefinitionNode text preview: [{}]. NameNode text preview: [{}]",
                                     captureName, definitionNode.getType(), definitionNode.getStartPoint().getRow() + 1, file,
                                     defNodeTextPreview, nameNodeTextPreview);
                        }
                    }
                }
            }
        } // End main query loop

        // Sort declaration nodes by their start byte to process outer definitions before inner ones.
        // This is crucial for parent lookup.
        if (file.getFileName().equals("vars.py")) {
            log.trace("[vars.py DEBUG] declarationNodes for vars.py: {}", declarationNodes.entrySet().stream()
                .map(entry -> String.format("Node: %s (%s), Capture: %s, Name: %s",
                                        entry.getKey().getType(),
                                        textSlice(entry.getKey(), src).lines().findFirst().orElse("").trim(),
                                        entry.getValue().getKey(),
                                        entry.getValue().getValue()))
                .collect(Collectors.toList()));
            if (declarationNodes.isEmpty()) {
                log.trace("[vars.py DEBUG] declarationNodes for vars.py is EMPTY after query execution.");
            }
        } else if (file.getFileName().equals("declarations.go")) {
            log.trace("[declarations.go DEBUG] declarationNodes before sort: {}", declarationNodes.entrySet().stream()
                .map(entry -> String.format("Node: %s (line %d, text: '%s'), Value: (Capture: %s, Name: %s)",
                                        entry.getKey().getType(),
                                        entry.getKey().getStartPoint().getRow() + 1,
                                        textSlice(entry.getKey(), src).lines().findFirst().orElse("").trim(),
                                        entry.getValue().getKey(),
                                        entry.getValue().getValue()))
                .collect(Collectors.toList()));
             if (declarationNodes.isEmpty()) {
                log.trace("[declarations.go DEBUG] declarationNodes is EMPTY after query execution.");
            }
        }
        List<Map.Entry<TSNode, Map.Entry<String, String>>> sortedDeclarationEntries =
            declarationNodes.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getStartByte()))
                .collect(Collectors.toList());

        TSNode currentRootNode = tree.getRootNode(); // Used for namespace and class chain extraction

        for (var entry : sortedDeclarationEntries) {
            TSNode node = entry.getKey();
            Map.Entry<String, String> defInfo = entry.getValue();
            String primaryCaptureName = defInfo.getKey();
            String simpleName = defInfo.getValue();

            if (simpleName == null || simpleName.isBlank()) {
                log.warn("Simple name was null/blank for node type {} (capture: {}) in file {}. Skipping.",
                         node.getType(), primaryCaptureName, file);
                continue;
            }

            log.trace("Processing definition: Name='{}', Capture='{}', Node Type='{}'",
                      simpleName, primaryCaptureName, node.getType());

            String packageName = determinePackageName(file, node, currentRootNode, src);
            List<String> enclosingClassNames = new ArrayList<>();
            TSNode tempParent = node.getParent();
            while (tempParent != null && !tempParent.isNull() && !tempParent.equals(currentRootNode)) {
                if (isClassLike(tempParent)) {
                    extractSimpleName(tempParent, src).ifPresent(parentName -> { // extractSimpleName is now non-static
                        if (!parentName.isBlank()) enclosingClassNames.add(0, parentName);
                    });
                }
                tempParent = tempParent.getParent();
            }
            String classChain = String.join("$", enclosingClassNames);
            log.trace("Computed classChain for simpleName='{}': '{}'", simpleName, classChain);

            // Adjust simpleName and classChain for Go methods to correctly include the receiver type
            if (project.getAnalyzerLanguage() == Language.GO && "method.definition".equals(primaryCaptureName)) {
                // The SCM query for Go methods captures `@method.receiver.type` and `@method.identifier`
                // `simpleName` at this point is from `@method.identifier` (e.g., "MyMethod")
                // We need to find the receiver type from the original captures for this match
                // The `capturedNodes` map (re-populated per match earlier in the loop) is not directly available here.
                // We need to re-access the specific captures for the current `match` associated with `node`.
                // This requires finding the original TSQueryMatch or passing its relevant parts.
                // For now, let's assume `node` is the `method_declaration` node, and we can query its children.
                // A more robust way would be to pass `capturedNodes` from the outer loop or re-query for this specific `node`.

                TSNode receiverNode = null;
                // TSNode methodIdentifierNode = null; // This would be `node.getChildByFieldName("name")` for method_declaration
                                                    // or more reliably, the node associated with captureName.replace(".definition", ".name")
                                                    // simpleName is already derived from method.identifier.

                // Re-evaluate captures specific to this `node` (method_definition)
                // This is a simplified re-querying logic. A more efficient approach might involve
                // passing the full `capturedNodes` map associated with the `match` that led to this `node`.
                TSQueryCursor an_cursor = new TSQueryCursor();
                TSQuery currentThreadQueryForNode = this.query.get(); // Get thread-specific query for this operation
                an_cursor.exec(currentThreadQueryForNode, node); // Execute query only on the current definition node
                TSQueryMatch an_match = new TSQueryMatch();
                Map<String, TSNode> localCaptures = new HashMap<>();
                if (an_cursor.nextMatch(an_match)) { // Should find one match for the definition node itself
                    for (TSQueryCapture capture : an_match.getCaptures()) {
                        String capName = currentThreadQueryForNode.getCaptureNameForId(capture.getIndex());
                        localCaptures.put(capName, capture.getNode());
                    }
                }
                // an_cursor.close(); // TSQueryCursor does not have close

                receiverNode = localCaptures.get("method.receiver.type");

                if (receiverNode != null && !receiverNode.isNull()) {
                    String receiverTypeText = textSlice(receiverNode, src).trim();
                    if (receiverTypeText.startsWith("*")) { // Handle pointer receivers like *MyStruct
                        receiverTypeText = receiverTypeText.substring(1).trim();
                    }
                    // Prepend receiver type to simpleName and set classChain to receiver type
                    if (!receiverTypeText.isEmpty()) {
                        simpleName = receiverTypeText + "." + simpleName;
                        classChain = receiverTypeText;
                        log.trace("Go method: Adjusted simpleName to '{}', classChain to '{}'", simpleName, classChain);
                    } else {
                        log.warn("Go method: Receiver type text was empty for node {}. FQN might be incorrect.", textSlice(receiverNode, src));
                    }
                } else {
                    log.warn("Go method: Could not find @method.receiver.type capture for method '{}'. FQN might be incorrect.", simpleName);
                }
            }

            CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName, packageName, classChain);
            log.trace("createCodeUnit returned: {}", cu);

            if (cu == null) {
                log.warn("createCodeUnit returned null for node {} ({})", simpleName, primaryCaptureName);
                continue;
            }

            String signature = buildSignatureString(node, simpleName, src, primaryCaptureName);
            log.trace("Built signature for '{}': [{}]", simpleName, signature == null ? "NULL" : signature.isBlank() ? "BLANK" : signature.lines().findFirst().orElse("EMPTY"));

            if (file.getFileName().equals("vars.py") && primaryCaptureName.equals("field.definition")) {
                log.trace("[vars.py DEBUG] Processing entry for vars.py field: Node Type='{}', SimpleName='{}', CaptureName='{}', PackageName='{}', ClassChain='{}'",
                         node.getType(), simpleName, primaryCaptureName, packageName, classChain);
                log.trace("[vars.py DEBUG] CU created: {}, Signature: [{}]", cu, signature == null ? "NULL_SIG" : signature.isBlank() ? "BLANK_SIG" : signature.lines().findFirst().orElse("EMPTY_SIG"));
            }

            if (signature == null || signature.isBlank()) {
                // buildSignatureString might legitimately return blank for some nodes that don't form part of a textual skeleton but create a CU.
                // However, if it's blank, it shouldn't be added to signatures map.
                log.debug("buildSignatureString returned empty/null for node {} ({}), simpleName {}. This CU might not have a direct textual signature.", node.getType(), primaryCaptureName, simpleName);
                continue;
            }
            
            // Handle potential duplicates (e.g. JS export and direct lexical declaration)
            // This logic might need adjustment if a single CodeUnit (due to fqName equality)
            // can arise from both an exported and non-exported declaration, and we are now
            // collecting multiple signatures. For now, we assume `computeIfAbsent` for signatures handles accumulation,
            // and this "export" preference applies if different `CodeUnit` instances (which are not `equals()`)
            // somehow map to the same `fqName` in `localCuByFqName` before `cu` itself is unified.
            // If overloads result in CodeUnits that are `equals()`, this block is less relevant for them.
            CodeUnit existingCUforKeyLookup = localCuByFqName.get(cu.fqName());
            if (existingCUforKeyLookup != null && !existingCUforKeyLookup.equals(cu)) {
                // This case implies two distinct CodeUnit objects map to the same FQName.
                // The export preference logic might apply here.
                // However, if `cu` is for an overload of `existingCUforKeyLookup` and they are `equals()`,
                // then this block should ideally not be the primary path for handling overload signatures.
                // The current approach of `localSignatures.computeIfAbsent` will handle accumulation for equal CUs.
                // This existing block seems more for replacing a less specific CU with a more specific one (e.g. exported).
                // For now, let's assume this logic remains for such non-overload "duplicate FQName" cases.
                // If it causes issues with overloads, it needs refinement.
                List<String> existingSignatures = localSignatures.get(existingCUforKeyLookup); // Existing signatures for the *other* CU instance
                boolean newIsExported = signature.trim().startsWith("export");
                boolean oldIsExported = (existingSignatures != null && !existingSignatures.isEmpty()) && existingSignatures.getFirst().trim().startsWith("export"); // Check first existing

                if (newIsExported && !oldIsExported) {
                    log.warn("Replacing non-exported CU/signature list for {} with new EXPORTED signature.", cu.fqName());
                    localSignatures.remove(existingCUforKeyLookup); // Remove old CU's signatures
                    // The new signature for `cu` will be added below.
                } else if (!newIsExported && oldIsExported) {
                    log.trace("Keeping existing EXPORTED CU/signature list for {}. Discarding new non-exported signature for current CU.", cu.fqName());
                    continue; // Skip adding this new signature if an exported one exists for a CU with the same FQName
                } else {
                    log.warn("Duplicate CU FQName {} (distinct instances). New signature will be added. Review if this is expected.", cu.fqName());
                }
            }

            if (signature != null && !signature.isBlank()) { // Only add non-blank signatures
                List<String> sigsForCu = localSignatures.computeIfAbsent(cu, k -> new ArrayList<>());
                if (!sigsForCu.contains(signature)) { // Avoid duplicate signature strings for the same CU
                    sigsForCu.add(signature);
                }
            }
            var currentRange = new Range(node.getStartByte(), node.getEndByte(),
                                         node.getStartPoint().getRow(), node.getEndPoint().getRow());
            localSourceRanges.computeIfAbsent(cu, k -> new ArrayList<>()).add(currentRange);
            localCuByFqName.put(cu.fqName(), cu); // Add/overwrite current CU by its FQ name
            localChildren.putIfAbsent(cu, new ArrayList<>()); // Ensure every CU can be a parent

            if (classChain.isEmpty()) {
                localTopLevelCUs.add(cu);
            } else {
                // Parent's shortName is the classChain string itself.
                String parentFqName = cu.packageName().isEmpty() ? classChain
                                     : cu.packageName() + "." + classChain;
                CodeUnit parentCu = localCuByFqName.get(parentFqName);
                if (parentCu != null) {
                    List<CodeUnit> kids = localChildren.computeIfAbsent(parentCu, k -> new ArrayList<>());
                    if (!kids.contains(cu)) { // Prevent adding duplicate children
                        kids.add(cu);
                    }
                } else {
                    log.warn("Could not resolve parent CU for {} using parent FQ name candidate '{}' (derived from classChain '{}'). Treating as top-level for this file.", cu, parentFqName, classChain);
                    localTopLevelCUs.add(cu); // Fallback
                }
            }
            log.trace("Stored/Updated info for CU: {}", cu);
        }

        // After processing all captures, if there were import statements, create a MODULE CodeUnit
        if (!localImportStatements.isEmpty()) {
            String modulePackageName = determinePackageName(file, rootNode, rootNode, src); // Use rootNode for general package name
            // Use a consistent, unique short name for the module CU, e.g., based on filename.
            // Using "_module_" might lead to collisions in maps if not combined with unique package name.
            // For fqName uniqueness, source+packageName+"_module_" should be fine.
            String moduleShortName = "_module_";
            CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

            // Check if a module CU with this FQ name already exists (e.g. from a different file, though unlikely here)
            // or if this logic somehow runs twice for the same file.
            if (!localCuByFqName.containsKey(moduleCU.fqName())) {
                 localTopLevelCUs.add(0, moduleCU); // Add to the beginning for preferred order
                 localCuByFqName.put(moduleCU.fqName(), moduleCU);
                 // Join imports into a single multi-line signature string for the module CU
                 String importBlockSignature = String.join("\n", localImportStatements);
                 localSignatures.computeIfAbsent(moduleCU, k -> new ArrayList<>()).add(importBlockSignature);
                 // Add a general range for the module CU (e.g. entire file or first import to last)
                 // For simplicity, can use the range of the root node or skip detailed range for module CU.
                 // Here, we'll use the root node's range as a placeholder.
                 localSourceRanges.computeIfAbsent(moduleCU, k -> new ArrayList<>())
                                  .add(new Range(rootNode.getStartByte(), rootNode.getEndByte(),
                                                 rootNode.getStartPoint().getRow(), rootNode.getEndPoint().getRow()));
                 log.trace("Created MODULE CU for {} with {} import statements.", file, localImportStatements.size());
            } else {
                log.warn("Module CU for {} with fqName {} already exists. Skipping duplicate module CU creation.", file, moduleCU.fqName());
            }
        }


        log.trace("Finished analyzing {}: found {} top-level CUs (includes {} imports), {} total signatures, {} parent entries, {} source range entries.",
                  file, localTopLevelCUs.size(), localImportStatements.size(), localSignatures.size(), localChildren.size(), localSourceRanges.size());

        // Make internal lists unmodifiable before returning in FileAnalysisResult
        Map<CodeUnit, List<CodeUnit>> finalLocalChildren = new HashMap<>();
        localChildren.forEach((p, kids) -> finalLocalChildren.put(p, Collections.unmodifiableList(kids)));

        Map<CodeUnit, List<Range>> finalLocalSourceRanges = new HashMap<>();
        localSourceRanges.forEach((c, ranges) -> finalLocalSourceRanges.put(c, Collections.unmodifiableList(ranges)));

        return new FileAnalysisResult(Collections.unmodifiableList(localTopLevelCUs),
                                      finalLocalChildren,
                                      localSignatures,
                                      finalLocalSourceRanges,
                                      Collections.unmodifiableList(localImportStatements));
    }


    /* ---------- Signature Building Logic ---------- */

    /** Calculates the leading whitespace indentation for the line the node starts on. */
    private String computeIndentation(TSNode node, String src) {
        int startByte = node.getStartByte();
        int lineStartByte = src.lastIndexOf('\n', startByte - 1);
        if (lineStartByte == -1) {
            lineStartByte = 0; // Start of file
        } else {
            lineStartByte++; // Move past the newline character
        }

        // Find the first non-whitespace character on the line
        int firstCharByte = lineStartByte;
        while (firstCharByte < startByte && Character.isWhitespace(src.charAt(firstCharByte))) {
            firstCharByte++;
        }
        // Ensure we don't include indentation from within the node itself if it starts mid-line after whitespace
        int effectiveStart = Math.min(startByte, firstCharByte);

        // The indentation is the substring from the line start up to the first non-whitespace character.
        // Safety check: ensure lineStartByte <= firstCharByte and firstCharByte is within src bounds
        if (lineStartByte > firstCharByte || firstCharByte > src.length()) {
             log.warn("Indentation calculation resulted in invalid range [{}, {}] for node starting at byte {}",
                      lineStartByte, firstCharByte, startByte);
             return ""; // Return empty string on error
        }
        String indentResult = src.substring(lineStartByte, firstCharByte);
        log.trace("computeIndentation: Node={}, Indent='{}'", node.getType(), indentResult.replace("\t", "\\t").replace("\n", "\\n"));
        return indentResult;
    }


    /**
     * Builds a signature string for a given definition node.
     * This includes decorators and the main declaration line (e.g., class header or function signature).
     * @param simpleName The simple name of the definition, pre-determined by query captures.
     */
    private String buildSignatureString(TSNode definitionNode, String simpleName, String src, String primaryCaptureName) {
        List<String> signatureLines = new ArrayList<>();
        var profile = getLanguageSyntaxProfile();

        TSNode nodeForContent = definitionNode; // Node used for extracting the main signature text
        // TSNode nodeForContext = definitionNode; // Node for contextual things like overall export (kept as definitionNode for now)

        // 1. Handle language-specific structural unwrapping (e.g., export statements, Python's decorated_definition)
        // For JAVASCRIPT:
        if (project.getAnalyzerLanguage() == Language.JAVASCRIPT && "export_statement".equals(definitionNode.getType())) {
            TSNode declarationInExport = definitionNode.getChildByFieldName("declaration");
            if (declarationInExport != null && !declarationInExport.isNull()) {
                nodeForContent = declarationInExport;
            }
        } else if (project.getAnalyzerLanguage() == Language.PYTHON && "decorated_definition".equals(definitionNode.getType())) {
            // Python's decorated_definition: decorators and actual def are children.
            // Process decorators directly here and identify the actual content node.
            for (int i = 0; i < definitionNode.getNamedChildCount(); i++) {
                TSNode child = definitionNode.getNamedChild(i);
                if (profile.decoratorNodeTypes().contains(child.getType())) {
                    signatureLines.add(textSlice(child, src).stripLeading());
                } else if (profile.functionLikeNodeTypes().contains(child.getType()) || profile.classLikeNodeTypes().contains(child.getType())) {
                    nodeForContent = child; // This will be used for the main signature body
                }
            }
        }

        // 2. Handle decorators for languages where they precede the definition
        //    (Skip if Python already handled its specific decorator structure)
        if (!(project.getAnalyzerLanguage() == Language.PYTHON && "decorated_definition".equals(definitionNode.getType()))) {
            List<TSNode> decorators = getPrecedingDecorators(nodeForContent); // Decorators precede the actual content node
            for (TSNode decoratorNode : decorators) {
                signatureLines.add(textSlice(decoratorNode, src).stripLeading());
            }
        }

        // 3. Determine export/visibility prefix based on the actual content node and its context
        //    (e.g. if nodeForContent's parent is an export statement)
        String exportPrefix = getVisibilityPrefix(nodeForContent, src);

        // 4. Build main signature based on type, using nodeForContent
        SkeletonType skeletonType = getSkeletonTypeForCapture(primaryCaptureName);
        switch (skeletonType) {
            case CLASS_LIKE: {
                TSNode bodyNode = nodeForContent.getChildByFieldName(profile.bodyFieldName());
                String classSignatureText;
                if (bodyNode != null && !bodyNode.isNull()) {
                    classSignatureText = textSlice(nodeForContent.getStartByte(), bodyNode.getStartByte(), src).stripTrailing();
                } else {
                    classSignatureText = textSlice(nodeForContent.getStartByte(), nodeForContent.getEndByte(), src).stripTrailing();
                    // Attempt to remove trailing tokens like '{' or ';' if no body node found, to get a cleaner signature part
                    if (classSignatureText.endsWith("{")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                    else if (classSignatureText.endsWith(";")) classSignatureText = classSignatureText.substring(0, classSignatureText.length() - 1).stripTrailing();
                }

                // If exportPrefix is present and classSignatureText also starts with it,
                // remove it from classSignatureText to avoid duplication by renderClassHeader.
                if (!exportPrefix.isEmpty() && !exportPrefix.isBlank() && classSignatureText.startsWith(exportPrefix.strip())) {
                    classSignatureText = classSignatureText.substring(exportPrefix.strip().length()).stripLeading();
                } else if (!exportPrefix.isEmpty() && !exportPrefix.isBlank() && classSignatureText.startsWith(exportPrefix)) { // Check with trailing space too
                     classSignatureText = classSignatureText.substring(exportPrefix.length()).stripLeading();
                }


                String headerLine = assembleClassSignature(nodeForContent, src, exportPrefix, classSignatureText, "");
                if (headerLine != null && !headerLine.isBlank()) signatureLines.add(headerLine);
                break;
            }
            case FUNCTION_LIKE: {
                // Add extra comments determined from the function body
                CodeUnit tempCuForComments = null; // Ideally, resolve CU here if needed by getExtraFunctionComments
                                                // For now, pass null, JsxAnalyzer override will handle bodyNode directly.
                TSNode bodyNodeForComments = nodeForContent.getChildByFieldName(profile.bodyFieldName());
                List<String> extraComments = getExtraFunctionComments(bodyNodeForComments, src, tempCuForComments);
                for (String comment : extraComments) {
                    if (comment != null && !comment.isBlank()) {
                        signatureLines.add(comment); // Comments are added without indent here; buildSkeletonRecursive adds indent.
                    }
                }
                // Pass determined exportPrefix to buildFunctionSkeleton
                buildFunctionSkeleton(nodeForContent, Optional.of(simpleName), src, "", signatureLines, exportPrefix);
                break;
            }
            case FIELD_LIKE: {
                String fieldDeclText = textSlice(nodeForContent, src).stripLeading().strip();
                // If exportPrefix is present and fieldDeclText also starts with it,
                // remove it from fieldDeclText to avoid duplication.
                if (!exportPrefix.isEmpty() && !exportPrefix.isBlank() && fieldDeclText.startsWith(exportPrefix.strip())) {
                    fieldDeclText = fieldDeclText.substring(exportPrefix.strip().length()).stripLeading();
                } else if (!exportPrefix.isEmpty() && !exportPrefix.isBlank() && fieldDeclText.startsWith(exportPrefix)) {
                    fieldDeclText = fieldDeclText.substring(exportPrefix.length()).stripLeading();
                }
                signatureLines.add(exportPrefix + fieldDeclText);
                break;
            }
            case UNSUPPORTED:
            default:
                 log.debug("Unsupported capture name '{}' for signature building (type {}). Using raw text slice, stripped.", primaryCaptureName, skeletonType);
                 signatureLines.add(textSlice(definitionNode, src).stripLeading());
                 break;
        }

        String result = String.join("\n", signatureLines).stripTrailing(); // stripTrailing still useful for multi-line sigs
        log.trace("buildSignatureString: DefNode={}, Capture='{}', Signature (first line): '{}'",
                  definitionNode.getType(), primaryCaptureName, (result.isEmpty() ? "EMPTY" : result.lines().findFirst().orElse("EMPTY")));
        return result;
    }

    /** Renders the opening part of a class-like structure (e.g., "public class Foo {"). */
    protected abstract String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent);
    // renderClassFooter is removed, replaced by getLanguageSpecificCloser
    // buildClassMemberSkeletons is removed from this direct path; children are handled by recursive reconstruction.

    /* ---------- Granular Signature Rendering Callbacks (Formatting) ---------- */
    protected String getFunctionDeclarationKeyword(TSNode funcNode, String src) { return ""; }

    /**
     * Formats the parameter list for a function. Subclasses may override to provide
     * language-specific formatting using the full AST subtree. The default
     * implementation simply returns the raw text of {@code parametersNode}.
     *
     * @param parametersNode The TSNode representing the parameter list.
     * @param src The source code.
     * @return The formatted parameter list text.
     */
    protected String formatParameterList(TSNode parametersNode, String src) {
        return parametersNode == null || parametersNode.isNull() ? "" : textSlice(parametersNode, src);
    }

    /**
     * @deprecated Use {@link #formatParameterList(TSNode, String)} instead for AST-aware formatting.
     * This method is kept for backward compatibility for subclasses that might still override it.
     */
    @Deprecated
    protected String formatParameterList(String paramsText) { return paramsText; }

    /**
     * Formats the return-type portion of a function signature. Subclasses may
     * override to provide language-specific formatting. The default implementation
     * returns the raw text of {@code returnTypeNode} (or an empty string if the
     * node is null).
     *
     * @param returnTypeNode The TSNode representing the return type.
     * @param src The source code.
     * @return The formatted return type text.
     */
    protected String formatReturnType(TSNode returnTypeNode, String src) {
        return returnTypeNode == null || returnTypeNode.isNull() ? "" : textSlice(returnTypeNode, src);
    }

    /**
     * @deprecated Use {@link #formatReturnType(TSNode, String)} instead for AST-aware formatting.
     * This method is kept for backward compatibility for subclasses that might still override it.
     */
    @Deprecated
    protected String formatReturnType(String returnTypeText) { return returnTypeText == null ? "" : returnTypeText; }

    protected String getClassDeclarationKeyword(TSNode classNode, String src) { return ""; }
    protected String formatHeritage(String signatureText) { return signatureText; }

    /* ---------- Granular Signature Rendering Callbacks (Assembly) ---------- */
    protected String assembleFunctionSignature(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        return renderFunctionDeclaration(funcNode, src, exportPrefix, asyncPrefix, functionName, paramsText, returnTypeText, indent);
    }
    protected String assembleClassSignature(TSNode classNode, String src, String exportPrefix, String classSignatureText, String baseIndent) {
        return renderClassHeader(classNode, src, exportPrefix, classSignatureText, baseIndent);
    }

    /**
     * Determines a visibility or export prefix (e.g., "export ", "public ") for a given node.
     * Subclasses can override this to provide language-specific logic.
     * The default implementation returns an empty string.
     *
     * @param node The node to check for visibility/export modifiers.
     * @param src  The source code.
     * @return The visibility or export prefix string.
     */
    protected String getVisibilityPrefix(TSNode node, String src) {
        return ""; // Default implementation returns an empty string
    }

    /**
     * Builds the function signature lines.
     * @param funcNode The TSNode for the function definition.
     * @param providedNameOpt Optional pre-determined name (e.g. from a specific capture).
     * @param src Source code.
     * @param indent Indentation string.
     * @param lines List to add signature lines to.
     * @param outerExportPrefix Pre-determined export prefix from the broader context (e.g. if function is in an export statement).
     */
    protected void buildFunctionSkeleton(TSNode funcNode, Optional<String> providedNameOpt, String src, String indent, List<String> lines, String outerExportPrefix) {
        var profile = getLanguageSyntaxProfile();
        String functionName;
        TSNode nameNode = funcNode.getChildByFieldName(profile.identifierFieldName());

        if (nameNode != null && !nameNode.isNull()) {
            functionName = textSlice(nameNode, src);
        } else if (providedNameOpt.isPresent()) {
            functionName = providedNameOpt.get();
        } else {
            // Try to extract name using extractSimpleName as a last resort if the specific field isn't found/helpful
            // This could happen for anonymous functions or if identifierFieldName isn't 'name' and not directly on funcNode.
            Optional<String> extractedNameOpt = extractSimpleName(funcNode, src);
            if (extractedNameOpt.isPresent()) {
                functionName = extractedNameOpt.get();
            } else {
                String funcNodeText = textSlice(funcNode, src);
                log.warn("Function node type {} has no name field '{}' and no name was provided or extracted. Raw text: {}",
                         funcNode.getType(), profile.identifierFieldName(), funcNodeText.lines().findFirst().orElse(""));
                lines.add(indent + funcNodeText);
                log.warn("-> Falling back to raw text slice for function skeleton due to missing name.");
                return;
            }
        }

        TSNode paramsNode = funcNode.getChildByFieldName(profile.parametersFieldName());
        TSNode returnTypeNode = null;
        if (!profile.returnTypeFieldName().isEmpty()) {
            returnTypeNode = funcNode.getChildByFieldName(profile.returnTypeFieldName());
        }
        TSNode bodyNode = funcNode.getChildByFieldName(profile.bodyFieldName());

        // Parameter node is usually essential for a valid function signature.
        if (paramsNode == null || paramsNode.isNull()) {
            // Allow functions without explicit parameter lists if the language syntax supports it (e.g. some JS/Go forms)
            // but log it if it's unusual for the current node type based on typical expectations.
            // If paramsText ends up empty, renderFunctionDeclaration should handle it gracefully.
            log.trace("Parameters node (field '{}') not found for function node type '{}', name '{}'. Assuming empty parameter list.",
                     profile.parametersFieldName(), funcNode.getType(), functionName);
        }

        // Body node might be missing for abstract/interface methods.
        if (bodyNode == null || bodyNode.isNull()) {
            log.trace("Body node (field '{}') not found for function node type '{}', name '{}'. Renderer or placeholder logic must handle this.",
                     profile.bodyFieldName(), funcNode.getType(), functionName);
        }

        // Use the passed-in outerExportPrefix. If it's empty, then allow getVisibilityPrefix to check funcNode itself for local modifiers.
        String exportPrefix = (outerExportPrefix != null && !outerExportPrefix.isEmpty()) ? outerExportPrefix : getVisibilityPrefix(funcNode, src);
        String asyncPrefix = "";
        TSNode firstChildOfFunc = funcNode.getChild(0);
        String asyncKWType = profile.asyncKeywordNodeType();
        if (!asyncKWType.isEmpty() && firstChildOfFunc != null && !firstChildOfFunc.isNull() && asyncKWType.equals(firstChildOfFunc.getType())) {
            asyncPrefix = textSlice(firstChildOfFunc, src).strip() + " "; // Capture "async " or similar from source
        }

        String paramsText = formatParameterList(paramsNode, src); // paramsNode can be null, formatParameterList handles it
        String returnTypeText = formatReturnType(returnTypeNode, src); // returnTypeNode can be null

        String functionLine = assembleFunctionSignature(funcNode, src, exportPrefix, asyncPrefix, functionName, paramsText, returnTypeText, indent);

        // Add extra comments (e.g., // mutates: ...) before the function line if they exist
        // These comments should not have the 'indent' applied here, as assembleFunctionSignature might return a multi-line string already.
        // The expectation is that buildSignatureString handles the overall structure.
        // Let's refine: getExtraFunctionComments are added to `lines` before the main signature.
        // The main `functionLine` from `assembleFunctionSignature` could itself be multi-line if it includes the body placeholder.

        CodeUnit currentCu = null; // Need to retrieve or construct the CU for getExtraFunctionComments
        // This requires resolving fqName or having CU available here. For now, pass null or skip.
        // A better approach: pass the CU into buildFunctionSkeleton if available.
        // Or, getExtraFunctionComments is called from buildSignatureString where CU is known.
        // For now, cannot call getExtraFunctionComments here without CU.
        // Let's assume getExtraFunctionComments is called from a place with CU context.
        //
        // Re-thinking: buildFunctionSkeleton is called by buildSignatureString.
        // buildSignatureString has the CU. It can call getExtraFunctionComments and pass to buildFunctionSkeleton.
        //
        // Simpler: assembleFunctionSignature itself should integrate these.
        // So, the List<String> lines passed to buildFunctionSkeleton should be used.
        //
        // Current path: buildSignatureString -> buildFunctionSkeleton(lines.add(...))
        // -> assembleFunctionSignature (returns String) -> lines.add(result of assembleFunctionSignature)
        //
        // New path: buildSignatureString -> calls getExtraFunctionComments -> adds to 'signatureLines'
        //             buildSignatureString -> calls buildFunctionSkeleton(signatureLines.add(...))
        // This means `buildFunctionSkeleton` adds its line (from `assembleFunctionSignature`) to the list.

        if (functionLine != null && !functionLine.isBlank()) {
            // Extra comments are now added by buildSignatureString directly to the lines list.
            // This method just adds the main functionLine.
            // The `indent` parameter for this method is the base indent for the CU, and
            // `functionLine` is expected to be a single conceptual signature line (possibly multi-line text from placeholder).
            // `reconstructSkeletonRecursive` handles the overall indent for the CU.
            // `assembleFunctionSignature` is responsible for the internal structure of `functionLine`.
            lines.add(functionLine);
        }
    }

    /**
     * Retrieves extra comment lines to be added to a function's skeleton, typically before the body.
     * Example: mutation tracking comments.
     *
     * @param bodyNode The TSNode representing the function's body. Can be null.
     * @param src The source code.
     * @param functionCu The CodeUnit for the function. Can be null if not available.
     * @return A list of comment strings, or an empty list if none.
     */
    protected List<String> getExtraFunctionComments(TSNode bodyNode, String src, CodeUnit functionCu) {
        return List.of(); // Default: no extra comments
    }


    protected abstract String bodyPlaceholder();

    /**
     * Renders the complete declaration line for a function, including any prefixes, name, parameters,
     * return type, and language-specific syntax like "def" or "function" keywords, colons, or braces.
     * Implementations are responsible for constructing the entire line, including indentation and any
     * language-specific body placeholder if the function body is not empty or trivial.
     *
     * @param funcNode The Tree-sitter node representing the function.
     * @param src The source code of the file.
     * @param exportPrefix The export prefix (e.g., "export ") if applicable, otherwise empty.
     * @param asyncPrefix The async prefix (e.g., "async ") if applicable, otherwise empty.
     * @param functionName The name of the function.
     * @param paramsText The text content of the function's parameters.
     * @param returnTypeText The text content of the function's return type, or empty if none.
     * @param indent The base indentation string for this line.
     * @return The fully rendered function declaration line, or null/blank if it should not be added.
     */
    protected abstract String renderFunctionDeclaration(TSNode funcNode,
                                                        String src,
                                                        String exportPrefix,
                                                        String asyncPrefix,
                                                        String functionName,
                                                        String paramsText,
                                                        String returnTypeText,
                                                        String indent);

    /** Finds decorator nodes immediately preceding a given node. */
    private List<TSNode> getPrecedingDecorators(TSNode decoratedNode) {
        List<TSNode> decorators = new ArrayList<>();
        var decoratorNodeTypes = getLanguageSyntaxProfile().decoratorNodeTypes();
        if (decoratorNodeTypes.isEmpty()) {
            return decorators;
        }
        TSNode current = decoratedNode.getPrevSibling();
        while (current != null && !current.isNull() && decoratorNodeTypes.contains(current.getType())) {
            decorators.add(current);
            current = current.getPrevSibling();
        }
        Collections.reverse(decorators); // Decorators should be in source order
        return decorators;
    }


    /** Extracts a substring from the source code based on node boundaries. */
    protected String textSlice(TSNode node, String src) {
        if (node == null || node.isNull()) return "";
        
        // Get the byte array representation of the source 
        // This may be cached for better performance in a real implementation
        byte[] bytes;
        try {
            bytes = src.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fallback in case of encoding error
            log.warn("Error getting bytes from source: {}. Falling back to substring (may truncate UTF-8 content)", e.getMessage());
            return src.substring(Math.min(node.getStartByte(), src.length()), 
                                Math.min(node.getEndByte(), src.length()));
        }
        
        // Extract using correct byte indexing
        return textSliceFromBytes(node.getStartByte(), node.getEndByte(), bytes);
    }

    /** Extracts a substring from the source code based on byte offsets. */
    protected String textSlice(int startByte, int endByte, String src) {
        // Get the byte array representation of the source
        byte[] bytes;
        try {
            bytes = src.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fallback in case of encoding error
            log.warn("Error getting bytes from source: {}. Falling back to substring (may truncate UTF-8 content)", e.getMessage());
            return src.substring(Math.min(startByte, src.length()), 
                                Math.min(endByte, src.length()));
        }
        
        return textSliceFromBytes(startByte, endByte, bytes);
    }
    
    /** Helper method that correctly extracts UTF-8 byte slice into a String */
    private String textSliceFromBytes(int startByte, int endByte, byte[] bytes) {
        if (startByte < 0 || endByte > bytes.length || startByte >= endByte) {
            log.warn("Invalid byte range [{}, {}] for byte array of length {}",
                    startByte, endByte, bytes.length);
            return "";
        }
        
        int len = endByte - startByte;
        return new String(bytes, startByte, len, StandardCharsets.UTF_8);
    }


    /* ---------- helpers ---------- */

    /**
     * Fallback to extract a simple name from a declaration node when an explicit `.name` capture isn't found.
     * Tries finding a child node with field name specified in LanguageSyntaxProfile.
     * Needs the source string `src` for substring extraction.
     */
    protected Optional<String> extractSimpleName(TSNode decl, String src) {
        Optional<String> nameOpt = Optional.empty();
        String identifierFieldName = getLanguageSyntaxProfile().identifierFieldName();
        if (identifierFieldName.isEmpty()) {
            log.warn("Identifier field name is empty in LanguageSyntaxProfile for node type {} at line {}. Cannot extract simple name by field.",
                     decl.getType(), decl.getStartPoint().getRow() + 1);
            return Optional.empty();
        }

        try {
            TSNode nameNode = decl.getChildByFieldName(identifierFieldName);
            if (nameNode != null && !nameNode.isNull()) {
                nameOpt = Optional.of(src.substring(nameNode.getStartByte(), nameNode.getEndByte()));
            } else {
                 log.warn("getChildByFieldName('{}') returned null or isNull for node type {} at line {}",
                          identifierFieldName, decl.getType(), decl.getStartPoint().getRow() + 1);
            }
        } catch (Exception e) {
             log.warn("Error extracting simple name using field '{}' from node type {} for node starting with '{}...': {}",
                      identifierFieldName, decl.getType(), src.substring(decl.getStartByte(), Math.min(decl.getEndByte(), decl.getStartByte() + 20)), e.getMessage());
        }

        if (nameOpt.isEmpty()) {
            log.warn("extractSimpleName: Failed using getChildByFieldName('{}') for node type {} at line {}",
                     identifierFieldName, decl.getType(), decl.getStartPoint().getRow() + 1);
        }
        log.trace("extractSimpleName: DeclNode={}, IdentifierField='{}', ExtractedName='{}'",
                  decl.getType(), identifierFieldName, nameOpt.orElse("N/A"));
        return nameOpt;
    }

    private static String loadResource(String path) {
        try (InputStream in = TreeSitterAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        Set<String> symbols = new HashSet<>();
        Queue<CodeUnit> toProcess = new LinkedList<>(sources);
        Set<CodeUnit> visited = new HashSet<>();

        while (!toProcess.isEmpty()) {
            CodeUnit current = toProcess.poll();
            if (!visited.add(current)) { // If already visited, skip
                continue;
            }

            String shortName = current.shortName();
            if (shortName == null || shortName.isEmpty()) {
                continue;
            }

            int lastDot = shortName.lastIndexOf('.');
            int lastDollar = shortName.lastIndexOf('$');
            int lastSeparator = Math.max(lastDot, lastDollar);

            String unqualifiedName;
            if (lastSeparator == -1) {
                unqualifiedName = shortName;
            } else {
                // Ensure there's something after the separator
                if (lastSeparator < shortName.length() - 1) {
                    unqualifiedName = shortName.substring(lastSeparator + 1);
                } else {
                    // This case (e.g., shortName ends with '.') should ideally not happen
                    // with valid CodeUnit shortNames, but handle defensively.
                    unqualifiedName = ""; // Or log a warning
                }
            }

            if (!unqualifiedName.isEmpty()) {
                symbols.add(unqualifiedName);
            }

            List<CodeUnit> children = childrenByParent.getOrDefault(current, Collections.emptyList());
            for (CodeUnit child : children) {
                if (!visited.contains(child)) { // Add to queue only if not already processed or in queue
                    toProcess.add(child);
                }
            }
        }
        return symbols;
    }
}
