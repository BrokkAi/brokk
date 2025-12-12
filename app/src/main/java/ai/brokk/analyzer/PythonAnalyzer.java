package ai.brokk.analyzer;

import static ai.brokk.analyzer.python.PythonTreeSitterNodeTypes.*;

import ai.brokk.project.IProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TreeSitterPython;

public final class PythonAnalyzer extends TreeSitterAnalyzer {
    // Python's "last wins" behavior is handled by TreeSitterAnalyzer's addTopLevelCodeUnit().

    @Override
    public Optional<String> extractCallReceiver(String reference) {
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
        this(project, ProgressListener.NOOP);
    }

    public PythonAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.PYTHON, listener);
    }

    private PythonAnalyzer(IProject project, AnalyzerState state, ProgressListener listener) {
        super(project, Languages.PYTHON, state, listener);
    }

    public static PythonAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
        return new PythonAnalyzer(project, state, listener);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state, ProgressListener listener) {
        return new PythonAnalyzer(getProject(), state, listener);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPython();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    /**
     * Encapsulates Python package/module resolution, handling __init__.py semantics in one place.
     * For __init__.py files, the last package segment becomes the module name to match Python import semantics
     * (e.g., "from mypackage import ClassName" works when ClassName is in mypackage/__init__.py).
     */
    private record PythonModuleInfo(String packageName, String moduleName) {
        /**
         * Returns the fully qualified module path (packageName.moduleName or just moduleName).
         * This is the prefix for all FQNs in this file.
         */
        String moduleQualifiedPackage() {
            return packageName.isEmpty() ? moduleName : packageName + "." + moduleName;
        }
    }

    /**
     * Resolves the package and module name for a Python file, handling __init__.py semantics.
     */
    private PythonModuleInfo resolveModuleInfo(ProjectFile file) {
        String rawPackage = getPackageNameForFile(file);

        // Extract module name from filename
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3);
        }

        // For __init__.py, fold last package segment into module name
        if (moduleName.equals("__init__") && !rawPackage.isEmpty()) {
            int lastDot = rawPackage.lastIndexOf('.');
            if (lastDot == -1) {
                // "mypackage" -> module="mypackage", pkg=""
                return new PythonModuleInfo("", rawPackage);
            } else {
                // "mypackage.subpkg" -> module="subpkg", pkg="mypackage"
                return new PythonModuleInfo(rawPackage.substring(0, lastDot), rawPackage.substring(lastDot + 1));
            }
        }

        return new PythonModuleInfo(rawPackage, moduleName);
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            List<ScopeSegment> scopeChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        // packageName is already module-qualified (from determinePackageName via resolveModuleInfo)
        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION -> {
                log.trace(
                        "Creating class: simpleName='{}', scopeChain='{}', packageName='{}'",
                        simpleName,
                        scopeChain,
                        packageName);

                // Design: shortName = class hierarchy only (no module prefix)
                // Module is included in packageName for proper fqName construction
                // Use $ for class nesting, . for function scope
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level class: just "ClassName"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Function-local class: "func$LocalClass" or "func$Outer$Inner"
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Direct child of function
                        finalShortName = first.name() + "$" + simpleName;
                    } else {
                        // Nested in class inside function
                        String restPart = normalizedRest(scopeChain);
                        finalShortName = first.name() + "$" + restPart + "$" + simpleName;
                    }
                } else {
                    // Class-nested: "Outer$Inner"
                    finalShortName = normalized(scopeChain) + "$" + simpleName;
                }

                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> {
                // Functions use . for member access
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level function: just "func"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Nested function or method in function-local class
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Nested function inside function: "outer.inner"
                        finalShortName = first.name() + "." + simpleName;
                    } else {
                        // Method in function-local class: "func$Class.method"
                        finalShortName = first.name() + "$" + normalizedRest(scopeChain) + "." + simpleName;
                    }
                } else {
                    // Method in regular class: "Class.method" or "Outer$Inner.method"
                    finalShortName = normalized(scopeChain) + "." + simpleName;
                }

                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> {
                // Fields use . for member access
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level variable: just "varName"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Field in function-local class
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Variable in function scope (unusual): "func.var"
                        finalShortName = first.name() + "." + simpleName;
                    } else {
                        finalShortName = first.name() + "$" + normalizedRest(scopeChain) + "." + simpleName;
                    }
                } else {
                    // Field in regular class: "Class.field"
                    finalShortName = normalized(scopeChain) + "." + simpleName;
                }

                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture: {} with name: {} and scopeChain: {}", captureName, simpleName, scopeChain);
                yield null;
            }
        };
    }

    /** Join all scope segment names with $ */
    private static String normalized(List<ScopeSegment> scopeChain) {
        return scopeChain.stream().map(ScopeSegment::name).collect(Collectors.joining("$"));
    }

    /** Join scope segment names after the first with $ */
    private static String normalizedRest(List<ScopeSegment> scopeChain) {
        return scopeChain.size() <= 1
                ? ""
                : scopeChain.subList(1, scopeChain.size()).stream()
                        .map(ScopeSegment::name)
                        .collect(Collectors.joining("$"));
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected boolean shouldSkipNode(TSNode node, String captureName, SourceContent sourceContent) {
        // Skip property setters to avoid duplicates with property getters
        if (CaptureNames.FUNCTION_DEFINITION.equals(captureName) && DECORATED_DEFINITION.equals(node.getType())) {
            // Check if this is a property setter by looking at decorators
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                TSNode child = node.getNamedChild(i);
                if (DECORATOR.equals(child.getType())) {
                    TSNode decoratorChild = child.getNamedChild(0);
                    if (decoratorChild != null && ATTRIBUTE.equals(decoratorChild.getType())) {
                        // Get the decorator text using the inherited textSlice method
                        String decoratorText = sourceContent
                                .substringFromBytes(decoratorChild.getStartByte(), decoratorChild.getEndByte())
                                .trim();
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
    protected boolean shouldReplaceOnDuplicate(CodeUnit existing, CodeUnit candidate) {
        // Python "last wins" semantics: duplicate definitions replace earlier ones
        // But only replace if same kind - a field shouldn't replace a class (e.g., "Base = FallbackBase")
        if (existing.kind() != candidate.kind()) {
            return false;
        }
        return candidate.isField() || candidate.isClass() || candidate.isFunction();
    }

    @Override
    protected boolean hasWrappingDecoratorNode() {
        // Python wraps decorated definitions in a decorated_definition node
        return true;
    }

    @Override
    protected TSNode extractContentFromDecoratedNode(
            TSNode decoratedNode,
            List<String> outDecoratorLines,
            SourceContent sourceContent,
            LanguageSyntaxProfile profile) {
        // Python's decorated_definition: decorators and actual definition are children
        // Process decorators and identify the actual content node
        TSNode nodeForContent = decoratedNode;
        for (int i = 0; i < decoratedNode.getNamedChildCount(); i++) {
            TSNode child = decoratedNode.getNamedChild(i);
            if (profile.decoratorNodeTypes().contains(child.getType())) {
                outDecoratorLines.add(sourceContent
                        .substringFromBytes(child.getStartByte(), child.getEndByte())
                        .stripLeading());
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
            SourceContent sourceContent,
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
                                && !PASS_STATEMENT.equals(
                                        bodyNode.getNamedChild(0).getType())));

        if (hasMeaningfulBody) {
            return signature + " " + bodyPlaceholder(); // Do not prepend indent here
        } else {
            return signature; // Do not prepend indent here
        }
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
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
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        // The definitionNode, rootNode, and src parameters are not used for Python package determination.
        // Returns module-qualified package (e.g., "mypkg.mod" not just "mypkg") for proper FQN construction.
        return resolveModuleInfo(file).moduleQualifiedPackage();
    }

    @Override
    protected String buildParentFqName(CodeUnit cu, String classChain, List<ScopeSegment> scopeChain) {
        // Design: shortName = class/function hierarchy, packageName = pkg.module
        // The scopeChain represents the nesting structure above this symbol
        // - Top-level: parent = packageName (module level)
        // - Nested class: Outer$Inner -> parent fqName = pkg.module.Outer
        // - Function-local: func$Local -> parent fqName = pkg.module.func
        // - Nested in func-local: func$Outer$Inner -> parent = pkg.module.func$Outer

        String packageName = cu.packageName();

        // TreeSitterAnalyzer only calls buildParentFqName for nested symbols
        assert !scopeChain.isEmpty() : "buildParentFqName should only be called with non-empty scopeChain";

        if (scopeChain.getFirst().isFunctionScope()) {
            // Function scope: func or func$Class
            var first = scopeChain.getFirst();
            if (scopeChain.size() == 1) {
                // Just function: parent fqName = pkg.module.func
                String parentFqn = packageName + "." + first.name();
                log.trace(
                        "Python parent lookup: scopeChain='{}', packageName='{}', returning '{}' (function parent)",
                        scopeChain,
                        packageName,
                        parentFqn);
                return parentFqn;
            } else {
                // Function + classes: func$Class -> parent = pkg.module.func$Class
                String parentFqn = packageName + "." + first.name() + "$" + normalizedRest(scopeChain);
                log.trace(
                        "Python parent lookup: scopeChain='{}', packageName='{}', first='{}', rest='{}', returning '{}'",
                        scopeChain,
                        packageName,
                        first.name(),
                        normalizedRest(scopeChain),
                        parentFqn);
                return parentFqn;
            }
        } else {
            // Class scope: Outer or Outer$Inner -> parent = pkg.module.Outer
            String parentFqn = packageName + "." + normalized(scopeChain);
            log.trace(
                    "Python parent lookup: scopeChain='{}', packageName='{}', normalized='{}', returning '{}' (class parent)",
                    scopeChain,
                    packageName,
                    normalized(scopeChain),
                    parentFqn);
            return parentFqn;
        }
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
     * Include functions as class-like parents to detect local classes inside functions.
     */
    @Override
    protected boolean isClassLike(TSNode node) {
        return super.isClassLike(node) || FUNCTION_DEFINITION.equals(node.getType());
    }

    @Override
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, TSNode classNode, String signature, SourceContent sourceContent) {
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
            var text = sourceContent
                    .substringFromBytes(s.getStartByte(), s.getEndByte())
                    .strip();
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

    /**
     * Resolves a module path to a ProjectFile, checking both module.py and package __init__.py.
     *
     * @param modulePath The dotted module path (e.g., "pkg.subpkg" or "module")
     * @return The resolved ProjectFile, or null if neither exists
     */
    private @Nullable ProjectFile resolveModuleFile(String modulePath) {
        var basePath = modulePath.replace('.', '/');

        // Try module.py first
        var moduleFilePath = basePath + ".py";
        var moduleFile = new ProjectFile(getProject().getRoot(), moduleFilePath);
        if (Files.exists(moduleFile.absPath())) {
            return moduleFile;
        }

        // Fall back to package __init__.py
        var initFilePath = basePath + "/__init__.py";
        var initFile = new ProjectFile(getProject().getRoot(), initFilePath);
        if (Files.exists(initFile.absPath())) {
            return initFile;
        }

        return null;
    }

    /**
     * Resolves import statements into a set of {@link CodeUnit}s, matching Python's native import semantics.
     * In Python, imports are executed in order and later imports override earlier ones with the same name.
     * This means a wildcard import that comes after an explicit import will shadow the explicit import
     * if both provide the same name.
     * <p>
     * Wildcard imports include public classes and functions (those without leading underscore).
     */
    // TODO: Performance optimization opportunity - This method re-parses each import line with
    // TreeSitter, even though the full AST was available during analyzeFileContent. A cleaner
    // approach would collect structured ImportInfo during the initial pass (while processing
    // import_statement/import_from_statement nodes) and store it in FileProperties. This would
    // eliminate redundant parsing. However, TreeSitter parsing is fast (~microseconds per line)
    // and Python files typically have few imports, so this is low priority unless profiling
    // shows it's a bottleneck.
    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        // Use a map to track resolved names - later imports overwrite earlier ones (Python semantics)
        Map<String, CodeUnit> resolvedByName = new LinkedHashMap<>();

        for (String importLine : importStatements) {
            if (importLine.isBlank()) continue;

            // Re-parse the import statement with TreeSitter (see TODO above)
            var parser = getTSParser();
            var tree = parser.parseString(null, importLine);
            var rootNode = tree.getRootNode();

            var query = getThreadLocalQuery();
            var cursor = new TSQueryCursor();
            cursor.exec(query, rootNode);

            var match = new TSQueryMatch();
            String currentModule = null;
            String wildcardModule = null;

            // Prepare SourceContent for this import line to use textSlice overloads
            SourceContent importSc = SourceContent.of(importLine);

            // Collect all captures from this import statement
            while (cursor.nextMatch(match)) {
                for (var cap : match.getCaptures()) {
                    var capName = query.getCaptureNameForId(cap.getIndex());
                    var node = cap.getNode();
                    if (node == null || node.isNull()) continue;

                    var text = importSc.substringFromBytes(node.getStartByte(), node.getEndByte());

                    switch (capName) {
                        case IMPORT_MODULE -> currentModule = text;
                        case IMPORT_RELATIVE -> {
                            // Resolve relative import to absolute package path
                            var absolutePath = resolveRelativeImport(file, text);
                            currentModule = absolutePath.orElse(null);
                        }
                        case IMPORT_MODULE_WILDCARD -> wildcardModule = text;
                        case IMPORT_RELATIVE_WILDCARD -> {
                            // Resolve relative wildcard import to absolute package path
                            var absolutePath = resolveRelativeImport(file, text);
                            wildcardModule = absolutePath.orElse(null);
                        }
                        case IMPORT_WILDCARD -> {
                            // Wildcard import - expand and add all public symbols (may overwrite previous imports)
                            if (wildcardModule != null && !wildcardModule.isEmpty()) {
                                var moduleFile = resolveModuleFile(wildcardModule);
                                if (moduleFile != null) {
                                    try {
                                        var decls = getDeclarations(moduleFile);
                                        for (CodeUnit child : decls) {
                                            // Import public classes and functions (no underscore prefix)
                                            // TODO: Consider including public top-level constants (fields)
                                            if ((child.isClass() || child.isFunction())
                                                    && !child.identifier().startsWith("_")) {
                                                resolvedByName.put(child.identifier(), child);
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.warn(
                                                "Could not expand wildcard import from {}: {}",
                                                wildcardModule,
                                                e.getMessage());
                                    }
                                } else {
                                    log.warn("Could not find module file for wildcard import: {}", wildcardModule);
                                }
                            }
                        }
                        case IMPORT_NAME -> {
                            // For "from X import Y" style, we need the module
                            if (currentModule != null) {
                                // In Python, modules (files) don't add a level to class FQNs
                                // "from package.module import Class" means:
                                //   - Look for Class in file package/module.py or package/__init__.py
                                //   - The FQN will be package.Class, not package.module.Class

                                // Try to find the symbol in the module file
                                var moduleFile = resolveModuleFile(currentModule);
                                if (moduleFile != null) {
                                    try {
                                        var decls = getDeclarations(moduleFile);
                                        decls.stream()
                                                .filter(cu -> cu.identifier().equals(text)
                                                        && (cu.isClass() || cu.isFunction()))
                                                .findFirst()
                                                .ifPresent(cu -> resolvedByName.put(cu.identifier(), cu));
                                    } catch (Exception e) {
                                        log.warn(
                                                "Could not resolve import '{}' from module {}: {}",
                                                text,
                                                currentModule,
                                                e.getMessage());
                                    }
                                } else {
                                    log.debug("Could not find module file for import: {}", currentModule);
                                }
                            } else if (currentModule == null && wildcardModule == null) {
                                // For "import X" style (no module context)
                                var definitions = getDefinitions(text);
                                definitions.stream()
                                        .filter(cu -> cu.isClass() || cu.isFunction())
                                        .findFirst()
                                        .ifPresent(cu -> resolvedByName.put(cu.identifier(), cu));
                            }
                        }
                            // Note: IMPORT_ALIAS captures the alias name, but we don't need it
                            // for resolution - we only care about the original name
                    }
                }
            }
        }

        return Collections.unmodifiableSet(new LinkedHashSet<>(resolvedByName.values()));
    }

    @Override
    protected void createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            Map<String, CodeUnit> localCuByFqName,
            List<CodeUnit> localTopLevelCUs,
            Map<CodeUnit, List<String>> localSignatures,
            Map<CodeUnit, List<Range>> localSourceRanges,
            Map<CodeUnit, List<CodeUnit>> localChildren) {

        if (modulePackageName.isBlank()) {
            return;
        }

        int idx = modulePackageName.lastIndexOf('.');
        String parentPkg = idx >= 0 ? modulePackageName.substring(0, idx) : "";
        String simpleName = idx >= 0 ? modulePackageName.substring(idx + 1) : modulePackageName;

        CodeUnit moduleCu = CodeUnit.module(file, parentPkg, simpleName);

        List<CodeUnit> children = localTopLevelCUs.stream()
                .filter(cu -> modulePackageName.equals(cu.packageName()))
                .filter(cu -> cu.isClass() || cu.isFunction() || cu.isField())
                .collect(Collectors.toList());

        localChildren.put(moduleCu, children);
        localCuByFqName.put(moduleCu.fqName(), moduleCu);

        localSignatures.computeIfAbsent(moduleCu, k -> new ArrayList<>()).add("# module " + modulePackageName);
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
            var inPackageSet = getDefinitions(fqnInPackage);
            var inPackage = inPackageSet.stream().filter(CodeUnit::isClass).findFirst();
            if (inPackage.isPresent()) {
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
