package io.github.jbellis.brokk.util;

import java.util.Map;

/**
 * Utility class to detect the appropriate RSyntaxTextArea syntax style based on file extension.
 */
public final class SyntaxDetector {
    // Map file extensions (lowercase) to RSyntaxTextArea SyntaxConstants
    private static final Map<String, String> EXT_TO_SYNTAX = Map.ofEntries(
            // Core languages
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("jsonc", SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS),
            Map.entry("jshintrc", SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS),
            Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS),
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),

            // Shell and scripting
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bash", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("zsh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bat", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            Map.entry("cmd", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),

            // C family
            Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("hpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),

            // JVM languages
            Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("scala", SyntaxConstants.SYNTAX_STYLE_SCALA),
            Map.entry("groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("gradle", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("clj", SyntaxConstants.SYNTAX_STYLE_CLOJURE),

            // Web technologies
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("jsx", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("tsx", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("php", SyntaxConstants.SYNTAX_STYLE_PHP),
            Map.entry("jsp", SyntaxConstants.SYNTAX_STYLE_JSP),
            Map.entry("less", SyntaxConstants.SYNTAX_STYLE_LESS),
            Map.entry("hbs", SyntaxConstants.SYNTAX_STYLE_HANDLEBARS),
            Map.entry("mxml", SyntaxConstants.SYNTAX_STYLE_MXML),

            // Markup and documentation
            Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("tex", SyntaxConstants.SYNTAX_STYLE_LATEX),
            Map.entry("bbcode", SyntaxConstants.SYNTAX_STYLE_BBCODE),
            Map.entry("dtd", SyntaxConstants.SYNTAX_STYLE_DTD),

            // Systems and low-level
            Map.entry("rs", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("go", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("d", SyntaxConstants.SYNTAX_STYLE_D),
            Map.entry("asm", SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86),
            Map.entry("6502", SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_6502),

            // Scripting and dynamic languages
            Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("pl", SyntaxConstants.SYNTAX_STYLE_PERL),
            Map.entry("lua", SyntaxConstants.SYNTAX_STYLE_LUA),
            Map.entry("tcl", SyntaxConstants.SYNTAX_STYLE_TCL),
            Map.entry("dart", SyntaxConstants.SYNTAX_STYLE_DART),

            // Functional and academic languages
            Map.entry("lisp", SyntaxConstants.SYNTAX_STYLE_LISP),
            Map.entry("f", SyntaxConstants.SYNTAX_STYLE_FORTRAN),
            Map.entry("f90", SyntaxConstants.SYNTAX_STYLE_FORTRAN),
            Map.entry("pas", SyntaxConstants.SYNTAX_STYLE_DELPHI),
            Map.entry("vb", SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC),

            // Data and configuration
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("csv", SyntaxConstants.SYNTAX_STYLE_CSV),
            Map.entry("proto", SyntaxConstants.SYNTAX_STYLE_PROTO),

            // Build and deployment
            Map.entry("dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE),
            Map.entry("makefile", SyntaxConstants.SYNTAX_STYLE_MAKEFILE),
            Map.entry("nsi", SyntaxConstants.SYNTAX_STYLE_NSIS),

            // Specialized
            Map.entry("as", SyntaxConstants.SYNTAX_STYLE_ACTIONSCRIPT),
            Map.entry("sas", SyntaxConstants.SYNTAX_STYLE_SAS),
            Map.entry("hosts", SyntaxConstants.SYNTAX_STYLE_HOSTS),
            Map.entry("htaccess", SyntaxConstants.SYNTAX_STYLE_HTACCESS)
    );

    // Private constructor to prevent instantiation
    private SyntaxDetector() {}

    public static String fromExtension(String extension) {
        return EXT_TO_SYNTAX.getOrDefault(extension, SyntaxConstants.SYNTAX_STYLE_NONE);
    }
}
