package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal TSConfig "paths" + "baseUrl" resolver for in-project module resolution.
 *
 * <p>This resolver is intentionally shallow. It only expands specifiers to candidate file paths; callers are
 * responsible for deciding which candidates qualify as in-project and for performing extension/index probing.
 */
public final class TsConfigPathsResolver {
    private static final String TSCONFIG_JSON = "tsconfig.json";

    public record Expansion(List<String> candidates, boolean hadAnyMapping) {
        public static Expansion emptyNoMapping() {
            return new Expansion(List.of(), false);
        }

        public static Expansion withCandidates(List<String> candidates) {
            return new Expansion(List.copyOf(candidates), true);
        }
    }

    private record ParsedConfig(Path tsconfigDir, @Nullable Path baseUrlAbs, Map<String, List<Path>> paths) {}

    private final Path projectRoot;
    private final Map<Path, Optional<ParsedConfig>> configCache = new ConcurrentHashMap<>();

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
        if (cfg.baseUrlAbs() != null) {
            Path baseUrlAbs = cfg.baseUrlAbs();
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
        List<Path> exact = cfg.paths().get(specifier);
        if (exact != null && !exact.isEmpty()) {
            return resolveTargets(exact, null);
        }

        // Single-wildcard patterns
        for (Map.Entry<String, List<Path>> e : cfg.paths().entrySet()) {
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
            List<Path> targets = e.getValue();
            if (targets == null || targets.isEmpty()) continue;
            return resolveTargets(targets, captured);
        }

        return List.of();
    }

    private List<String> resolveTargets(List<Path> targets, @Nullable String starCapture) {
        var out = new ArrayList<String>();
        for (Path target : targets) {
            String mapped =
                    starCapture == null ? target.toString() : target.toString().replace("*", starCapture);
            Path candidateAbs = Path.of(mapped).normalize();
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

        Path current = parent.normalize();
        while (current != null && current.startsWith(projectRoot)) {
            Optional<ParsedConfig> parsed = configCache.computeIfAbsent(current, this::parseConfigForDir);
            if (parsed.isPresent()) {
                return parsed;
            }

            Path next = current.getParent();
            if (next == null || Objects.equals(next, current)) {
                break;
            }
            current = next.normalize();
        }
        return Optional.empty();
    }

    private Optional<ParsedConfig> parseConfigForDir(Path tsconfigDir) {
        return parseConfig(tsconfigDir, tsconfigDir.resolve(TSCONFIG_JSON), new HashSet<>());
    }

    private Optional<ParsedConfig> parseConfig(Path tsconfigDir, Path tsconfigPath, Set<Path> visiting) {
        if (!Files.exists(tsconfigPath)) {
            return Optional.empty();
        }
        Path normalizedPath = tsconfigPath.normalize();
        if (!visiting.add(normalizedPath)) {
            return Optional.empty();
        }

        try {
            String text = Files.readString(tsconfigPath);
            JsonNode root = Json.getMapper().readTree(text);
            Path configDir = requireParent(normalizedPath);

            JsonNode extendsNode = root.path("extends");
            Optional<ParsedConfig> parent = extendsNode.isTextual()
                    ? resolveExtendedConfigPath(configDir, extendsNode.asText())
                            .flatMap(path -> parseConfig(requireParent(path), path, visiting))
                    : Optional.empty();

            JsonNode compilerOptions = root.path("compilerOptions");
            Path baseUrlAbs =
                    parent.flatMap(cfg -> Optional.ofNullable(cfg.baseUrlAbs())).orElse(null);
            if (compilerOptions.hasNonNull("baseUrl")
                    && compilerOptions.get("baseUrl").isTextual()) {
                baseUrlAbs = configDir
                        .resolve(Path.of(compilerOptions.get("baseUrl").asText()))
                        .normalize();
            }

            Map<String, List<Path>> paths = new LinkedHashMap<>();
            parent.ifPresent(cfg -> paths.putAll(cfg.paths()));
            JsonNode pathsNode = compilerOptions.path("paths");
            if (pathsNode.isObject()) {
                Path resolutionBase = baseUrlAbs != null ? baseUrlAbs : configDir;
                pathsNode.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode arr = entry.getValue();
                    if (!arr.isArray()) {
                        return;
                    }
                    var vals = new ArrayList<Path>();
                    arr.forEach(n -> {
                        if (n != null && n.isTextual()) {
                            vals.add(resolutionBase.resolve(Path.of(n.asText())).normalize());
                        }
                    });
                    if (!vals.isEmpty()) {
                        paths.put(key, List.copyOf(vals));
                    }
                });
            }

            return Optional.of(new ParsedConfig(tsconfigDir, baseUrlAbs, Collections.unmodifiableMap(paths)));
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            visiting.remove(normalizedPath);
        }
    }

    private static Path requireParent(Path path) {
        Path parent = path.getParent();
        return parent != null ? parent : path.getRoot();
    }

    private Optional<Path> resolveExtendedConfigPath(Path configDir, String rawExtends) {
        if (rawExtends.isBlank()) {
            return Optional.empty();
        }
        Path candidate = Path.of(rawExtends);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : configDir.resolve(candidate).normalize();
        if (Files.isDirectory(resolved)) {
            resolved = resolved.resolve(TSCONFIG_JSON).normalize();
        } else if (!resolved.getFileName().toString().contains(".")) {
            resolved = Path.of(resolved + ".json").normalize();
        }
        return Files.exists(resolved) ? Optional.of(resolved) : Optional.empty();
    }

    // Intentionally does not validate against project file-set membership; callers perform "in-project" filtering
    // via existing module extension/index probing against the analyzer project file set.
}
