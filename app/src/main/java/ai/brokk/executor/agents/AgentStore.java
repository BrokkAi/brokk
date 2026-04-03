package ai.brokk.executor.agents;

import ai.brokk.concurrent.AtomicWrites;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * File-based store for custom agent definitions.
 * Agents are stored as markdown files with YAML frontmatter in two locations:
 * <ul>
 *   <li>Project-level: {@code .brokk/agents/*.md}</li>
 *   <li>User-level: {@code ~/.brokk/agents/*.md}</li>
 * </ul>
 * Project-level agents override user-level agents with the same name.
 */
public class AgentStore {
    private static final Logger logger = LogManager.getLogger(AgentStore.class);
    private static final String FRONTMATTER_DELIMITER = "---";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z][a-z0-9-]*");

    private final Path projectDir;
    private final Path userDir;

    public AgentStore(Path projectDir, Path userDir) {
        this.projectDir = projectDir;
        this.userDir = userDir;
    }

    /**
     * Lists all agents, merged from both levels. Project agents override user agents with the same name.
     */
    public List<AgentDefinition> list() {
        var byName = new LinkedHashMap<String, AgentDefinition>();
        // Load user-level first so project-level can override
        loadFromDirectory(userDir, "user").forEach(def -> byName.put(def.name(), def));
        loadFromDirectory(projectDir, "project").forEach(def -> byName.put(def.name(), def));
        return List.copyOf(byName.values());
    }

    /**
     * Gets an agent by name. Project-level takes precedence.
     */
    public Optional<AgentDefinition> get(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        var projectAgent = loadFromFile(projectDir.resolve(name + ".md"), "project");
        if (projectAgent.isPresent()) {
            return projectAgent;
        }
        return loadFromFile(userDir.resolve(name + ".md"), "user");
    }

    /**
     * Checks whether an agent exists in a specific scope only (does not merge).
     */
    public boolean exists(String name, String scope) {
        if (!SAFE_NAME.matcher(name).matches()) {
            return false;
        }
        var dir = "user".equals(scope) ? userDir : projectDir;
        return Files.exists(dir.resolve(name + ".md"));
    }

    /**
     * Saves an agent definition to project-level storage.
     */
    public void save(AgentDefinition def) throws IOException {
        save(def, "project");
    }

    /**
     * Saves an agent definition to the specified scope.
     */
    public void save(AgentDefinition def, String scope) throws IOException {
        if (!SAFE_NAME.matcher(def.name()).matches()) {
            throw new IllegalArgumentException("Invalid agent name: " + def.name());
        }
        var dir = "user".equals(scope) ? userDir : projectDir;
        Files.createDirectories(dir);
        var file = dir.resolve(def.name() + ".md");
        var content = toMarkdown(def);
        AtomicWrites.save(file, content);
        logger.info("Saved agent '{}' to {} ({})", def.name(), file, scope);
    }

    /**
     * Deletes an agent from project-level storage.
     *
     * @return true if the file existed and was deleted
     */
    public boolean delete(String name) throws IOException {
        return delete(name, "project");
    }

    /**
     * Deletes an agent from the specified scope.
     *
     * @return true if the file existed and was deleted
     */
    public boolean delete(String name, String scope) throws IOException {
        if (!SAFE_NAME.matcher(name).matches()) {
            return false;
        }
        var dir = "user".equals(scope) ? userDir : projectDir;
        var file = dir.resolve(name + ".md");
        if (Files.exists(file)) {
            Files.delete(file);
            logger.info("Deleted agent '{}' from {} ({})", name, file, scope);
            return true;
        }
        return false;
    }

    /**
     * Serializes an agent definition to markdown+YAML frontmatter format.
     */
    static String toMarkdown(AgentDefinition def) {
        var sb = new StringBuilder();
        sb.append(FRONTMATTER_DELIMITER).append('\n');
        sb.append("name: ").append(def.name()).append('\n');
        sb.append("description: ").append(yamlQuote(def.description())).append('\n');
        if (def.tools() != null && !def.tools().isEmpty()) {
            sb.append("tools:\n");
            for (var tool : def.tools()) {
                sb.append("  - ").append(tool).append('\n');
            }
        }
        if (def.maxTurns() != null) {
            sb.append("maxTurns: ").append(def.maxTurns()).append('\n');
        }
        sb.append(FRONTMATTER_DELIMITER).append('\n');
        sb.append('\n');
        sb.append(def.systemPrompt()).append('\n');
        return sb.toString();
    }

    private static String yamlQuote(String value) {
        if (value.contains(":")
                || value.contains("#")
                || value.contains("'")
                || value.contains("\"")
                || value.contains("\n")
                || value.startsWith(" ")
                || value.endsWith(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        return value;
    }

    private List<AgentDefinition> loadFromDirectory(Path dir, String scope) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .map(p -> loadFromFile(p, scope))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to list agents in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private Optional<AgentDefinition> loadFromFile(Path file, String scope) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            var content = Files.readString(file);
            var def = parseMarkdown(content, scope);
            var errors = def.validate();
            if (!errors.isEmpty()) {
                logger.warn("Invalid agent file {}: {}", file, errors);
                return Optional.empty();
            }
            return Optional.of(def);
        } catch (Exception e) {
            logger.warn("Failed to parse agent file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses a markdown+YAML frontmatter string into an AgentDefinition.
     */
    static AgentDefinition parseMarkdown(String content, String scope) throws IOException {
        var lines = content.strip().split("\n");
        if (lines.length == 0 || !lines[0].strip().equals(FRONTMATTER_DELIMITER)) {
            throw new IOException("Agent file must start with '---' YAML frontmatter delimiter");
        }

        // Find the closing delimiter as a standalone line
        int closingLine = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FRONTMATTER_DELIMITER)) {
                closingLine = i;
                break;
            }
        }
        if (closingLine < 0) {
            throw new IOException("Agent file must have a closing '---' YAML frontmatter delimiter");
        }

        var yamlContent =
                String.join("\n", Arrays.copyOfRange(lines, 1, closingLine)).strip();
        var markdownBody = String.join("\n", Arrays.copyOfRange(lines, closingLine + 1, lines.length))
                .strip();

        // Parse YAML frontmatter
        var frontmatter = YAML_MAPPER.readValue(yamlContent, Frontmatter.class);

        if (frontmatter.name == null || frontmatter.name.isBlank()) {
            throw new IOException("Agent file must have a 'name' field in YAML frontmatter");
        }
        if (frontmatter.description == null || frontmatter.description.isBlank()) {
            throw new IOException("Agent file must have a 'description' field in YAML frontmatter");
        }

        return new AgentDefinition(
                frontmatter.name,
                frontmatter.description,
                frontmatter.tools,
                frontmatter.maxTurns,
                markdownBody,
                scope);
    }

    /**
     * Internal record for YAML frontmatter deserialization.
     * All fields nullable because YAML files may omit them.
     */
    private record Frontmatter(
            @Nullable String name,
            @Nullable String description,
            @Nullable List<String> tools,
            @Nullable Integer maxTurns) {}
}
