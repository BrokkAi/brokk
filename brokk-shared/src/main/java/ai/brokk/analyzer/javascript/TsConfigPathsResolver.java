package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal TSConfig "paths" + "baseUrl" resolver for in-project module resolution.
 *
 * <p>This resolver is intentionally shallow. It only expands specifiers to candidate file paths; callers are
 * responsible for deciding which candidates qualify as in-project and for performing extension/index probing.
 */
public final class TsConfigPathsResolver {

    public record Expansion(List<String> candidates, boolean hadAnyMapping) {
        public static Expansion emptyNoMapping() {
            return new Expansion(List.of(), false);
        }

        public static Expansion withCandidates(List<String> candidates) {
            return new Expansion(List.copyOf(candidates), true);
        }
    }

    private record ParsedConfig(Path tsconfigDir, @Nullable Path baseUrlRel, Map<String, List<String>> paths) {}

    private final Path projectRoot;
    private final Map<Path, Optional<ParsedConfig>> configCache = new LinkedHashMap<>();

    public TsConfigPathsResolver(Path projectRoot) {
        this.projectRoot = projectRoot.normalize();
    }

    public Expansion expand(ProjectFile importingFile, String specifier) {
        Optional<ParsedConfig> cfgOpt = nearestConfigFor(importingFile);
        if (cfgOpt.isEmpty()) {
            return Expansion.emptyNoMapping();
        }
        ParsedConfig cfg = cfgOpt.get();

        // Paths mapping
        if (!cfg.paths().isEmpty()) {
            List<String> fromPaths = expandViaPaths(cfg, specifier);
            if (!fromPaths.isEmpty()) {
                return Expansion.withCandidates(fromPaths);
            }
        }

        // baseUrl fallback (only when it exists)
        if (cfg.baseUrlRel() != null) {
            Path baseUrlAbs = cfg.tsconfigDir().resolve(cfg.baseUrlRel()).normalize();
            if (baseUrlAbs.startsWith(projectRoot)) {
                return Expansion.withCandidates(List.of(
                        projectRoot.relativize(baseUrlAbs.resolve(specifier)).toString()));
            }
            return Expansion.withCandidates(
                    List.of(baseUrlAbs.resolve(specifier).toString()));
        }

        return Expansion.emptyNoMapping();
    }

    private List<String> expandViaPaths(ParsedConfig cfg, String specifier) {
        // Exact match first
        List<String> exact = cfg.paths().get(specifier);
        if (exact != null && !exact.isEmpty()) {
            return resolveTargets(cfg, exact, null);
        }

        // Single-wildcard patterns
        for (Map.Entry<String, List<String>> e : cfg.paths().entrySet()) {
            String pattern = e.getKey();
            int star = pattern.indexOf('*');
            if (star < 0 || pattern.indexOf('*', star + 1) >= 0) {
                continue;
            }

            String prefix = pattern.substring(0, star);
            String suffix = pattern.substring(star + 1);
            if (!specifier.startsWith(prefix)
                    || !specifier.endsWith(suffix)
                    || specifier.length() < prefix.length() + suffix.length()) {
                continue;
            }
            String captured = specifier.substring(prefix.length(), specifier.length() - suffix.length());
            List<String> targets = e.getValue();
            if (targets == null || targets.isEmpty()) continue;
            return resolveTargets(cfg, targets, captured);
        }

        return List.of();
    }

    private List<String> resolveTargets(ParsedConfig cfg, List<String> targets, @Nullable String starCapture) {
        Path base = cfg.baseUrlRel() != null
                ? cfg.tsconfigDir().resolve(cfg.baseUrlRel()).normalize()
                : cfg.tsconfigDir();

        var out = new ArrayList<String>();
        for (String t : targets) {
            if (t == null || t.isBlank()) continue;
            String mapped = starCapture == null ? t : t.replace("*", starCapture);
            Path candidateAbs = base.resolve(mapped).normalize();
            if (candidateAbs.startsWith(projectRoot)) {
                out.add(projectRoot.relativize(candidateAbs).toString());
            } else {
                out.add(candidateAbs.toString());
            }
        }
        return List.copyOf(out);
    }

    private Optional<ParsedConfig> nearestConfigFor(ProjectFile importingFile) {
        Path parent = importingFile.absPath().getParent();
        if (parent == null) {
            return Optional.empty();
        }

        var visited = new ArrayList<Path>();
        Path current = parent.normalize();
        while (current != null && current.startsWith(projectRoot)) {
            Optional<ParsedConfig> cached = configCache.get(current);
            if (cached != null) {
                return cached;
            }

            visited.add(current);

            Path tsconfigPath = current.resolve("tsconfig.json");
            Optional<ParsedConfig> parsed = parseConfig(current, tsconfigPath);
            if (parsed.isPresent()) {
                for (Path v : visited) {
                    configCache.put(v, parsed);
                }
                return parsed;
            }

            Path next = current.getParent();
            if (next == null || Objects.equals(next, current)) {
                break;
            }
            current = next.normalize();
        }

        for (Path v : visited) {
            configCache.put(v, Optional.empty());
        }
        return Optional.empty();
    }

    private Optional<ParsedConfig> parseConfig(Path tsconfigDir, Path tsconfigPath) {
        if (!Files.exists(tsconfigPath)) {
            return Optional.empty();
        }

        try {
            String text = Files.readString(tsconfigPath);
            JsonNode root = Json.getMapper().readTree(text);
            JsonNode compilerOptions = root.path("compilerOptions");
            Path baseUrlRel = null;
            if (compilerOptions.hasNonNull("baseUrl")
                    && compilerOptions.get("baseUrl").isTextual()) {
                baseUrlRel = Path.of(compilerOptions.get("baseUrl").asText());
            }

            Map<String, List<String>> paths = new LinkedHashMap<>();
            JsonNode pathsNode = compilerOptions.path("paths");
            if (pathsNode.isObject()) {
                pathsNode.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode arr = entry.getValue();
                    if (!arr.isArray()) {
                        return;
                    }
                    var vals = new ArrayList<String>();
                    arr.forEach(n -> {
                        if (n != null && n.isTextual()) {
                            vals.add(n.asText());
                        }
                    });
                    if (!vals.isEmpty()) {
                        paths.put(key, List.copyOf(vals));
                    }
                });
            }

            return Optional.of(new ParsedConfig(tsconfigDir, baseUrlRel, Collections.unmodifiableMap(paths)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // Intentionally does not validate against project file-set membership; callers perform "in-project" filtering
    // via existing module extension/index probing against the analyzer project file set.
}
