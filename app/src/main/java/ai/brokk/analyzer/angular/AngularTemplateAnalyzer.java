package ai.brokk.analyzer.angular;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.IAnalyzer.ProgressListener;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterAngular;

/**
 * Template analyzer for Angular applications.
 * Detects Angular components and analyzes their HTML templates.
 */
@NullMarked
public class AngularTemplateAnalyzer implements ITemplateAnalyzer {
    private static final Logger log = LogManager.getLogger(AngularTemplateAnalyzer.class);

    private final Map<ProjectFile, TemplateAnalysisResult> results = Collections.synchronizedMap(new HashMap<>());
    private final Map<CodeUnit, String> hostClassToInlineTemplate = Collections.synchronizedMap(new HashMap<>());
    private final Map<CodeUnit, String> hostClassToTemplateUrl = Collections.synchronizedMap(new HashMap<>());

    @Override
    public boolean isApplicable(IProject project) {
        Path root = project.getRoot();

        // Check for configuration files up to 5 levels deep
        try (Stream<Path> stream = Files.walk(root, 5)) {
            boolean hasConfig = stream.filter(Files::isRegularFile).anyMatch(p -> {
                String fileName = p.getFileName().toString();
                if (fileName.equals("angular.json")) {
                    return true;
                }
                if (fileName.equals("package.json")) {
                    try {
                        String content = Files.readString(p);
                        return content.contains("@angular/core");
                    } catch (IOException e) {
                        return false;
                    }
                }
                return false;
            });
            if (hasConfig) return true;
        } catch (IOException e) {
            log.debug("Error scanning for Angular config in {}: {}", root, e.getMessage());
        }

        // Check for component templates
        return project.getAllFiles().stream().anyMatch(pf -> pf.getFileName().endsWith(".component.html"));
    }

    @Override
    public String name() {
        return "Angular";
    }

    @Override
    public String internalName() {
        return "ANGULAR";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("html");
    }

    @Override
    public void onHostSignal(String signal, Map<String, Object> payload, TreeSitterAnalyzer.AnalyzerState globalState) {
        if (!"COMPONENT_FOUND".equals(signal)) {
            return;
        }

        Object hostClassObj = payload.get("hostClass");
        if (!(hostClassObj instanceof CodeUnit hostClass)) {
            return;
        }

        Object template = payload.get("template");
        if (template instanceof String inline) {
            hostClassToInlineTemplate.put(hostClass, inline);
        }

        Object templateUrl = payload.get("templateUrl");
        if (templateUrl instanceof String url) {
            hostClassToTemplateUrl.put(hostClass, url);
        }
    }

    @Override
    public Set<ProjectFile> getTemplateFiles(CodeUnit hostClass, IContextManager contextManager) {
        String templateUrl = hostClassToTemplateUrl.get(hostClass);
        if (templateUrl == null) {
            return Set.of();
        }

        try {
            // Angular template URLs are relative to the component file
            Path hostDir = hostClass.source().getParent();
            Path resolvedPath = hostDir.resolve(templateUrl).normalize();

            // ProjectFile requires a path relative to the project root
            ProjectFile pf = new ProjectFile(contextManager.getProject().getRoot(), resolvedPath);
            if (pf.exists()) {
                return Set.of(pf);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve template file {} for {}: {}", templateUrl, hostClass, e.getMessage());
        }

        return Set.of();
    }

    @Override
    public Optional<String> summarizeTemplate(ProjectFile templateFile, IContextManager contextManager) {
        try {
            AngularHtmlParser parser = new AngularHtmlParser(contextManager.getProject());
            return Optional.ofNullable(parser.summarizeSymbols(templateFile));
        } catch (Exception e) {
            log.warn("Failed to summarize Angular template {}: {}", templateFile, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Set<String> getTemplateSources(CodeUnit hostClass) {
        String inline = hostClassToInlineTemplate.get(hostClass);
        if (inline != null) {
            return Set.of(inline);
        }

        String templateUrl = hostClassToTemplateUrl.get(hostClass);
        if (templateUrl != null) {
            try {
                Path sourcePath = hostClass.source().absPath();
                Path parentPath = sourcePath.getParent();
                if (parentPath != null) {
                    Path templatePath = parentPath.resolve(templateUrl).normalize();
                    if (Files.exists(templatePath)) {
                        return Set.of(Files.readString(templatePath));
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read template file {} for {}: {}", templateUrl, hostClass, e.getMessage());
            }
        }

        return Set.of();
    }

    @Override
    public TemplateAnalysisResult analyzeTemplate(
            IAnalyzer hostAnalyzer, ProjectFile templateFile, CodeUnit hostClass) {
        try {
            // Ensure the parser sees the template file by using a focused project view
            IProject focusedProject = hostAnalyzer.getProject();
            AngularHtmlParser parser = new AngularHtmlParser(focusedProject);
            List<CodeUnit> topLevel = parser.getTopLevelDeclarations(templateFile);

            TemplateAnalysisResult result =
                    new TemplateAnalysisResult(internalName(), templateFile, Set.copyOf(topLevel), List.of());

            results.put(templateFile, result);
            return result;
        } catch (Exception e) {
            log.error("Error analyzing Angular template {}: {}", templateFile, e.getMessage());
            return new TemplateAnalysisResult(internalName(), templateFile, Set.of(), List.of(e.getMessage()));
        }
    }

    @Override
    public List<TemplateAnalysisResult> snapshotState() {
        return new ArrayList<>(results.values());
    }

    @Override
    public void restoreState(List<TemplateAnalysisResult> state) {
        results.clear();
        for (var res : state) {
            results.put(res.templateFile(), res);
        }
    }

    /**
     * Language definition for Angular HTML templates.
     */
    private static final Language ANGULAR_HTML = new Language() {
        @Override
        public Set<String> getExtensions() {
            return Set.of("html");
        }

        @Override
        public String name() {
            return "AngularHTML";
        }

        @Override
        public String internalName() {
            return "ANGULAR_HTML";
        }

        @Override
        public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return new AngularHtmlParser(project);
        }

        @Override
        public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
            return createAnalyzer(project, listener);
        }
    };

    /**
     * Internal TreeSitter parser for Angular HTML templates.
     */
    private static class AngularHtmlParser extends TreeSitterAnalyzer {
        private static final LanguageSyntaxProfile PROFILE = new LanguageSyntaxProfile(
                Set.of("element"), // classLikeNodeTypes
                Set.of(), // functionLikeNodeTypes
                Set.of(), // fieldLikeNodeTypes
                Set.of(), // constructorNodeTypes
                Set.of(), // decoratorNodeTypes
                "", // importNodeType
                "tag_name", // identifierFieldName
                "", // bodyFieldName
                "", // parametersFieldName
                "", // returnTypeFieldName
                "", // typeParametersFieldName
                Map.of("element", SkeletonType.CLASS_LIKE), // captureConfiguration
                "", // asyncKeywordNodeType
                Set.of() // modifierNodeTypes
                );

        AngularHtmlParser(IProject project) {
            super(project, ANGULAR_HTML, ProgressListener.NOOP);
        }

        @Override
        protected TSLanguage createTSLanguage() {
            return new TreeSitterAngular();
        }

        @Override
        protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
            return PROFILE;
        }

        @Override
        protected Optional<String> getQueryResource(QueryType type) {
            if (type == QueryType.DEFINITIONS) {
                // Coarse-grained capture of top-level elements
                return Optional.of("treesitter/angular/definitions.scm");
            }
            return Optional.empty();
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
            // HTML elements are treated as modules/coarse units in the template context
            return new CodeUnit(file, CodeUnitType.MODULE, "", simpleName);
        }

        @Override
        protected String determinePackageName(
                ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
            return "";
        }

        @Override
        protected String renderClassHeader(
                TSNode classNode,
                SourceContent sourceContent,
                String exportPrefix,
                String signatureText,
                String baseIndent) {
            return baseIndent + "<" + signatureText + ">";
        }

        @Override
        protected String renderFunctionDeclaration(
                TSNode funcNode,
                SourceContent sourceContent,
                String exportAndModifierPrefix,
                String asyncPrefix,
                String functionName,
                String typeParamsText,
                String paramsText,
                String returnTypeText,
                String indent) {
            return "";
        }

        @Override
        protected String getLanguageSpecificCloser(CodeUnit cu) {
            return "</" + cu.shortName() + ">";
        }

        @Override
        protected String bodyPlaceholder() {
            return "...";
        }

        @Override
        protected IAnalyzer newSnapshot(
                AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
            return new AngularHtmlParser(getProject(), state, listener, previousCache);
        }

        AngularHtmlParser(
                IProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
            super(project, ANGULAR_HTML, state, listener, cache);
        }

        @Override
        public Optional<IAnalyzer> subAnalyzer(Language language) {
            return Optional.of(this);
        }

        @Override
        public Optional<String> extractCallReceiver(String reference) {
            return Optional.empty();
        }
    }
}
