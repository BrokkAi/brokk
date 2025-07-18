package io.github.jbellis.brokk.util;

import java.util.Map;

/**
 * Utility class to detect the appropriate RSyntaxTextArea syntax style based on file extension.
 */
public final class SyntaxDetector {
    // Map file extensions (lowercase) to syntax style strings
    private static final Map<String, String> EXT_TO_SYNTAX = Map.ofEntries(
            // Core languages
            Map.entry("java", "text/java"),
            Map.entry("py", "text/python"),
            Map.entry("json", "application/json"),
            Map.entry("jsonc", "application/json-with-comments"),
            Map.entry("jshintrc", "application/json-with-comments"),
            Map.entry("html", "text/html"),
            Map.entry("xml", "text/xml"),
            Map.entry("css", "text/css"),
            Map.entry("sql", "text/sql"),
            Map.entry("yaml", "text/yaml"),
            Map.entry("yml", "text/yaml"),

            // Shell and scripting
            Map.entry("sh", "text/shell"),
            Map.entry("bash", "text/shell"),
            Map.entry("zsh", "text/shell"),
            Map.entry("bat", "text/batch"),
            Map.entry("cmd", "text/batch"),

            // C family
            Map.entry("c", "text/c"),
            Map.entry("h", "text/c"),
            Map.entry("cpp", "text/cpp"),
            Map.entry("hpp", "text/cpp"),
            Map.entry("cs", "text/csharp"),

            // JVM languages
            Map.entry("kt", "text/kotlin"),
            Map.entry("scala", "text/scala"),
            Map.entry("groovy", "text/groovy"),
            Map.entry("gradle", "text/groovy"),
            Map.entry("clj", "text/clojure"),

            // Web technologies
            Map.entry("js", "text/javascript"),
            Map.entry("jsx", "text/javascript"),
            Map.entry("ts", "text/typescript"),
            Map.entry("tsx", "text/typescript"),
            Map.entry("php", "text/php"),
            Map.entry("jsp", "text/jsp"),
            Map.entry("less", "text/less"),
            Map.entry("hbs", "text/handlebars"),
            Map.entry("mxml", "text/mxml"),

            // Markup and documentation
            Map.entry("md", "text/markdown"),
            Map.entry("tex", "text/latex"),
            Map.entry("bbcode", "text/bbcode"),
            Map.entry("dtd", "text/dtd"),

            // Systems and low-level
            Map.entry("rs", "text/rust"),
            Map.entry("go", "text/go"),
            Map.entry("d", "text/d"),
            Map.entry("asm", "text/asm-x86"),
            Map.entry("6502", "text/asm-6502"),

            // Scripting and dynamic languages
            Map.entry("rb", "text/ruby"),
            Map.entry("pl", "text/perl"),
            Map.entry("lua", "text/lua"),
            Map.entry("tcl", "text/tcl"),
            Map.entry("dart", "text/dart"),

            // Functional and academic languages
            Map.entry("lisp", "text/lisp"),
            Map.entry("f", "text/fortran"),
            Map.entry("f90", "text/fortran"),
            Map.entry("pas", "text/delphi"),
            Map.entry("vb", "text/vb"),

            // Data and configuration
            Map.entry("properties", "text/properties"),
            Map.entry("ini", "text/ini"),
            Map.entry("csv", "text/csv"),
            Map.entry("proto", "text/proto"),

            // Build and deployment
            Map.entry("dockerfile", "text/dockerfile"),
            Map.entry("makefile", "text/makefile"),
            Map.entry("nsi", "text/nsis"),

            // Specialized
            Map.entry("as", "text/actionscript"),
            Map.entry("sas", "text/sas"),
            Map.entry("hosts", "text/hosts"),
            Map.entry("htaccess", "text/htaccess")
    );

    // Private constructor to prevent instantiation
    private SyntaxDetector() {}

    public static String fromExtension(String extension) {
        return EXT_TO_SYNTAX.getOrDefault(extension, "text/plain");
    }
}
