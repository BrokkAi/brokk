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

        // For __init__.py, use the package name as the module name (matching Python import semantics)
        // e.g., from mypackage import ClassName works when ClassName is in mypackage/__init__.py
        String effectivePackageName = packageName;
        if (moduleName.equals("__init__") && !packageName.isEmpty()) {
            int lastDot = packageName.lastIndexOf('.');
            if (lastDot == -1) {
                // Single-component package: "mypackage" -> module="mypackage", pkg=""
                moduleName = packageName;
                effectivePackageName = "";
            } else {
                // Multi-component: "mypackage.subpkg" -> module="subpkg", pkg="mypackage"
                moduleName = packageName.substring(lastDot + 1);
                effectivePackageName = packageName.substring(0, lastDot);
            }
        }

        // Parse classChain once - centralizes scope detection logic
        var parser = new ClassChainParser(classChain);

        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION -> {
                log.trace(
                        "Creating class: simpleName='{}', classChain='{}', packageName='{}', moduleName='{}'",
                        simpleName,
                        classChain,
                        packageName,
                        moduleName);

                // Design: Use $ for class boundaries, . for scope boundaries
                // shortName = "module$Class" or "module$Outer$Inner" or "module.func$LocalClass"
                String finalShortName;

                if (parser.isEmpty()) {
                    // Top-level class: "module$ClassName"
                    finalShortName = moduleName + "$" + simpleName;
                } else if (parser.isFunctionScope) {
                    // Function-local class: "module.func$LocalClass" or "module.func$Outer$Inner"
                    if (parser.rest.isEmpty()) {
                        // Just "func" - direct child of function
                        finalShortName = moduleName + "." + parser.firstSegment + "$" + simpleName;
                    } else {
                        // "func.Outer" or "func$Outer" -> "module.func$Outer$simpleName"
                        String classPart = parser.normalizedRest() + "$";
                        finalShortName = moduleName + "." + parser.firstSegment + "$" + classPart + simpleName;
                    }
                } else {
                    // Class-nested: "module$Outer$Inner"
                    finalShortName = moduleName + "$" + parser.normalizedChain() + "$" + simpleName;
                }

                yield CodeUnit.cls(file, effectivePackageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> {
                // Functions use . throughout: "module.func" or "module$Class.method"
                String finalShortName;

                if (parser.isEmpty()) {
                    // Top-level function: "module.func"
                    finalShortName = moduleName + "." + simpleName;
                } else if (parser.isFunctionScope) {
                    // Nested function or method in function-local class
                    if (parser.rest.isEmpty()) {
                        // Nested function inside function: "module.outer.inner"
                        finalShortName = moduleName + "." + classChain + "." + simpleName;
                    } else {
                        // Method in function-local class: "module.func$Class.method"
                        finalShortName = moduleName + "." + parser.normalizedChain() + "." + simpleName;
                    }
                } else {
                    // Method in regular class: "module$Class.method" or "module$Outer$Inner.method"
                    finalShortName = moduleName + "$" + parser.normalizedChain() + "." + simpleName;
                }

                yield CodeUnit.fn(file, effectivePackageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> {
                // Fields use . for member access: "module.var" or "module$Class.field"
                String finalShortName;

                if (parser.isEmpty()) {
                    // Top-level variable: "module.varName"
                    finalShortName = moduleName + "." + simpleName;
                } else if (parser.isFunctionScope) {
                    // Field in function-local class: "module.func$Class.field"
                    if (parser.rest.isEmpty()) {
                        // Variable in function scope (unusual): "module.func.var"
                        finalShortName = moduleName + "." + classChain + "." + simpleName;
                    } else {
                        finalShortName = moduleName + "." + parser.normalizedChain() + "." + simpleName;
                    }
                } else {
                    // Field in regular class: "module$Class.field"
                    finalShortName = moduleName + "$" + parser.normalizedChain() + "." + simpleName;
                }

                yield CodeUnit.field(file, effectivePackageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null;
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
        // Design: $ for class boundaries, . for function/module scope
        // The classChain represents the nesting structure above this symbol
        // - Top-level class: module$Class -> parent = packageName (module level)
        // - Nested class: module$Outer$Inner -> parent fqName = pkg.module$Outer
        // - Function-local: module.func$Local -> parent fqName = pkg.module.func
        // - Nested in func-local: module.func$Outer$Inner -> parent = pkg.module.func$Outer

        String packageName = cu.packageName();

        if (classChain.isBlank()) {
            // Top-level: no parent to find within the file structure
            return packageName;
        }

        // Get module name from file
        String moduleName = cu.source().getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3);
        }

        // For __init__.py, derive the module name from the CodeUnit's shortName
        // since the effective module is the package name, not "__init__"
        if (moduleName.equals("__init__")) {
            // Extract module name from shortName (e.g., "mypackage$Class" -> "mypackage")
            var shortName = cu.shortName();
            int firstBoundary = shortName.indexOf('$');
            if (firstBoundary == -1) {
                firstBoundary = shortName.indexOf('.');
            }
            if (firstBoundary > 0) {
                moduleName = shortName.substring(0, firstBoundary);
            }
        }

        // Use ClassChainParser - same logic as createCodeUnit for consistent FQN construction
        var parser = new ClassChainParser(classChain);
        String base = packageName.isEmpty() ? moduleName : packageName + "." + moduleName;

        if (parser.isFunctionScope) {
            // Function scope: module.func or module.func$Class
            if (parser.rest.isEmpty()) {
                // Just function: parent fqName = pkg.module.func
                String parentFqn = base + "." + parser.firstSegment;
                log.trace(
                        "Python parent lookup: classChain='{}', base='{}', returning '{}' (function parent)",
                        classChain,
                        base,
                        parentFqn);
                return parentFqn;
            } else {
                // Function + classes: convert class parts to $
                // classChain = "func.Class" -> parent = pkg.module.func$Class
                String parentFqn = base + "." + parser.firstSegment + "$" + parser.normalizedRest();
                log.trace(
                        "Python parent lookup: classChain='{}', base='{}', firstSegment='{}', rest='{}', returning '{}'",
                        classChain,
                        base,
                        parser.firstSegment,
                        parser.rest,
                        parentFqn);
                return parentFqn;
            }
        } else {
            // Class scope: module$Class or module$Outer$Inner
            String parentFqn = base + "$" + parser.normalizedChain();
            log.trace(
                    "Python parent lookup: classChain='{}', base='{}', normalizedChain='{}', returning '{}' (class parent)",
                    classChain,
                    base,
                    parser.normalizedChain(),
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
     * Checks if identifier follows Python function naming (lowercase start) vs class naming (PascalCase).
     *
     * <p>This is a fallback heuristic used only when AST-based type markers are not available.
     * The primary mechanism is the ":F" and ":C" markers added by {@link #determineClassChainSegmentName},
     * which use actual TreeSitter node types to distinguish functions from classes.
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
     * Parses a classChain string to extract scope information.
     * Centralizes the logic for determining function vs class scope and extracting segments.
     *
     * <p>Recognizes ":F" (function) and ":C" (class) markers added by
     * {@link #determineClassChainSegmentName} to identify symbol types by AST node type
     * rather than relying on the naming heuristic.
     */
    private static class ClassChainParser {
        static final String FUNCTION_MARKER = ":F";
        static final String CLASS_MARKER = ":C";

        final String classChain;
        final String firstSegment; // Without marker
        final String rest; // Without markers
        final boolean isFunctionScope;

        ClassChainParser(String classChain) {
            this.classChain = classChain;

            // Find first boundary (. or $)
            int firstDot = classChain.indexOf('.');
            int firstDollar = classChain.indexOf('$');
            int boundary = minPositive(firstDot, firstDollar);

            String rawFirstSegment;
            String rawRest;
            if (boundary == -1) {
                rawFirstSegment = classChain;
                rawRest = "";
            } else {
                rawFirstSegment = classChain.substring(0, boundary);
                rawRest = classChain.substring(boundary + 1);
            }

            // Check for AST-based markers
            if (rawFirstSegment.endsWith(FUNCTION_MARKER)) {
                this.firstSegment = rawFirstSegment.substring(0, rawFirstSegment.length() - FUNCTION_MARKER.length());
                this.isFunctionScope = true;
            } else if (rawFirstSegment.endsWith(CLASS_MARKER)) {
                this.firstSegment = rawFirstSegment.substring(0, rawFirstSegment.length() - CLASS_MARKER.length());
                this.isFunctionScope = false;
            } else {
                this.firstSegment = rawFirstSegment;
                // Fall back to naming heuristic for unmarked segments (backward compatibility)
                this.isFunctionScope = !firstSegment.isEmpty() && isLowercaseIdentifier(firstSegment);
            }

            // Strip markers from rest as well
            this.rest = stripMarkers(rawRest);
        }

        boolean isEmpty() {
            return classChain.isEmpty() || classChain.isBlank();
        }

        String normalizedRest() {
            return rest.replace(".", "$");
        }

        String normalizedChain() {
            return stripMarkers(classChain).replace(".", "$");
        }

        private static String stripMarkers(String s) {
            return s.replace(FUNCTION_MARKER, "").replace(CLASS_MARKER, "");
        }

        private static int minPositive(int a, int b) {
            if (a == -1) return b;
            if (b == -1) return a;
            return Math.min(a, b);
        }
    }

    /**
     * Include functions as class-like parents to detect local classes inside functions.
     */
    @Override
    protected boolean isClassLike(TSNode node) {
        return super.isClassLike(node) || "function_definition".equals(node.getType());
    }

    /**
     * Mark functions with ":F" and classes with ":C" suffix in classChain.
     * This allows ClassChainParser to use actual AST type info instead of the naming heuristic.
     */
    @Override
    protected String determineClassChainSegmentName(String nodeType, String shortName) {
        return switch (nodeType) {
            case "function_definition" -> shortName + ClassChainParser.FUNCTION_MARKER;
            case "class_definition" -> shortName + ClassChainParser.CLASS_MARKER;
            default -> shortName;
        };
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
    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        // Use a map to track resolved names - later imports overwrite earlier ones (Python semantics)
        Map<String, CodeUnit> resolvedByName = new LinkedHashMap<>();

        for (String importLine : importStatements) {
            if (importLine.isBlank()) continue;

            // Parse the import statement with TreeSitter
            var parser = getTSParser();
            var tree = parser.parseString(null, importLine);
            var rootNode = tree.getRootNode();

            var query = getThreadLocalQuery();
            var cursor = new TSQueryCursor();
            cursor.exec(query, rootNode);

            var match = new TSQueryMatch();
            String currentModule = null;
            String wildcardModule = null;

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
