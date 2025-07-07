package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public final class PythonAnalyzer extends TreeSitterAnalyzer {

    private static final TSLanguage PY_LANGUAGE = new TreeSitterPython();
    private static final LanguageSyntaxProfile PY_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_definition"),
            Set.of("function_definition"),
            Set.of("assignment", "typed_parameter"),
            Set.of("decorator"),
            "name",        // identifierFieldName
            "body",        // bodyFieldName
            "parameters",  // parametersFieldName
            "return_type", // returnTypeFieldName
            "",            // typeParametersFieldName (Python doesn't have explicit type parameters)
            java.util.Map.of( // captureConfiguration
                "class.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE
            ),
            "async", // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
    );

    public PythonAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
    }

    public PythonAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return PY_LANGUAGE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName, // Changed from namespaceName
                                      String classChain) {
        // The packageName parameter is now supplied by determinePackageName.
        // The classChain parameter is used for Joern-style short name generation.

        // Extract module name from filename using the inherited getFileName() method
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3); // e.g., "A"
        }

        try {
            return switch (captureName) {
                case "class.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packageName, finalShortName);
                }
                case "function.definition" -> {
                    String finalShortName = classChain.isEmpty() ? (moduleName + "." + simpleName) : (classChain + "." + simpleName);
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "field.definition" -> { // For class attributes or top-level variables
                    if (file.getFileName().equals("vars.py")) {
                        log.trace("[vars.py DEBUG PythonAnalyzer.createCodeUnit] file: {}, captureName: {}, simpleName: {}, packageName: {}, classChain: {}, moduleName: {}",
                                 file.getFileName(), captureName, simpleName, packageName, classChain, moduleName);
                    }
                    String finalShortName;
                    if (classChain.isEmpty()) {
                        // For top-level variables, use "moduleName.variableName" to satisfy CodeUnit.field's expectation of a "."
                        // This also makes it consistent with how top-level functions are named (moduleName.funcName)
                        finalShortName = moduleName + "." + simpleName;
                    } else {
                        finalShortName = classChain + "." + simpleName;
                    }
                    yield CodeUnit.field(file, packageName, finalShortName);
                }
                default -> {
                    // Log or handle unexpected captures if necessary
                    log.debug("Ignoring capture: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                    yield null; // Returning null ignores the capture
                }
            };
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create CodeUnit for capture '{}', name '{}', file '{}': {}",
                     captureName, simpleName, file, e.getMessage());
            return null; // Return null on error to avoid breaking the analysis stream
        }
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        String pyReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? " -> " + returnTypeText : "";
        // The 'indent' parameter is now "" when called from buildSignatureString,
        // so it's effectively ignored here for constructing the stored signature.
        String signature = String.format("%s%sdef %s%s%s:", exportPrefix, asyncPrefix, functionName, paramsText, pyReturnTypeSuffix);

        TSNode bodyNode = funcNode.getChildByFieldName("body");
        boolean hasMeaningfulBody = bodyNode != null && !bodyNode.isNull() &&
                                    (bodyNode.getNamedChildCount() > 1 ||
                                     (bodyNode.getNamedChildCount() == 1 && !"pass_statement".equals(bodyNode.getNamedChild(0).getType())));

        if (hasMeaningfulBody) {
            return signature + " " + bodyPlaceholder(); // Do not prepend indent here
        } else {
            return signature; // Do not prepend indent here
        }
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return signatureText; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return ""; // Python uses indentation, no explicit closer for classes/functions
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        // The definitionNode, rootNode, and src parameters are not used for Python package determination.
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
        Path rootForRelativize = effectivePackageRoot.equals(projectRoot) ? projectRoot : effectivePackageRoot.getParent();
        if (rootForRelativize == null) { // Should not happen if projectRoot is valid
            rootForRelativize = projectRoot;
        }

        // If parentDir is not under rootForRelativize (e.g. parentDir is projectRoot, effectivePackageRoot is deeper due to missing __init__.py)
        // or if parentDir is the same as rootForRelativize, then there's no relative package path.
        if (!parentDir.startsWith(rootForRelativize) || parentDir.equals(rootForRelativize)) {
             return "";
        }

        var relPath = rootForRelativize.relativize(parentDir);

        // Convert path separators to dots for package name
        return relPath.toString().replace('/', '.').replace('\\', '.');
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
}
