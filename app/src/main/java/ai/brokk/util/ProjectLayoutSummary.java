package ai.brokk.util;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Blocking;

/**
 * Utility to render a bounded, deterministic "Project Layout" section for AGENTS.md.
 */
public final class ProjectLayoutSummary {

    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final int DEFAULT_MAX_ENTRIES_PER_DIR = 20;
    private static final int DEFAULT_MAX_LINES = 120;
    private static final int DEFAULT_MAX_CHARS = 12_000;

    private final IProject project;

    public record LayoutResult(String markdown, String fingerprint) {}

    public ProjectLayoutSummary(IProject project) {
        this.project = project;
    }

    private static int getCap(String propName, int defVal) {
        String v = System.getProperty(propName);
        if (v == null || v.isBlank()) return defVal;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : defVal;
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    @Blocking
    public LayoutResult render() {
        Set<ProjectFile> allFiles = project.getAllFiles();
        int maxLines = getCap("brokk.layout.maxLines", DEFAULT_MAX_LINES);
        int maxChars = getCap("brokk.layout.maxChars", DEFAULT_MAX_CHARS);

        List<String> sections = new ArrayList<>();
        sections.add("## Project Layout");
        sections.add(renderPackageSummary(allFiles));
        sections.add(renderDirectoryTree(allFiles));

        String rawMarkdown = String.join("\n\n", sections);
        String finalMarkdown = applyBounding(rawMarkdown, maxLines, maxChars);

        return new LayoutResult(finalMarkdown, computeFingerprint(finalMarkdown));
    }

    private String applyBounding(String markdown, int maxLines, int maxChars) {
        List<String> lines = markdown.lines().collect(Collectors.toList());
        boolean truncated = false;

        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            truncated = true;
        }

        String result = String.join("\n", lines);
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars);
            truncated = true;
        }

        if (truncated) {
            result = result.stripTrailing() + "\n\n[Note: truncated to fit size limits]";
        }
        return result;
    }

    private String renderPackageSummary(Set<ProjectFile> files) {
        Map<String, Long> counts = new TreeMap<>();
        for (ProjectFile pf : files) {
            String relPath = pf.getRelPath().toString().replace('\\', '/');
            if (ai.brokk.analyzer.Languages.fromExtension(pf.extension()) == ai.brokk.analyzer.Languages.NONE) {
                continue;
            }

            int javaIdx = relPath.indexOf("/java/");
            if (javaIdx == -1) javaIdx = relPath.indexOf("java/"); // leading
            
            if (javaIdx != -1) {
                String sub = relPath.substring(javaIdx);
                sub = sub.startsWith("/") ? sub.substring(1) : sub; // "java/..."
                String[] segments = sub.split("/");
                if (segments.length > 1) {
                    // Treat segments after 'java' as package
                    Path packagePath = pf.getRelPath().getParent();
                    if (packagePath != null) {
                        counts.merge(packagePath.toString().replace('\\', '/'), 1L, Long::sum);
                    }
                    continue;
                }
            }

            // Fallback: top-level directory
            Path rel = pf.getRelPath();
            String module = rel.getNameCount() > 1 ? rel.getName(0).toString() : ".";
            counts.merge(module, 1L, Long::sum);
        }

        if (counts.isEmpty()) return "### Packages / Modules\nNo analyzable source files found.";

        List<String> lines = new ArrayList<>();
        lines.add("### Packages / Modules");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> lines.add("- `" + e.getKey() + "`: " + e.getValue() + " source files"));

        return String.join("\n", lines);
    }

    private String renderDirectoryTree(Set<ProjectFile> files) {
        int maxDepth = getCap("brokk.layout.maxDepth", DEFAULT_MAX_DEPTH);
        TreeNode root = new TreeNode("");
        for (ProjectFile file : files) {
            root.insert(file.getRelPath(), 0, maxDepth);
        }

        List<String> lines = new ArrayList<>();
        lines.add("### Directory Tree");
        lines.add("```text");
        root.render(lines, "", true);
        lines.add("```");
        
        return String.join("\n", lines);
    }

    private String computeFingerprint(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private static class TreeNode {
        private final String name;
        private final Map<String, TreeNode> children = new TreeMap<>();
        private boolean isFile = false;

        TreeNode(String name) {
            this.name = name;
        }

        void insert(Path path, int index, int maxDepth) {
            if (index >= path.getNameCount() || index >= maxDepth) return;
            String part = path.getName(index).toString();
            TreeNode child = children.computeIfAbsent(part, TreeNode::new);
            if (index == path.getNameCount() - 1) {
                child.isFile = true;
            } else {
                child.insert(path, index + 1, maxDepth);
            }
        }

        void render(List<String> lines, String prefix, boolean isLast) {
            if (!name.isEmpty()) {
                lines.add(prefix + (isLast ? "\\-- " : "|-- ") + name + (isFile ? "" : "/"));
            }
            
            String childPrefix = prefix + (name.isEmpty() ? "" : (isLast ? "    " : "|   "));
            List<String> keys = new ArrayList<>(children.keySet());
            int maxEntries = getCap("brokk.layout.maxEntriesPerDir", DEFAULT_MAX_ENTRIES_PER_DIR);
            int count = Math.min(keys.size(), maxEntries);
            
            for (int i = 0; i < count; i++) {
                children.get(keys.get(i)).render(lines, childPrefix, i == count - 1 && count == keys.size());
            }
            
            if (keys.size() > maxEntries) {
                lines.add(childPrefix + "\\-- ... (" + (keys.size() - maxEntries) + " more)");
            }
        }
    }
}
