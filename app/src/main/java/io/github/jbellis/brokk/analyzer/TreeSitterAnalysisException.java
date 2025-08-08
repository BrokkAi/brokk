package io.github.jbellis.brokk.analyzer;

/**
 * Exception thrown when TreeSitter parsing or analysis operations fail.
 * Provides more specific error handling than generic Exception.
 */
public class TreeSitterAnalysisException extends Exception {
    private final ProjectFile file;
    private final String operation;

    public TreeSitterAnalysisException(String message, ProjectFile file, String operation) {
        super(message);
        this.file = file;
        this.operation = operation;
    }

    public TreeSitterAnalysisException(String message, Throwable cause, ProjectFile file, String operation) {
        super(message, cause);
        this.file = file;
        this.operation = operation;
    }

    public ProjectFile getFile() {
        return file;
    }

    public String getOperation() {
        return operation;
    }

    @Override
    public String getMessage() {
        return String.format("TreeSitter analysis failed during %s for file %s: %s",
                           operation, file, super.getMessage());
    }
}