package ai.brokk.analyzer;

import static ai.brokk.analyzer.python.PythonTreeSitterNodeTypes.*;

import ai.brokk.project.IProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TreeSitterPython;

public final class PythonAnalyzer extends TreeSitterAnalyzer {
    // Python's "last wins" behavior is handled by TreeSitterAnalyzer's addTopLevelCodeUnit().

    // Import resolution using TreeSitter queries instead of regex

    @Override
    public Optional<String> extractClassName(String reference) {
        return ClassNameExtractor.extractForPython(reference);
    }

    // PY_LANGUAGE field removed, createTSLanguage will provide new instances.
    private static final LanguageSyntaxProfile PY_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DEFINITION),
            Set.of(FUNCTION_DEFINITION),
            Set.of(ASSIGNMENT, TYPED_PARAMETER),
            Set.of(DECORATOR),
            IMPORT_DECLARATION,
            "name", // identifierFieldName
            "body", // bodyFieldName
            "parameters", // parametersFieldName
            "return_type", // returnTypeFieldName
            "", // typeParametersFieldName (Python doesn't have explicit type parameters)
            Map.of( // captureConfiguration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "async", // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
            );

    public PythonAnalyzer(IProject project) {
        super(project, Languages.PYTHON);
    }

    private PythonAnalyzer(IProject project, AnalyzerState state) {
        super(project, Languages.PYTHON, state);
    }

    public static PythonAnalyzer fromState(IProject project, AnalyzerState state) {
        return new PythonAnalyzer(project, state);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new PythonAnalyzer(getProject(), state);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPython();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        // The packageName parameter is now supplied by determinePackageName.
        // The classChain parameter is used for Joern-style short name generation.

        // Extract module name from filename using the inherited getFileName() method
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3); // e.g., "A"
        }

        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION -> {
                log.trace(
                        "Creating class: simpleName='{}', classChain='{}', packageName='{}'",
                        simpleName,
                        classChain,
                        packageName);
                String finalShortName;
                if (classChain.isEmpty()) {
                    finalShortName = simpleName;
                } else if (!classChain.contains("$")) {
                    // Function-local class: replace dots with $ (e.g., "func.Outer" → "func$Outer$Inner")
                    String normalizedChain = classChain.replace(".", "$");
                    finalShortName = normalizedChain + "$" + simpleName;
                } else {
                    // Regular nested class or already processed function-local chain (contains $)
                    finalShortName = classChain + "$" + simpleName;
                }
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> {
                // Methods always use dot notation (parent classes use $)
                String finalShortName =
                        classChain.isEmpty() ? (moduleName + "." + simpleName) : (classChain + "." + simpleName);
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> { // For class attributes or top-level variables
                String finalShortName;
                if (classChain.isEmpty()) {
                    // Top-level variables use "moduleName.variableName" (consistent with functions)
                    finalShortName = moduleName + "." + simpleName;
                } else {
                    finalShortName = classChain + "." + simpleName;
                }

                // Duplicates handled by addTopLevelCodeUnit() ("last wins" for Python)
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                // Log or handle unexpected captures if necessary
                log.debug("Ignoring capture: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null; // Returning null ignores the capture
            }
        };
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected boolean shouldSkipNode(TSNode node, String captureName, byte[] srcBytes) {
        // Skip property setters to avoid duplicates with property getters
        if (CaptureNames.FUNCTION_DEFINITION.equals(captureName) && "decorated_definition".equals(node.getType())) {
            // Check if this is a property setter by looking at decorators
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode child = node.getNamedChild(i);
                if ("decorator".equals(child.getType())) {
                    TSNode decoratorChild = child.getNamedChild(0);
                    if (decoratorChild != null && "attribute".equals(decoratorChild.getType())) {
                        // Get the decorator text using the inherited textSlice method
                        String decoratorText =
                                textSlice(decoratorChild, srcBytes).trim();
                        // Skip property setters/deleters: match "<name>.(setter|deleter)" only
                        if (decoratorText.matches("[^.]+\\.(setter|deleter)")) {
                            log.trace("Skipping property setter/deleter with decorator: {}", decoratorText);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected boolean shouldReplaceOnDuplicate(CodeUnit cu) {
        // Python "last wins" semantics: duplicate definitions replace earlier ones
        return cu.isField() || cu.isClass() || cu.isFunction();
    }

    @Override
    protected boolean hasWrappingDecoratorNode() {
        // Python wraps decorated definitions in a decorated_definition node
        return true;
    }

    @Override
    protected TSNode extractContentFromDecoratedNode(
            TSNode decoratedNode, List<String> outDecoratorLines, byte[] srcBytes, LanguageSyntaxProfile profile) {
        // Python's decorated_definition: decorators and actual definition are children
        // Process decorators and identify the actual content node
        TSNode nodeForContent = decoratedNode;
        for (int i = 0; i < decoratedNode.getNamedChildCount(); i++) {
            TSNode child = decoratedNode.getNamedChild(i);
            if (profile.decoratorNodeTypes().contains(child.getType())) {
                outDecoratorLines.add(textSlice(child, srcBytes).stripLeading());
            } else if (profile.functionLikeNodeTypes().contains(child.getType())
                    || profile.classLikeNodeTypes().contains(child.getType())) {
                nodeForContent = child;
            }
        }
        return nodeForContent;
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        String pyReturnTypeSuffix = !returnTypeText.isEmpty() ? " -> " + returnTypeText : "";
        // The 'indent' parameter is now "" when called from buildSignatureString,
        // so it's effectively ignored here for constructing the stored signature.
        String signature = String.format(
                "%s%sdef %s%s%s:", exportPrefix, asyncPrefix, functionName, paramsText, pyReturnTypeSuffix);

        TSNode bodyNode = funcNode.getChildByFieldName("body");
        boolean hasMeaningfulBody = bodyNode != null
                && !bodyNode.isNull()
                && (bodyNode.getNamedChildCount() > 1
                        || (bodyNode.getNamedChildCount() == 1
                                && !"pass_statement"
                                        .equals(bodyNode.getNamedChild(0).getType())));

        if (hasMeaningfulBody) {
            return signature + " " + bodyPlaceholder(); // Do not prepend indent here
        } else {
            return signature; // Do not prepend indent here
        }
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return signatureText; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return ""; // Python uses indentation, no explicit closer for classes/functions
    }
    /**
     * Determines the package name for a Python file based on its directory structure
     * and __init__.py markers.
     *
     * @param file The Python file
     * @return The package name (dot-separated), or empty string if at root
     */
    private String getPackageNameForFile(ProjectFile file) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        var absPath = file.absPath();
        var projectRoot = getProject().getRoot();
        var parentDir = absPath.getParent();

        // If the file is directly in the project root, the package path is empty
        if (parentDir == null || parentDir.equals(projectRoot)) {
            return "";
        }

        // Find the highest directory containing __init__.py between project root and the file's parent
        var effectivePackageRoot = projectRoot;
        var current = parentDir;
        while (current != null && !current.equals(projectRoot)) {
            if (Files.exists(current.resolve("__init__.py"))) {
                effectivePackageRoot = current; // Found a potential root, keep checking higher
            }
            current = current.getParent();
        }

        // Calculate the relative path from the (parent of the effective package root OR project root)
        // to the file's parent directory.
        Path rootForRelativize =
                effectivePackageRoot.equals(projectRoot) ? projectRoot : effectivePackageRoot.getParent();
        if (rootForRelativize == null) { // Should not happen if projectRoot is valid
            rootForRelativize = projectRoot;
        }

        // If parentDir is not under rootForRelativize (e.g. parentDir is projectRoot, effectivePackageRoot is deeper
        // due to missing __init__.py)
        // or if parentDir is the same as rootForRelativize, then there's no relative package path.
        if (!parentDir.startsWith(rootForRelativize) || parentDir.equals(rootForRelativize)) {
            return "";
        }

        var relPath = rootForRelativize.relativize(parentDir);

        // Convert path separators to dots for package name
        return relPath.toString().replace('/', '.').replace('\\', '.');
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        // The definitionNode, rootNode, and src parameters are not used for Python package determination.
        return getPackageNameForFile(file);
    }

    @Override
    protected String buildParentFqName(CodeUnit cu, String classChain) {
        // Transform function-local class chains: "func.LocalClass" → "func$LocalClass"
        // Detection: lowercase start = function (per PEP 8)

        // Extract module name from filename for function FQN construction
        String moduleName = cu.source().getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3);
        }

        String packageName = cu.packageName();

        if (!classChain.isBlank()) {
            // Extract first segment to determine if this is function-local
            String firstSegment;
            int dotIndex = classChain.indexOf('.');
            int dollarIndex = classChain.indexOf('$');
            if (dotIndex >= 0 && (dollarIndex < 0 || dotIndex < dollarIndex)) {
                firstSegment = classChain.substring(0, dotIndex);
            } else if (dollarIndex >= 0) {
                firstSegment = classChain.substring(0, dollarIndex);
            } else {
                firstSegment = classChain;
            }

            // Check if classChain starts with a function (lowercase = function, PascalCase = class)
            boolean isFunctionLocal = isLowercaseIdentifier(firstSegment);

            if (isFunctionLocal) {
                // Single element (module-level function) - need to prepend module name
                // Function FQNs are stored as "moduleName.functionName" even when packageName is empty
                if (!classChain.contains(".") && !classChain.contains("$")) {
                    // Build FQN matching how functions are stored: moduleName.functionName
                    String functionFqn = moduleName + "." + classChain;
                    log.trace(
                            "Python parent lookup: classChain='{}', module='{}', returning function FQN '{}'",
                            classChain,
                            moduleName,
                            functionFqn);
                    return packageName.isEmpty() ? functionFqn : packageName + "." + functionFqn;
                }

                // Function-local class hierarchy: transform dots to $
                // Don't prepend module name - only top-level functions get module prefix
                if (!classChain.contains("$") && classChain.contains(".")) {
                    String transformed = classChain.replace(".", "$");
                    log.trace(
                            "Python parent lookup: classChain='{}', transformed to '{}' for function-local class lookup",
                            classChain,
                            transformed);
                    return packageName.isEmpty() ? transformed : packageName + "." + transformed;
                }
            }
        }

        log.trace(
                "Python parent lookup: packageName='{}', classChain='{}', using default join", packageName, classChain);
        // Default behavior for regular nested classes
        return Stream.of(packageName, classChain).filter(s -> !s.isBlank()).collect(Collectors.joining("."));
    }

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile.
    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PY_SYNTAX_PROFILE;
    }

    @Override
    protected boolean requiresSemicolons() {
        return false;
    }

    /**
     * Checks if identifier follows Python function naming (lowercase start) vs class naming (PascalCase).
     *
     * @param identifier the identifier to check (e.g., "my_function", "_private", "MyClass")
     * @return true if first letter is lowercase (function naming convention per PEP 8)
     */
    private static boolean isLowercaseIdentifier(String identifier) {
        // Find first letter character (skip leading underscores)
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isLowerCase(c);
            }
        }
        // All underscores (like "____") or empty - treat as function-like
        return true;
    }

    /**
     * Include functions as class-like parents to detect local classes inside functions.
     */
    @Override
    protected boolean isClassLike(TSNode node) {
        return super.isClassLike(node) || "function_definition".equals(node.getType());
    }

    @Override
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, TSNode classNode, String signature, String src) {
        // Extract superclass names from Python class definition
        // Pattern: class Child(Parent1, Parent2): ...
        var query = getThreadLocalQuery();

        // Ascend to root node for matching
        TSNode root = classNode;
        while (root.getParent() != null && !root.getParent().isNull()) {
            root = root.getParent();
        }

        var cursor = new TSQueryCursor();
        cursor.exec(query, root);

        var match = new TSQueryMatch();
        List<TSNode> aggregateSuperNodes = new ArrayList<>();

        final int targetStart = classNode.getStartByte();
        final int targetEnd = classNode.getEndByte();

        while (cursor.nextMatch(match)) {
            TSNode declNode = null;
            List<TSNode> superCapturesThisMatch = new ArrayList<>();

            for (var cap : match.getCaptures()) {
                var capName = query.getCaptureNameForId(cap.getIndex());
                var n = cap.getNode();
                if (n == null || n.isNull()) continue;

                if ("type.decl".equals(capName)) {
                    declNode = n;
                } else if ("type.super".equals(capName)) {
                    superCapturesThisMatch.add(n);
                }
            }

            if (declNode != null && declNode.getStartByte() == targetStart && declNode.getEndByte() == targetEnd) {
                aggregateSuperNodes.addAll(superCapturesThisMatch);
            }
        }

        // Sort by position to preserve source order
        aggregateSuperNodes.sort(Comparator.comparingInt(TSNode::getStartByte));

        List<String> supers = new ArrayList<>(aggregateSuperNodes.size());
        for (var s : aggregateSuperNodes) {
            var text = textSlice(s, src).strip();
            if (!text.isEmpty()) {
                supers.add(text);
            }
        }

        // Deduplicate while preserving order
        var unique = new LinkedHashSet<>(supers);
        return List.copyOf(unique);
    }

    /**
     * Resolves a relative import to an absolute package path.
     *
     * @param file The file containing the import
     * @param relativeImportText The text of the relative_import node (e.g., ".sibling", "..parent", "...")
     * @return The absolute package path, or empty if resolution fails
     */
    private Optional<String> resolveRelativeImport(ProjectFile file, String relativeImportText) {
        // Count leading dots
        int dotCount = 0;
        while (dotCount < relativeImportText.length() && relativeImportText.charAt(dotCount) == '.') {
            dotCount++;
        }

        // Get the module name after the dots (if any)
        String relativeModule = relativeImportText.substring(dotCount);

        // Get the current file's package
        String currentPackage = getPackageNameForFile(file);

        // Navigate up dotCount-1 levels (1 dot = current package, 2 dots = parent, etc.)
        String[] packageParts = currentPackage.isEmpty() ? new String[0] : currentPackage.split("\\.");
        int levelsUp = dotCount - 1;

        if (levelsUp > packageParts.length) {
            // Import goes above project root - invalid
            log.warn("Relative import {} in {} goes above project root", relativeImportText, file.getRelPath());
            return Optional.empty();
        }

        // Build target package
        String[] targetParts = new String[packageParts.length - levelsUp];
        System.arraycopy(packageParts, 0, targetParts, 0, targetParts.length);
        String targetPackage = String.join(".", targetParts);

        // Append the relative module name if present
        if (!relativeModule.isEmpty()) {
            if (!targetPackage.isEmpty()) {
                targetPackage = targetPackage + "." + relativeModule;
            } else {
                targetPackage = relativeModule;
            }
        }

        return Optional.of(targetPackage);
    }

    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        Set<CodeUnit> resolved = new LinkedHashSet<>();

        for (String importLine : importStatements) {
            if (importLine.isBlank()) continue;

            // Parse the import statement with TreeSitter
            // Note: We parse it as a complete module (TreeSitter expects valid Python)
            var parser = getTSParser();
            var tree = parser.parseString(null, importLine);
            var rootNode = tree.getRootNode();

            var query = getThreadLocalQuery();
            var cursor = new TSQueryCursor();
            cursor.exec(query, rootNode);

            var match = new TSQueryMatch();
            String currentModule = null;

            // Collect all captures from this import statement
            while (cursor.nextMatch(match)) {
                for (var cap : match.getCaptures()) {
                    var capName = query.getCaptureNameForId(cap.getIndex());
                    var node = cap.getNode();
                    if (node == null || node.isNull()) continue;

                    var text = textSlice(node, importLine);

                    switch (capName) {
                        case IMPORT_MODULE -> currentModule = text;
                        case IMPORT_RELATIVE -> {
                            // Resolve relative import to absolute package path
                            var absolutePath = resolveRelativeImport(file, text);
                            currentModule = absolutePath.orElse(null);
                        }
                        case IMPORT_NAME -> {
                            // For "from X import Y" style, we need the module
                            if (currentModule != null && !text.equals("*")) {
                                // In Python, modules (files) don't add a level to class FQNs
                                // "from package.module import Class" means:
                                //   - Look for Class in file package/module.py
                                //   - The FQN will be package.Class, not package.module.Class

                                // Try to find the class in the module file
                                var moduleFilePath = currentModule.replace('.', '/') + ".py";
                                try {
                                    var moduleFile =
                                            new ProjectFile(getProject().getRoot(), moduleFilePath);
                                    var decls = getDeclarations(moduleFile);
                                    decls.stream()
                                            .filter(cu -> cu.identifier().equals(text) && cu.isClass())
                                            .findFirst()
                                            .ifPresent(resolved::add);
                                } catch (Exception e) {
                                    log.warn(
                                            "Could not resolve import '{}' from module {}: {}",
                                            text,
                                            currentModule,
                                            e.getMessage());
                                }
                            } else if (currentModule == null) {
                                // For "import X" style
                                var found = getDefinition(text);
                                if (found.isPresent() && found.get().isClass()) {
                                    resolved.add(found.get());
                                }
                            }
                        }
                            // Note: IMPORT_ALIAS captures the alias name, but we don't need it
                            // for resolution - we only care about the original name
                    }
                }
            }
        }

        return Collections.unmodifiableSet(resolved);
    }

    @Override
    public List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Get raw supertype names from CodeUnitProperties
        var rawNames = withCodeUnitProperties(
                props -> props.getOrDefault(cu, CodeUnitProperties.empty()).rawSupertypes());

        if (rawNames.isEmpty()) {
            return List.of();
        }

        // Get resolved imports for this file
        Set<CodeUnit> resolvedImports = importedCodeUnitsOf(cu.source());

        List<CodeUnit> result = new ArrayList<>();

        for (String rawName : rawNames) {
            // First try to find in imports
            Optional<CodeUnit> fromImport = resolvedImports.stream()
                    .filter(imp -> imp.identifier().equals(rawName))
                    .findFirst();

            if (fromImport.isPresent()) {
                result.add(fromImport.get());
                continue;
            }

            // Then try same package (same file or same directory)
            String packageName = cu.packageName();
            String fqnInPackage = packageName.isEmpty() ? rawName : packageName + "." + rawName;
            Optional<CodeUnit> inPackage = getDefinition(fqnInPackage);
            if (inPackage.isPresent() && inPackage.get().isClass()) {
                result.add(inPackage.get());
                continue;
            }

            // Try global search
            var searchResults = searchDefinitions(rawName, false);
            Optional<CodeUnit> fromSearch =
                    searchResults.stream().filter(CodeUnit::isClass).findFirst();
            fromSearch.ifPresent(result::add);
        }

        return result;
    }
}
