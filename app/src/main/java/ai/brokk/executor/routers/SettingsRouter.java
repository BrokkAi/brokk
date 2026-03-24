package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.IssueProvider;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.agents.BuildAgent.ModuleBuildEntry;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.issues.IssuesProviderConfig;
import ai.brokk.project.IProject;
import ai.brokk.project.IProject.CodeAgentTestScope;
import ai.brokk.project.MainProject.DataRetentionPolicy;
import ai.brokk.util.ShellConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/settings endpoints exposing project settings and build configuration.
 */
@NullMarked
public final class SettingsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(SettingsRouter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ContextManager contextManager;

    public SettingsRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (normalizedPath.equals("/v1/settings")) {
            if ("GET".equals(method)) {
                handleGetSettings(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/build")) {
            if ("POST".equals(method)) {
                handlePostBuild(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/project")) {
            if ("POST".equals(method)) {
                handlePostProject(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/shell")) {
            if ("POST".equals(method)) {
                handlePostShell(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/issues")) {
            if ("POST".equals(method)) {
                handlePostIssues(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/data-retention")) {
            if ("POST".equals(method)) {
                handlePostDataRetention(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        if (normalizedPath.equals("/v1/settings/languages")) {
            if ("POST".equals(method)) {
                handlePostLanguages(exchange);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
    }

    private void handleGetSettings(HttpExchange exchange) throws IOException {
        try {
            var project = contextManager.getProject();
            var response = new HashMap<String, Object>();

            // Build details
            var buildDetails = project.awaitBuildDetails();
            response.put("buildDetails", buildBuildDetailsMap(buildDetails));

            // Project settings
            response.put("projectSettings", buildProjectSettingsMap(project));

            // Shell config
            var shellConfig = project.getShellConfig();
            response.put("shellConfig", buildShellConfigMap(shellConfig));

            // Issue provider
            var issueProvider = project.getIssuesProvider();
            response.put("issueProvider", buildIssueProviderMap(issueProvider));

            // Data retention policy
            response.put("dataRetentionPolicy", project.getDataRetentionPolicy().name());

            // Analyzer languages
            response.put("analyzerLanguages", buildAnalyzerLanguagesMap(project));

            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/settings", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve settings", e));
        }
    }

    private Map<String, Object> buildBuildDetailsMap(BuildDetails details) {
        var map = new LinkedHashMap<String, Object>();
        map.put("buildLintCommand", details.buildLintCommand());
        map.put("buildLintEnabled", details.buildLintEnabled());
        map.put("testAllCommand", details.testAllCommand());
        map.put("testAllEnabled", details.testAllEnabled());
        map.put("testSomeCommand", details.testSomeCommand());
        map.put("testSomeEnabled", details.testSomeEnabled());
        map.put("exclusionPatterns", new ArrayList<>(details.exclusionPatterns()));
        map.put("environmentVariables", new LinkedHashMap<>(details.environmentVariables()));
        map.put("maxBuildAttempts", details.maxBuildAttempts());
        map.put("afterTaskListCommand", details.afterTaskListCommand());

        var modulesList = new ArrayList<Map<String, Object>>();
        for (var module : details.modules()) {
            var moduleMap = new LinkedHashMap<String, Object>();
            moduleMap.put("alias", module.alias());
            moduleMap.put("relativePath", module.relativePath());
            moduleMap.put("buildLintCommand", module.buildLintCommand());
            moduleMap.put("testAllCommand", module.testAllCommand());
            moduleMap.put("testSomeCommand", module.testSomeCommand());
            moduleMap.put("language", module.language());
            modulesList.add(moduleMap);
        }
        map.put("modules", modulesList);

        return map;
    }

    private Map<String, Object> buildProjectSettingsMap(IProject project) {
        var map = new LinkedHashMap<String, Object>();
        map.put("commitMessageFormat", project.getCommitMessageFormat());
        map.put("codeAgentTestScope", project.getCodeAgentTestScope().name());
        map.put("runCommandTimeoutSeconds", project.getRunCommandTimeoutSeconds());
        map.put("testCommandTimeoutSeconds", project.getTestCommandTimeoutSeconds());
        map.put("autoUpdateLocalDependencies", project.getAutoUpdateLocalDependencies());
        map.put("autoUpdateGitDependencies", project.getAutoUpdateGitDependencies());
        return map;
    }

    private Map<String, Object> buildShellConfigMap(ShellConfig config) {
        var map = new LinkedHashMap<String, Object>();
        map.put("executable", config.executable());
        map.put("args", new ArrayList<>(config.args()));
        return map;
    }

    private Map<String, Object> buildAnalyzerLanguagesMap(IProject project) {
        var map = new LinkedHashMap<String, Object>();

        // Configured languages (user-selected)
        var configured = project.getAnalyzerLanguages().stream()
                .filter(lang -> lang != Languages.NONE)
                .map(Language::internalName)
                .toList();
        map.put("configured", configured);

        // Detected languages (from project files)
        var detected = Languages.findLanguagesInProject(project).stream()
                .filter(lang -> lang != Languages.NONE)
                .map(Language::internalName)
                .toList();
        map.put("detected", detected);

        // Available languages (all supported, excluding NONE)
        var available = Languages.ALL_LANGUAGES.stream()
                .filter(lang -> lang != Languages.NONE)
                .map(lang -> {
                    var langMap = new LinkedHashMap<String, String>();
                    langMap.put("name", lang.name());
                    langMap.put("internalName", lang.internalName());
                    return langMap;
                })
                .toList();
        map.put("available", available);

        return map;
    }

    private Map<String, @Nullable Object> buildIssueProviderMap(IssueProvider provider) {
        var map = new LinkedHashMap<String, @Nullable Object>();
        map.put("type", provider.type().name());

        var configMap = new LinkedHashMap<String, @Nullable Object>();
        var config = provider.config();
        if (config instanceof IssuesProviderConfig.GithubConfig github) {
            configMap.put("owner", github.owner());
            configMap.put("repo", github.repo());
            configMap.put("host", github.host());
        } else if (config instanceof IssuesProviderConfig.JiraConfig jira) {
            configMap.put("baseUrl", jira.baseUrl());
            configMap.put("apiToken", jira.apiToken());
            configMap.put("projectKey", jira.projectKey());
        }
        map.put("config", configMap.isEmpty() ? null : configMap);

        return map;
    }

    private void handlePostBuild(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateBuildRequest.class, "/v1/settings/build");
        if (request == null) return;

        try {
            var project = contextManager.getProject();
            var current = project.awaitBuildDetails();

            // Merge only provided fields
            String buildLintCommand =
                    request.buildLintCommand() != null ? request.buildLintCommand() : current.buildLintCommand();
            boolean buildLintEnabled =
                    request.buildLintEnabled() != null ? request.buildLintEnabled() : current.buildLintEnabled();
            String testAllCommand =
                    request.testAllCommand() != null ? request.testAllCommand() : current.testAllCommand();
            boolean testAllEnabled =
                    request.testAllEnabled() != null ? request.testAllEnabled() : current.testAllEnabled();
            String testSomeCommand =
                    request.testSomeCommand() != null ? request.testSomeCommand() : current.testSomeCommand();
            boolean testSomeEnabled =
                    request.testSomeEnabled() != null ? request.testSomeEnabled() : current.testSomeEnabled();
            Set<String> exclusionPatterns = request.exclusionPatterns() != null
                    ? new LinkedHashSet<>(request.exclusionPatterns())
                    : current.exclusionPatterns();
            Map<String, String> environmentVariables = request.environmentVariables() != null
                    ? new LinkedHashMap<>(request.environmentVariables())
                    : current.environmentVariables();
            Integer maxBuildAttempts =
                    request.maxBuildAttempts() != null ? request.maxBuildAttempts() : current.maxBuildAttempts();
            String afterTaskListCommand = request.afterTaskListCommand() != null
                    ? request.afterTaskListCommand()
                    : current.afterTaskListCommand();

            List<ModuleBuildEntry> modules;
            if (request.modules() != null) {
                modules = new ArrayList<>();
                for (var m : request.modules()) {
                    modules.add(new ModuleBuildEntry(
                            m.alias(),
                            m.relativePath(),
                            m.buildLintCommand(),
                            m.testAllCommand(),
                            m.testSomeCommand(),
                            m.language()));
                }
            } else {
                modules = current.modules();
            }

            var newDetails = new BuildDetails(
                    buildLintCommand,
                    buildLintEnabled,
                    testAllCommand,
                    testAllEnabled,
                    testSomeCommand,
                    testSomeEnabled,
                    exclusionPatterns,
                    environmentVariables,
                    maxBuildAttempts,
                    afterTaskListCommand,
                    modules);

            project.saveBuildDetails(newDetails);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/build", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update build settings", e));
        }
    }

    private void handlePostProject(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateProjectRequest.class, "/v1/settings/project");
        if (request == null) return;

        try {
            var project = contextManager.getProject();
            var mainProject = project.getMainProject();

            if (request.commitMessageFormat() != null) {
                project.setCommitMessageFormat(request.commitMessageFormat());
            }

            if (request.codeAgentTestScope() != null) {
                var scope =
                        CodeAgentTestScope.fromString(request.codeAgentTestScope(), project.getCodeAgentTestScope());
                project.setCodeAgentTestScope(scope);
            }

            if (request.runCommandTimeoutSeconds() != null) {
                mainProject.setRunCommandTimeoutSeconds(request.runCommandTimeoutSeconds());
            }

            if (request.testCommandTimeoutSeconds() != null) {
                mainProject.setTestCommandTimeoutSeconds(request.testCommandTimeoutSeconds());
            }

            if (request.autoUpdateLocalDependencies() != null) {
                project.setAutoUpdateLocalDependencies(request.autoUpdateLocalDependencies());
            }

            if (request.autoUpdateGitDependencies() != null) {
                project.setAutoUpdateGitDependencies(request.autoUpdateGitDependencies());
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/project", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update project settings", e));
        }
    }

    private void handlePostShell(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateShellRequest.class, "/v1/settings/shell");
        if (request == null) return;

        if (request.executable() == null || request.executable().isBlank()) {
            RouterUtil.sendValidationError(exchange, "executable is required");
            return;
        }

        try {
            var project = contextManager.getProject();
            List<String> args = request.args() != null ? request.args() : List.of();
            var config = new ShellConfig(request.executable(), args);
            project.setShellConfig(config);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/shell", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update shell config", e));
        }
    }

    private void handlePostIssues(HttpExchange exchange) throws IOException {
        JsonNode requestNode;
        try (var is = exchange.getRequestBody()) {
            requestNode = objectMapper.readTree(is);
        } catch (Exception e) {
            RouterUtil.sendValidationError(exchange, "Invalid JSON");
            return;
        }

        if (requestNode == null || !requestNode.has("type")) {
            RouterUtil.sendValidationError(exchange, "type is required");
            return;
        }

        String typeStr = requestNode.get("type").asText();
        IssueProviderType type;
        try {
            type = IssueProviderType.valueOf(typeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid type: " + typeStr + ". Must be NONE, GITHUB, or JIRA");
            return;
        }

        // Validate JIRA config requirement before entering try block
        if (type == IssueProviderType.JIRA) {
            var configNode = requestNode.get("config");
            if (configNode == null) {
                RouterUtil.sendValidationError(exchange, "config is required for JIRA");
                return;
            }
        }

        try {
            var project = contextManager.getProject();
            IssueProvider provider =
                    switch (type) {
                        case NONE -> IssueProvider.none();
                        case GITHUB -> {
                            var configNode = requestNode.get("config");
                            if (configNode == null) {
                                yield IssueProvider.github();
                            } else {
                                String owner = configNode.has("owner")
                                        ? configNode.get("owner").asText("")
                                        : "";
                                String repo = configNode.has("repo")
                                        ? configNode.get("repo").asText("")
                                        : "";
                                String host = configNode.has("host")
                                        ? configNode.get("host").asText("")
                                        : "";
                                yield IssueProvider.github(owner, repo, host);
                            }
                        }
                        case JIRA -> {
                            var configNode = requestNode.get("config");
                            // configNode is guaranteed non-null here due to validation above
                            String baseUrl = configNode.has("baseUrl")
                                    ? configNode.get("baseUrl").asText("")
                                    : "";
                            String apiToken = configNode.has("apiToken")
                                    ? configNode.get("apiToken").asText("")
                                    : "";
                            String projectKey = configNode.has("projectKey")
                                    ? configNode.get("projectKey").asText("")
                                    : "";
                            yield IssueProvider.jira(baseUrl, apiToken, projectKey);
                        }
                    };

            project.setIssuesProvider(provider);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/issues", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update issue provider", e));
        }
    }

    private void handlePostDataRetention(HttpExchange exchange) throws IOException {
        var request =
                RouterUtil.parseJsonOr400(exchange, UpdateDataRetentionRequest.class, "/v1/settings/data-retention");
        if (request == null) return;

        if (request.policy() == null || request.policy().isBlank()) {
            RouterUtil.sendValidationError(exchange, "policy is required");
            return;
        }

        DataRetentionPolicy policy;
        try {
            policy = DataRetentionPolicy.valueOf(request.policy().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(
                    exchange, "Invalid policy: " + request.policy() + ". Must be IMPROVE_BROKK or MINIMAL");
            return;
        }

        if (policy == DataRetentionPolicy.UNSET) {
            RouterUtil.sendValidationError(exchange, "Cannot set policy to UNSET");
            return;
        }

        try {
            var project = contextManager.getProject();
            project.setDataRetentionPolicy(policy);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/data-retention", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update data retention policy", e));
        }
    }

    // Request DTOs

    private record UpdateBuildRequest(
            @Nullable String buildLintCommand,
            @Nullable Boolean buildLintEnabled,
            @Nullable String testAllCommand,
            @Nullable Boolean testAllEnabled,
            @Nullable String testSomeCommand,
            @Nullable Boolean testSomeEnabled,
            @Nullable List<String> exclusionPatterns,
            @Nullable Map<String, String> environmentVariables,
            @Nullable Integer maxBuildAttempts,
            @Nullable String afterTaskListCommand,
            @Nullable List<ModuleRequest> modules) {}

    private record ModuleRequest(
            @Nullable String alias,
            @Nullable String relativePath,
            @Nullable String buildLintCommand,
            @Nullable String testAllCommand,
            @Nullable String testSomeCommand,
            @Nullable String language) {}

    private record UpdateProjectRequest(
            @Nullable String commitMessageFormat,
            @Nullable String codeAgentTestScope,
            @Nullable Long runCommandTimeoutSeconds,
            @Nullable Long testCommandTimeoutSeconds,
            @Nullable Boolean autoUpdateLocalDependencies,
            @Nullable Boolean autoUpdateGitDependencies) {}

    private record UpdateShellRequest(@Nullable String executable, @Nullable List<String> args) {}

    private record UpdateDataRetentionRequest(@Nullable String policy) {}

    private record UpdateLanguagesRequest(@Nullable List<String> languages) {}

    private void handlePostLanguages(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateLanguagesRequest.class, "/v1/settings/languages");
        if (request == null) return;

        if (request.languages() == null) {
            RouterUtil.sendValidationError(exchange, "languages is required");
            return;
        }

        try {
            var project = contextManager.getProject();
            var convertedSet = new LinkedHashSet<Language>();

            for (var name : request.languages()) {
                try {
                    var lang = Languages.valueOf(name);
                    if (lang != Languages.NONE) {
                        convertedSet.add(lang);
                    }
                } catch (IllegalArgumentException e) {
                    RouterUtil.sendValidationError(exchange, "Invalid language: " + name);
                    return;
                }
            }

            project.setAnalyzerLanguages(convertedSet);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings/languages", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update languages", e));
        }
    }
}
