package io.github.jbellis.brokk.util;

/**
 * Syntax constants for various file types, similar to RSyntaxTextArea's SyntaxConstants
 * but without the UI dependency. These constants can be used for syntax highlighting
 * and file type detection.
 */
public final class SyntaxConstants {

    // No syntax highlighting
    public static final String SYNTAX_STYLE_NONE = "text/plain";

    // Core languages
    public static final String SYNTAX_STYLE_JAVA = "text/java";
    public static final String SYNTAX_STYLE_PYTHON = "text/python";
    public static final String SYNTAX_STYLE_JAVASCRIPT = "text/javascript";
    public static final String SYNTAX_STYLE_TYPESCRIPT = "text/typescript";
    public static final String SYNTAX_STYLE_C = "text/c";
    public static final String SYNTAX_STYLE_CPLUSPLUS = "text/cpp";
    public static final String SYNTAX_STYLE_CSHARP = "text/csharp";

    // JVM languages
    public static final String SYNTAX_STYLE_KOTLIN = "text/kotlin";
    public static final String SYNTAX_STYLE_SCALA = "text/scala";
    public static final String SYNTAX_STYLE_GROOVY = "text/groovy";
    public static final String SYNTAX_STYLE_CLOJURE = "text/clojure";

    // Systems languages
    public static final String SYNTAX_STYLE_RUST = "text/rust";
    public static final String SYNTAX_STYLE_GO = "text/go";
    public static final String SYNTAX_STYLE_D = "text/d";

    // Scripting languages
    public static final String SYNTAX_STYLE_RUBY = "text/ruby";
    public static final String SYNTAX_STYLE_PERL = "text/perl";
    public static final String SYNTAX_STYLE_LUA = "text/lua";
    public static final String SYNTAX_STYLE_TCL = "text/tcl";
    public static final String SYNTAX_STYLE_DART = "text/dart";
    public static final String SYNTAX_STYLE_PHP = "text/php";

    // Functional and academic languages
    public static final String SYNTAX_STYLE_LISP = "text/lisp";
    public static final String SYNTAX_STYLE_FORTRAN = "text/fortran";
    public static final String SYNTAX_STYLE_DELPHI = "text/delphi";
    public static final String SYNTAX_STYLE_VISUAL_BASIC = "text/vb";

    // Web technologies
    public static final String SYNTAX_STYLE_HTML = "text/html";
    public static final String SYNTAX_STYLE_CSS = "text/css";
    public static final String SYNTAX_STYLE_XML = "text/xml";
    public static final String SYNTAX_STYLE_JSP = "text/jsp";
    public static final String SYNTAX_STYLE_LESS = "text/less";
    public static final String SYNTAX_STYLE_HANDLEBARS = "text/handlebars";
    public static final String SYNTAX_STYLE_MXML = "text/mxml";

    // Data formats
    public static final String SYNTAX_STYLE_JSON = "application/json";
    public static final String SYNTAX_STYLE_JSON_WITH_COMMENTS = "application/json-with-comments";
    public static final String SYNTAX_STYLE_YAML = "text/yaml";
    public static final String SYNTAX_STYLE_CSV = "text/csv";
    public static final String SYNTAX_STYLE_PROPERTIES_FILE = "text/properties";
    public static final String SYNTAX_STYLE_INI = "text/ini";
    public static final String SYNTAX_STYLE_PROTO = "text/proto";

    // Shell and batch
    public static final String SYNTAX_STYLE_UNIX_SHELL = "text/shell";
    public static final String SYNTAX_STYLE_WINDOWS_BATCH = "text/batch";

    // Assembly
    public static final String SYNTAX_STYLE_ASSEMBLER_X86 = "text/asm-x86";
    public static final String SYNTAX_STYLE_ASSEMBLER_6502 = "text/asm-6502";

    // Markup and documentation
    public static final String SYNTAX_STYLE_MARKDOWN = "text/markdown";
    public static final String SYNTAX_STYLE_LATEX = "text/latex";
    public static final String SYNTAX_STYLE_BBCODE = "text/bbcode";
    public static final String SYNTAX_STYLE_DTD = "text/dtd";

    // Database
    public static final String SYNTAX_STYLE_SQL = "text/sql";

    // Build and deployment
    public static final String SYNTAX_STYLE_DOCKERFILE = "text/dockerfile";
    public static final String SYNTAX_STYLE_MAKEFILE = "text/makefile";
    public static final String SYNTAX_STYLE_NSIS = "text/nsis";

    // Specialized
    public static final String SYNTAX_STYLE_ACTIONSCRIPT = "text/actionscript";
    public static final String SYNTAX_STYLE_SAS = "text/sas";
    public static final String SYNTAX_STYLE_HOSTS = "text/hosts";
    public static final String SYNTAX_STYLE_HTACCESS = "text/htaccess";

    // Private constructor to prevent instantiation
    private SyntaxConstants() {}
}
