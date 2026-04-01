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
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
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
    static class AngularHtmlParser extends TreeSitterAnalyzer {
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
            return switch (type) {
                case DEFINITIONS -> Optional.of("treesitter/angular/definitions.scm");
                case SUMMARY -> Optional.of("treesitter/angular/summary.scm");
                default -> Optional.empty();
            };
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
        protected Optional<String> extractSimpleName(TSNode node, SourceContent sourceContent) {
            if ("element".equals(node.getType())) {
                // In tree-sitter-angular, tag_name is inside start_tag or self_closing_tag
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode child = node.getChild(i);
                    String type = child.getType();
                    if ("start_tag".equals(type) || "self_closing_tag".equals(type)) {
                        for (int j = 0; j < child.getChildCount(); j++) {
                            TSNode grandChild = child.getChild(j);
                            if ("tag_name".equals(grandChild.getType())) {
                                return Optional.of(
                                        sourceContent.substringFrom(grandChild).strip());
                            }
                        }
                    }
                }
            }
            return super.extractSimpleName(node, sourceContent);
        }

        @Override
        public Optional<IAnalyzer> subAnalyzer(Language language) {
            return Optional.of(this);
        }

        @Override
        public Optional<String> extractCallReceiver(String reference) {
            return Optional.empty();
        }

        @Override
        public @Nullable String summarizeSymbols(ProjectFile templateFile) {
            return withTreeOf(
                    templateFile,
                    tree -> withSource(
                            templateFile,
                            sc -> withCachedQuery(
                                    QueryType.SUMMARY,
                                    query -> {
                                        TSNode root = tree.getRootNode();
                                        if (root == null) return null;

                                        Set<String> components = new TreeSet<>();
                                        Set<String> directives = new TreeSet<>();
                                        Set<String> controlFlow = new TreeSet<>();
                                        Set<String> pipes = new TreeSet<>();
                                        Set<String> bindings = new TreeSet<>();
                                        Set<String> events = new TreeSet<>();

                                        try (TSQueryCursor cursor = new TSQueryCursor()) {
                                            cursor.exec(query, root, sc.text());
                                            TSQueryMatch match = new TSQueryMatch();
                                            while (cursor.nextMatch(match)) {
                                                for (TSQueryCapture capture : match.getCaptures()) {
                                                    String name = query.getCaptureNameForId(capture.getIndex());
                                                    TSNode node = capture.getNode();
                                                    String text = sc.substringFrom(node).strip();
                                                    if (node == null || text.isEmpty()) continue;

                                                    switch (name) {
                                                        case "tag_name" -> {
                                                            if (text.contains("-")) components.add(text);
                                                        }
                                                        case "directive_name" -> {
                                                            directives.add("*" + text);
                                                        }
                                                        case "control_flow" -> {
                                                            // For statement nodes, the first child is usually the
                                                            // keyword
                                                            String kw = text.split("[\\s(]")[0];
                                                            if (!kw.isEmpty()) {
                                                                controlFlow.add(kw.startsWith("@") ? kw : "@" + kw);
                                                            }
                                                        }
                                                        case "pipe_node" -> {
                                                            // pipe_call contains the whole expression, identifier is
                                                            // the pipe name
                                                            TSNode idNode = node.getChildByFieldName("name");
                                                            if (idNode == null) {
                                                                idNode = node.getChildren().stream()
                                                                        .filter(c -> "identifier".equals(c.getType()))
                                                                        .findFirst()
                                                                        .orElse(null);
                                                            }
                                                            if (idNode != null) {
                                                                pipes.add(sc.substringFrom(idNode).strip());
                                                            }
                                                        }
                                                        case "prop_binding" -> {
                                                            // property_binding contains [name]="val" or similar
                                                            node.getChildren().stream()
                                                                    .filter(c -> "binding_name".equals(c.getType())
                                                                            || "class_binding".equals(c.getType()))
                                                                    .map(sc::substringFrom)
                                                                    .map(String::strip)
                                                                    .map(s -> s.replace("[", "").replace("]", ""))
                                                                    .filter(s -> !s.isEmpty())
                                                                    .forEach(bindings::add);
                                                        }
                                                        case "evt_binding" -> {
                                                            // event_binding contains (name)="handler()"
                                                            node.getChildren().stream()
                                                                    .filter(c -> "binding_name".equals(c.getType()))
                                                                    .map(sc::substringFrom)
                                                                    .map(String::strip)
                                                                    .map(s -> s.replace("(", "").replace(")", ""))
                                                                    .filter(s -> !s.isEmpty())
                                                                    .forEach(events::add);
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (components.isEmpty()
                                                && directives.isEmpty()
                                                && controlFlow.isEmpty()
                                                && pipes.isEmpty()
                                                && bindings.isEmpty()
                                                && events.isEmpty()) {
                                            return null;
                                        }

                                        StringBuilder summary = new StringBuilder();
                                        summary.append("<!--\n");
                                        summary.append("  Angular Template Summary\n");
                                        appendCategory(summary, "Components", components);
                                        appendCategory(summary, "Directives", directives);
                                        appendCategory(summary, "Control Flow", controlFlow);
                                        appendCategory(summary, "Pipes", pipes);
                                        appendCategory(summary, "Bindings", bindings);
                                        appendCategory(summary, "Events", events);
                                        summary.append("-->");
                                        return summary.toString();
                                    },
                                    null),
                            null),
                    null);
        }

        private void appendCategory(StringBuilder sb, String title, Set<String> items) {
            if (!items.isEmpty()) {
                sb.append("\n  ").append(title).append(":\n");
                for (String item : items) {
                    sb.append("    - ").append(item).append("\n");
                }
            }
        }
    }
}
