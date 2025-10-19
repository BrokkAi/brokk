package io.github.jbellis.brokk.util;

import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Detects the best Python X.Y version for a project checkout.
 *
 * Priority:
 * 1) pyproject.toml: [project] requires-python (PEP 621) or [tool.poetry]
 * 2) setup.cfg: [options] python_requires
 * 3) setup.py: python_requires or REQUIRED_PYTHON
 * 4) tox.ini: envlist entries
 * 5) .github/workflows/*.yml: python-version entries
 * 6) Heuristic: if any source imports distutils, cap at 3.10
 *
 * Returns a version like "3.8", or "3.12" as a fallback.
 */
@NullMarked
public class EnvironmentPython {
    private static final Logger logger = LogManager.getLogger(EnvironmentPython.class);

    private static final Pattern PY_VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final Pattern DISTUTILS_PATTERN =
            Pattern.compile("^\\s*(from\\s+distutils|import\\s+distutils)\\b", Pattern.MULTILINE);

    /** Represents a Python major.minor version. */
    private record PyVersion(int major, int minor) {
        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

    /** Represents the lower and upper bounds of a version constraint. */
    private record VersionBounds(@Nullable PyVersion lower, @Nullable PyVersion upper) {}

    /** Represents a version spec with its source file. */
    private record SpecWithSource(String spec, @Nullable Path sourceFile) {}

    /** Represents a version with its source file. */
    private record VersionWithSource(String version, Path sourceFile) {}

    private final Path projectRoot;

    public EnvironmentPython(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Detect and return the best Python version for this project.
     *
     * @return A version string like "3.8" or "3.10".
     */
    public String getPythonVersion() {
        var specWithSource = getRequiresPythonSpecWithSource();
        var explicitVersionsWithSource = getExplicitVersionsWithSource();
        boolean cap310 = repoImportsDistutils();

        // Candidates (newest first)
        List<String> candidates = List.of("3.13", "3.12", "3.11", "3.10", "3.9", "3.8", "3.7", "3.6");
        if (cap310) {
            candidates = candidates.stream()
                    .filter(v -> !v.startsWith("3.1") || v.equals("3.10"))
                    .toList();
        }

        // Try explicit versions from CI/tox first
        if (!explicitVersionsWithSource.isEmpty()) {
            for (var versionWithSource : explicitVersionsWithSource.stream()
                    .sorted((a, b) -> compareVersions(b.version(), a.version()))
                    .toList()) {
                String v = versionWithSource.version();
                if (cap310 && compareVersions(v, "3.10") > 0) continue;
                if (pythonExecutableExists(v)) {
                    logger.debug(
                            "Selected Python version {} from CI/tox ({})",
                            v,
                            versionWithSource.sourceFile().toAbsolutePath());
                    return v;
                }
            }
        }

        // Parse requires-python spec and find best match
        var bounds = parseSpec(specWithSource.spec());
        var lower = bounds.lower();
        var upper = bounds.upper();

        for (String candidate : candidates) {
            if (satisfiesSpec(candidate, lower, upper)) {
                if (pythonExecutableExists(candidate)) {
                    String source = specWithSource.sourceFile() != null
                            ? specWithSource.sourceFile().toAbsolutePath().toString()
                            : "default spec";
                    logger.debug("Selected Python version {} from spec ({})", candidate, source);
                    return candidate;
                }
            }
        }

        // Last resort: use lower bound if available
        String fallback = lower != null ? lower.toString() : "3.6";
        String source = specWithSource.sourceFile() != null
                ? specWithSource.sourceFile().toAbsolutePath().toString()
                : "default lower bound";
        logger.debug("Selected Python version {} from lower bound ({})", fallback, source);
        return fallback;
    }

    /** Get requires-python spec from pyproject.toml, setup.cfg, or setup.py with source file. */
    private SpecWithSource getRequiresPythonSpecWithSource() {
        String spec = getRequiresPythonFromPyproject();
        if (spec != null && !spec.isBlank()) {
            return new SpecWithSource(spec, projectRoot.resolve("pyproject.toml"));
        }

        spec = getPythonRequiresFromSetupCfg();
        if (spec != null && !spec.isBlank()) {
            return new SpecWithSource(spec, projectRoot.resolve("setup.cfg"));
        }

        spec = getPythonReqFromSetupPy();
        if (spec != null && !spec.isBlank()) {
            return new SpecWithSource(spec, projectRoot.resolve("setup.py"));
        }

        return new SpecWithSource("", null);
    }

    /** Extract requires-python from [project] or [tool.poetry] in pyproject.toml. */
    private @Nullable String getRequiresPythonFromPyproject() {
        Path p = projectRoot.resolve("pyproject.toml");
        String content = readFile(p);
        if (content == null) return null;

        // [project] requires-python
        Matcher m = Pattern.compile("^\\s*requires-python\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.MULTILINE)
                .matcher(content);
        if (m.find()) return m.group(1);

        // [tool.poetry] python
        m = Pattern.compile(
                        "^\\s*\\[tool\\.poetry\\]\\s*.*?^\\s*python\\s*=\\s*[\"']([^\"']+)[\"']",
                        Pattern.MULTILINE | Pattern.DOTALL)
                .matcher(content);
        if (m.find()) return m.group(1);

        return null;
    }

    /** Extract python_requires from setup.cfg. */
    private @Nullable String getPythonRequiresFromSetupCfg() {
        Path p = projectRoot.resolve("setup.cfg");
        String content = readFile(p);
        if (content == null) return null;

        Matcher m = Pattern.compile("^\\s*python_requires\\s*=\\s*([^\\n#;]+)", Pattern.MULTILINE)
                .matcher(content);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^[\"']|[\"']$", "");
        }
        return null;
    }

    /** Extract python_requires or REQUIRED_PYTHON from setup.py. */
    private @Nullable String getPythonReqFromSetupPy() {
        Path p = projectRoot.resolve("setup.py");
        String content = readFile(p);
        if (content == null) return null;

        // python_requires=">=3.8,<4"
        Matcher m =
                Pattern.compile("python_requires\\s*=\\s*[\"']([^\"']+)[\"']").matcher(content);
        if (m.find()) return m.group(1);

        // REQUIRED_PYTHON = (3, 6)
        m = Pattern.compile("REQUIRED_PYTHON\\s*=\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
                .matcher(content);
        if (m.find()) {
            return ">=" + m.group(1) + "." + m.group(2);
        }
        return null;
    }

    /** Get explicit versions from tox.ini and GitHub workflows with source files. */
    private List<VersionWithSource> getExplicitVersionsWithSource() {
        List<VersionWithSource> versions = new ArrayList<>();
        versions.addAll(getVersionsFromToxIniWithSource());
        versions.addAll(getVersionsFromGhaWithSource());
        return versions.stream()
                .sorted((a, b) -> compareVersions(a.version(), b.version()))
                .collect(Collectors.toList());
    }

    /** Extract Python versions from tox.ini envlist with source file. */
    private List<VersionWithSource> getVersionsFromToxIniWithSource() {
        Path p = projectRoot.resolve("tox.ini");
        String content = readFile(p);
        if (content == null) return List.of();

        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("\\bpy3?(\\d{1,2})\\b").matcher(content);
        while (m.find()) {
            String digits = m.group(1);
            if (digits.length() == 1) continue; // skip py3
            int mm = Integer.parseInt(digits);
            if (digits.length() == 2 && mm >= 6 && mm <= 99) {
                out.add("3." + mm);
            }
        }
        return out.stream()
                .sorted(this::compareVersions)
                .map(v -> new VersionWithSource(v, p))
                .collect(Collectors.toList());
    }

    /** Extract Python versions from GitHub workflows with source files. */
    private List<VersionWithSource> getVersionsFromGhaWithSource() {
        List<VersionWithSource> out = new ArrayList<>();
        Path workflowsDir = projectRoot.resolve(".github").resolve("workflows");
        if (!Files.isDirectory(workflowsDir)) return List.of();

        try (var fileStream = Files.list(workflowsDir)) {
            fileStream
                    .filter(p -> p.getFileName().toString().endsWith(".yml")
                            || p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> {
                        String content = readFile(p);
                        if (content != null) {
                            Set<String> versions = new HashSet<>();
                            // python-version: ["3.10", "3.12"]
                            Matcher m = Pattern.compile("python-version\\s*:\\s*\\[([^\\]]+)\\]")
                                    .matcher(content);
                            if (m.find()) {
                                String group = m.group(1);
                                Matcher vm = Pattern.compile("[\"'](\\d+\\.\\d+)[\"']")
                                        .matcher(group);
                                while (vm.find()) {
                                    versions.add(vm.group(1));
                                }
                            }
                            // python-version: "3.8"
                            m = Pattern.compile("python-version\\s*:\\s*[\"']?(\\d+\\.\\d+)[\"']?")
                                    .matcher(content);
                            while (m.find()) {
                                versions.add(m.group(1));
                            }
                            for (String v : versions) {
                                out.add(new VersionWithSource(v, p));
                            }
                        }
                    });
        } catch (IOException e) {
            logger.debug("Error reading GitHub workflows directory", e);
        }

        return out.stream()
                .sorted((a, b) -> compareVersions(a.version(), b.version()))
                .collect(Collectors.toList());
    }

    /** Check if any Python source file imports distutils. */
    private boolean repoImportsDistutils() {
        try (var fileStream = Files.walk(projectRoot)) {
            return fileStream
                    .filter(p -> p.getFileName().toString().endsWith(".py"))
                    .filter(p -> {
                        String sp = p.toString();
                        return !sp.contains(".venv") && !sp.contains("/venv") && !sp.contains(".git");
                    })
                    .anyMatch(p -> {
                        String content = readFile(p);
                        return content != null
                                && DISTUTILS_PATTERN.matcher(content).find();
                    });
        } catch (IOException e) {
            logger.debug("Error walking project directory for distutils check", e);
            return false;
        }
    }

    /** Parse a PEP 440 version spec like ">=3.8,<4" or ">=3.6". */
    private VersionBounds parseSpec(String spec) {
        @Nullable PyVersion lower = null;
        @Nullable PyVersion upper = null;

        if (spec.isBlank()) {
            return new VersionBounds(null, null);
        }

        for (String part : Splitter.on(',').split(spec)) {
            part = part.trim();
            if (part.startsWith(">=")) {
                String v = part.substring(2).trim();
                var m = PY_VERSION_PATTERN.matcher(v);
                if (m.find()) {
                    lower = new PyVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                }
            } else if (part.startsWith(">")) {
                String v = part.substring(1).trim();
                var m = PY_VERSION_PATTERN.matcher(v);
                if (m.find()) {
                    int maj = Integer.parseInt(m.group(1));
                    int min = Integer.parseInt(m.group(2));
                    lower = new PyVersion(maj, min + 1);
                }
            } else if (part.startsWith("<")) {
                String v = part.substring(1).trim();
                var m = PY_VERSION_PATTERN.matcher(v);
                if (m.find()) {
                    upper = new PyVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                }
            } else if (part.startsWith("==")) {
                String v = part.substring(2).trim();
                var m = PY_VERSION_PATTERN.matcher(v);
                if (m.find()) {
                    int maj = Integer.parseInt(m.group(1));
                    int min = Integer.parseInt(m.group(2));
                    lower = new PyVersion(maj, min);
                    upper = new PyVersion(maj, min + 1);
                }
            }
        }

        return new VersionBounds(lower, upper);
    }

    /** Check if a version satisfies the given bounds. */
    private boolean satisfiesSpec(String version, @Nullable PyVersion lower, @Nullable PyVersion upper) {
        var m = PY_VERSION_PATTERN.matcher(version);
        if (!m.find()) return false;

        int maj = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));

        if (lower != null && maj < lower.major()) return false;
        if (lower != null && maj == lower.major() && min < lower.minor()) return false;
        if (upper != null && maj > upper.major()) return false;
        if (upper != null && maj == upper.major() && min >= upper.minor()) return false;

        return true;
    }

    /** Check if a Python executable of the given version exists. */
    private boolean pythonExecutableExists(String version) {
        try {
            var process = new ProcessBuilder("python" + version, "--version")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Read file contents, returning null on error. */
    private @Nullable String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Compare two version strings like "3.8" and "3.10". Returns < 0 if a < b, 0 if equal, > 0 if a > b. */
    private int compareVersions(String a, String b) {
        var aParts = Splitter.on('.').splitToList(a);
        var bParts = Splitter.on('.').splitToList(b);
        for (int i = 0; i < Math.min(aParts.size(), bParts.size()); i++) {
            int aVal = Integer.parseInt(aParts.get(i));
            int bVal = Integer.parseInt(bParts.get(i));
            if (aVal != bVal) return Integer.compare(aVal, bVal);
        }
        return Integer.compare(aParts.size(), bParts.size());
    }
}
