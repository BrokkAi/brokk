package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.analyzer.Language;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Common pattern constants used across the application to avoid duplication.
 */
public final class PatternConstants {
    
    private PatternConstants() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Pattern for matching symbol names (class names, method names, etc.).
     * Matches patterns like: MyClass, MyClass.MyMethod, com.example.MyClass, MyClass.myMethod()
     */
    public static final Pattern SYMBOL_PATTERN =
        Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*(?:\\.[a-z][A-Za-z0-9_]+\\(\\))?");
    
    /**
     * Pattern for matching Java package declarations.
     */
    public static final Pattern PACKAGE_PATTERN = 
        Pattern.compile("^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*;");
    
    /**
     * Pattern for identifying test files. Looks for "test" or "tests" surrounded by separators or camelCase boundaries.
     */
    public static final Pattern TEST_FILE_PATTERN = Pattern.compile(
        "(?i).*(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z]))tests?(?:[/\\\\.]|\\b|_|(?=[A-Z][a-z])|$).*"
    );
    
    /**
     * Pattern for matching file names with supported extensions.
     * Uses the Language enum to get all supported extensions dynamically.
     */
    public static final Pattern FILENAME_PATTERN = createFilePattern();
    
    /**
     * Creates a pattern that matches filenames with any supported language extension.
     * This pattern is built dynamically from the Language enum to ensure consistency.
     */
    private static Pattern createFilePattern() {
        var allExtensions = Language.values();
        var extensionList = java.util.Arrays.stream(allExtensions)
            .filter(lang -> lang != Language.NONE) // Exclude NONE language
            .flatMap(lang -> lang.getExtensions().stream())
            .distinct()
            .sorted()
            .collect(Collectors.toList());
        
        String extensionPattern = String.join("|", extensionList);
        return Pattern.compile(".*\\.(" + extensionPattern + ")$");
    }
    
    /**
     * Checks if a filename matches any supported language extension.
     * This is equivalent to FILENAME_PATTERN.matcher(filename).matches() but more semantic.
     * 
     * @param filename the filename to check
     * @return true if the filename has a supported extension, false otherwise
     */
    public static boolean isRecognizedFile(String filename) {
        if (filename.isEmpty()) {
            return false;
        }
        
        String extension = com.google.common.io.Files.getFileExtension(filename);
        return Language.fromExtension(extension) != Language.NONE;
    }
    
    /**
     * Checks if a string matches the symbol pattern.
     * 
     * @param text the text to check
     * @return true if the text matches the symbol pattern, false otherwise
     */
    public static boolean isSymbolLike(String text) {
        return !text.isEmpty() && SYMBOL_PATTERN.matcher(text).matches();
    }
    
    /**
     * Checks if a file path represents a test file.
     * 
     * @param filePath the file path to check
     * @return true if the file path appears to be a test file, false otherwise
     */
    public static boolean isTestFile(String filePath) {
        return !filePath.isEmpty() && TEST_FILE_PATTERN.matcher(filePath).matches();
    }
}