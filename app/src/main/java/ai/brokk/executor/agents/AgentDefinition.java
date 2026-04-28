package ai.brokk.executor.agents;

import ai.brokk.project.IProject;
import ai.brokk.tools.WorkspaceTools;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Parsed agent definition from a markdown+YAML frontmatter file.
 * The systemPrompt is the markdown body below the YAML frontmatter.
 */
public record AgentDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("tools") @Nullable List<String> tools,
        @JsonProperty("maxTurns") @Nullable Integer maxTurns,
        @JsonProperty("systemPrompt") String systemPrompt,
        @JsonProperty("scope") String scope) {

    public static final int DEFAULT_MAX_TURNS = 20;
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

    /**
     * Search tools that are safe to execute in parallel (read-only, no side effects).
     */
    public static final Set<String> PARALLEL_SAFE_SEARCH_TOOL_NAMES = Set.of(
            "searchSymbols",
            "scanUsages",
            "getSymbolLocations",
            "getSummaries",
            "skimFiles",
            "findFilesContaining",
            "findFilenames",
            "searchFileContents",
            "getClassSkeletons",
            "getClassSources",
            "getMethodSources",
            "getFileContents",
            "listFiles",
            "searchGitCommitMessages",
            "getGitLog",
            "explainCommit",
            "xmlSkim",
            "xmlSelect",
            "jq",
            "computeCyclomaticComplexity",
            "computeCognitiveComplexity",
            "reportLongMethodAndGodObjectSmells",
            "reportCommentDensityForCodeUnit",
            "reportCommentDensityForFiles",
            "reportExceptionHandlingSmells",
            "reportStructuralCloneSmells",
            "reportSecretLikeCode",
            "reportTestAssertionSmells",
            "analyzeGitHotspots");

    /**
     * Tool names that a read-only agent may use.
     * An agent whose {@link #effectiveTools} is a subset of this set is considered read-only
     * and eligible for parallel execution.
     */
    public static final Set<String> READ_ONLY_TOOL_NAMES = Set.of(
            // parallel-safe search tools
            "searchSymbols",
            "scanUsages",
            "getSymbolLocations",
            "getSummaries",
            "skimFiles",
            "findFilesContaining",
            "findFilenames",
            "searchFileContents",
            "getClassSkeletons",
            "getClassSources",
            "getMethodSources",
            "getFileContents",
            "listFiles",
            "searchGitCommitMessages",
            "getGitLog",
            "explainCommit",
            "xmlSkim",
            "xmlSelect",
            "jq",
            "computeCyclomaticComplexity",
            "computeCognitiveComplexity",
            "reportLongMethodAndGodObjectSmells",
            "reportCommentDensityForCodeUnit",
            "reportCommentDensityForFiles",
            "reportExceptionHandlingSmells",
            "reportStructuralCloneSmells",
            "reportSecretLikeCode",
            "reportTestAssertionSmells",
            "analyzeGitHotspots",
            // terminal / built-in
            "answer",
            "abortSearch",
            "think");

    /**
     * All tool method names that custom agents may reference.
     */
    public static final Set<String> KNOWN_TOOL_NAMES = Set.of(
            // SearchTools
            "searchSymbols",
            "scanUsages",
            "getSymbolLocations",
            "getSummaries",
            "skimFiles",
            "findFilesContaining",
            "findFilenames",
            "searchFileContents",
            "getClassSources",
            "getMethodSources",
            "getFileContents",
            "listFiles",
            "searchGitCommitMessages",
            "getGitLog",
            "explainCommit",
            "xmlSkim",
            "xmlSelect",
            "jq",
            // WorkspaceTools
            "addFilesToWorkspace",
            "addLineRangeToWorkspace",
            "addClassesToWorkspace",
            "addUrlContentsToWorkspace",
            "addClassSummariesToWorkspace",
            "addFileSummariesToWorkspace",
            "addMethodsToWorkspace",
            "dropWorkspaceFragments",
            "createOrReplaceTaskList",
            // ShellTools
            "runShellCommand",
            // DependencyTools
            "importDependency",
            // CodeQualityTools
            "computeCyclomaticComplexity",
            "computeCognitiveComplexity",
            "reportLongMethodAndGodObjectSmells",
            "reportCommentDensityForCodeUnit",
            "reportCommentDensityForFiles",
            "reportExceptionHandlingSmells",
            "reportStructuralCloneSmells",
            "reportSecretLikeCode",
            "reportTestAssertionSmells",
            "analyzeGitHotspots",
            // Terminal
            "answer",
            "abortSearch",
            // Built-in
            "think");

    public int effectiveMaxTurns() {
        return maxTurns != null ? maxTurns : DEFAULT_MAX_TURNS;
    }

    /**
     * Returns true if this agent uses only read-only tools (search + terminal),
     * making it safe for parallel execution alongside other read-only agents.
     */
    public boolean isReadOnly(IProject project) {
        return READ_ONLY_TOOL_NAMES.containsAll(effectiveTools(project));
    }

    /**
     * Returns the effective tool list, defaulting to SearchAgent's ANSWER_ONLY set
     * and filtered by analyzer availability.
     */
    public List<String> effectiveTools(IProject project) {
        var names = new ArrayList<>(tools != null && !tools.isEmpty() ? tools : defaultToolNames(project));
        // Always include terminal tools and think
        if (!names.contains("answer")) names.add("answer");
        if (!names.contains("abortSearch")) names.add("abortSearch");
        if (!names.contains("think")) names.add("think");
        return WorkspaceTools.filterByAnalyzerAvailability(names, project);
    }

    /**
     * Default tool set matching SearchAgent's ANSWER_ONLY configuration.
     */
    @JsonIgnore
    private static List<String> defaultToolNames(IProject project) {
        var names = new ArrayList<String>();
        names.add("searchSymbols");
        names.add("scanUsages");
        names.add("getSymbolLocations");
        names.add("getSummaries");
        names.add("skimFiles");
        names.add("findFilesContaining");
        names.add("findFilenames");
        names.add("searchFileContents");
        names.add("addClassesToWorkspace");
        names.add("addClassSummariesToWorkspace");
        names.add("addMethodsToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addLineRangeToWorkspace");
        names.add("addFilesToWorkspace");
        names.add("addUrlContentsToWorkspace");
        if (project.hasGit()) {
            names.add("searchGitCommitMessages");
            names.add("getGitLog");
            names.add("explainCommit");
        }
        if (project.getAllFiles().stream().anyMatch(f -> f.extension().equals("xml"))) {
            names.add("xmlSkim");
            names.add("xmlSelect");
        }
        if (project.getAllFiles().stream().anyMatch(f -> f.extension().equals("json"))) {
            names.add("jq");
        }
        return names;
    }

    /**
     * Validates this definition, returning a list of error messages (empty if valid).
     */
    @JsonIgnore
    public List<String> validate() {
        var errors = new ArrayList<String>();
        if (name.isBlank()) {
            errors.add("name is required");
        } else if (!NAME_PATTERN.matcher(name).matches()) {
            errors.add("name must match [a-z][a-z0-9-]* (lowercase letters, digits, hyphens)");
        }
        if (description.isBlank()) {
            errors.add("description is required");
        }
        if (systemPrompt.isBlank()) {
            errors.add("systemPrompt is required");
        }
        if (tools != null) {
            for (var tool : tools) {
                if (!KNOWN_TOOL_NAMES.contains(tool)) {
                    errors.add("unknown tool: " + tool);
                }
            }
        }
        if (maxTurns != null && maxTurns <= 0) {
            errors.add("maxTurns must be positive");
        }
        return errors;
    }
}
