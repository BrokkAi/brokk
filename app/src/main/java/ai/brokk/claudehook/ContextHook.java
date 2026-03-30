package ai.brokk.claudehook;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lightweight CLI for Claude Code hooks.
 *
 * Boots the Brokk analyzer for a project directory (no LLM, no API keys),
 * produces a compact project index (file paths + top-level symbol names),
 * and writes it to stdout as JSON suitable for a UserPromptSubmit hook's
 * additionalContext.
 *
 * This gives Claude structured code intelligence — it knows what classes,
 * methods, and fields exist in which files — so it can make informed decisions
 * about what to read, without needing to grep blindly.
 *
 * Usage:
 *   java ai.brokk.claudehook.ContextHook [--project path]
 *
 * When invoked as a Claude Code hook, reads hook input JSON from stdin
 * (which contains the user's prompt) and returns hook output JSON to stdout.
 */
public class ContextHook {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap the symbol map at ~100k chars to avoid overwhelming the context window. */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        Path projectPath = null;

        // Parse args
        for (int i = 0; i < args.length; i++) {
            if ("--project".equals(args[i]) && i + 1 < args.length) {
                projectPath = Path.of(args[++i]).toAbsolutePath().normalize();
            }
        }

        // Read hook input from stdin
        String prompt = null;
        if (System.in.available() > 0) {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            if (!input.isBlank()) {
                try {
                    var hookInput = MAPPER.readTree(input);
                    if (hookInput.has("prompt")) {
                        prompt = hookInput.get("prompt").asText();
                    }
                    if (projectPath == null && hookInput.has("cwd")) {
                        projectPath = Path.of(hookInput.get("cwd").asText()).toAbsolutePath().normalize();
                    }
                } catch (Exception e) {
                    prompt = input;
                }
            }
        }

        if (projectPath == null) {
            projectPath = Path.of(".").toAbsolutePath().normalize();
        }

        // Boot the analyzer (pure local, no LLM, no global Brokk config)
        var project = new LightweightProject(projectPath);
        var analyzer = createAnalyzer(project);
        if (analyzer == null) {
            // No analyzable languages — silently exit so the hook is a no-op
            return;
        }

        // Build the compact project index
        var index = buildProjectIndex(analyzer, project);

        // Format output as hook JSON
        var output = MAPPER.createObjectNode();
        var hookOutput = MAPPER.createObjectNode();
        hookOutput.put("hookEventName", "UserPromptSubmit");
        hookOutput.put("additionalContext", index);
        output.set("hookSpecificOutput", hookOutput);

        System.out.println(MAPPER.writeValueAsString(output));
    }

    private static @Nullable IAnalyzer createAnalyzer(IProject project) {
        Set<Language> languages = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .collect(Collectors.toSet());

        if (languages.isEmpty()) {
            return null;
        }

        // Load or create per-language analyzers, saving each one's cache
        Language langHandle = Languages.aggregate(languages);
        var noOp = IAnalyzer.ProgressListener.NOOP;
        var analyzer = langHandle.loadAnalyzer(project, noOp);

        // Persist cache for each individual language so subsequent runs are fast.
        // MultiLanguage.saveAnalyzer throws, so save per-language instead.
        for (var lang : languages) {
            analyzer.subAnalyzer(lang).ifPresent(sub -> lang.saveAnalyzer(sub, project));
        }

        return analyzer;
    }

    /**
     * Produces a compact index: each file followed by its top-level class/function names.
     * This is the lightweight "filename + symbols" pass that ContextAgent uses for
     * its first pruning stage. Enough for Claude to know what exists where.
     */
    private static String buildProjectIndex(IAnalyzer analyzer, IProject project) {
        var sb = new StringBuilder();
        sb.append("# Brokk Project Index\n");
        sb.append("# file -> top-level symbols (classes, functions, fields)\n\n");

        var allFiles = project.getAllFiles().stream().sorted().toList();

        for (var file : allFiles) {
            var declarations = analyzer.getTopLevelDeclarations(file).stream()
                    .filter(cu -> !cu.isModule())
                    .toList();
            if (declarations.isEmpty()) continue;

            sb.append(file);
            sb.append(": ");
            sb.append(declarations.stream()
                    .map(CodeUnit::shortName)
                    .collect(Collectors.joining(", ")));
            sb.append('\n');

            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("... (truncated, ").append(allFiles.size()).append(" total files)\n");
                break;
            }
        }

        return sb.toString();
    }
}
