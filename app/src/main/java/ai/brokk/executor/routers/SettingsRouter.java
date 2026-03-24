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
            } else if ("POST".equals(method)) {
                handlePostSettings(exchange);
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

    private void handlePostSettings(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateAllSettingsRequest.class, "/v1/settings");
        if (request == null) return;

        try {
            var project = contextManager.getProject();

            if (request.buildDetails() != null) {
                applyBuildSettings(project, request.buildDetails());
            }
            if (request.projectSettings() != null) {
                applyProjectSettings(project, request.projectSettings());
            }
            if (request.shellConfig() != null) {
                applyShellConfig(project, request.shellConfig());
            }
            if (request.issueProvider() != null) {
                applyIssueProvider(project, request.issueProvider());
            }
            if (request.dataRetentionPolicy() != null) {
                applyDataRetention(project, request.dataRetentionPolicy());
            }
            if (request.analyzerLanguages() != null) {
                applyLanguages(project, request.analyzerLanguages());
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "updated"));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling POST /v1/settings", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update settings", e));
        }
    }

    private void applyBuildSettings(IProject project, UpdateBuildRequest request) {
        var current = project.awaitBuildDetails();

        String buildLintCommand =
                request.buildLintCommand() != null ? request.buildLintCommand() : current.buildLintCommand();
        boolean buildLintEnabled =
                request.buildLintEnabled() != null ? request.buildLintEnabled() : current.buildLintEnabled();
        String testAllCommand = request.testAllCommand() != null ? request.testAllCommand() : current.testAllCommand();
        boolean testAllEnabled = request.testAllEnabled() != null ? request.testAllEnabled() : current.testAllEnabled();
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
    }

    private void applyProjectSettings(IProject project, UpdateProjectRequest request) {
        var mainProject = project.getMainProject();

        if (request.commitMessageFormat() != null) {
            project.setCommitMessageFormat(request.commitMessageFormat());
        }
        if (request.codeAgentTestScope() != null) {
            var scope = CodeAgentTestScope.fromString(request.codeAgentTestScope(), project.getCodeAgentTestScope());
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
    }

    private void applyShellConfig(IProject project, UpdateShellRequest request) {
        if (request.executable() == null || request.executable().isBlank()) {
            throw new IllegalArgumentException("executable is required");
        }
        List<String> args = request.args() != null ? request.args() : List.of();
        project.setShellConfig(new ShellConfig(request.executable(), args));
    }

    private void applyIssueProvider(IProject project, JsonNode requestNode) {
        if (requestNode == null || !requestNode.has("type")) {
            throw new IllegalArgumentException("type is required");
        }

        String typeStr = requestNode.get("type").asText();
        IssueProviderType type;
        try {
            type = IssueProviderType.valueOf(typeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid type: " + typeStr + ". Must be NONE, GITHUB, or JIRA");
        }

        if (type == IssueProviderType.JIRA) {
            var configNode = requestNode.get("config");
            if (configNode == null) {
                throw new IllegalArgumentException("config is required for JIRA");
            }
        }

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
    }

    private void applyDataRetention(IProject project, String policyStr) {
        DataRetentionPolicy policy;
        try {
            policy = DataRetentionPolicy.valueOf(policyStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid policy: " + policyStr + ". Must be IMPROVE_BROKK or MINIMAL");
        }
        if (policy == DataRetentionPolicy.UNSET) {
            throw new IllegalArgumentException("Cannot set policy to UNSET");
        }
        project.setDataRetentionPolicy(policy);
    }

    private void applyLanguages(IProject project, UpdateLanguagesRequest request) {
        if (request.languages() == null) {
            throw new IllegalArgumentException("languages is required");
        }
        var convertedSet = new LinkedHashSet<Language>();
        for (var name : request.languages()) {
            try {
                var lang = Languages.valueOf(name);
                if (lang != Languages.NONE) {
                    convertedSet.add(lang);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid language: " + name);
            }
        }
        project.setAnalyzerLanguages(convertedSet);
    }

    // Request DTOs

    private record UpdateAllSettingsRequest(
            @Nullable UpdateBuildRequest buildDetails,
            @Nullable UpdateProjectRequest projectSettings,
            @Nullable UpdateShellRequest shellConfig,
            @Nullable JsonNode issueProvider,
            @Nullable String dataRetentionPolicy,
            @Nullable UpdateLanguagesRequest analyzerLanguages) {}

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

}
