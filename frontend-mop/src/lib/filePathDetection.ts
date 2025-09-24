export interface ParsedFilePath {
    path: string;              // path without line numbers
    lineNumber?: number;       // single line number
    lineRange?: [number, number]; // line range
    originalText: string;      // original input
}

export interface FilePathDetectionResult {
    isValidPath: boolean;
    cleanPath: string;
    originalText: string;
    lineNumber?: number;
    lineRange?: [number, number];
}

/**
 * Parse line numbers from file paths like "file.js:42" or "file.py:15-20"
 */
export function parseFilePathWithLines(text: string): ParsedFilePath {
    const trimmed = text.trim();

    // Pattern to match line numbers at the end: :42 or :15-20
    const lineNumberMatch = trimmed.match(/^(.+?):(\d+)(?:-(\d+))?$/);

    if (lineNumberMatch) {
        const [, path, startLine, endLine] = lineNumberMatch;
        const lineNumber = parseInt(startLine, 10);

        if (endLine) {
            const endLineNum = parseInt(endLine, 10);
            return {
                path: path.trim(),
                lineRange: [lineNumber, endLineNum],
                originalText: text
            };
        } else {
            return {
                path: path.trim(),
                lineNumber,
                originalText: text
            };
        }
    }

    return {
        path: trimmed,
        originalText: text
    };
}

/**
 * Remove surrounding quotes from path
 */
export function removeQuotes(path: string): string {
    const trimmed = path.trim();
    if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
        (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
        return trimmed.slice(1, -1);
    }
    return trimmed;
}

/**
 * Get file extension from path (without the dot)
 */
export function getFileExtension(path: string): string {
    const lastDot = path.lastIndexOf('.');
    const lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));

    // Check if dot is after the last slash (to avoid directories like ".git")
    if (lastDot > lastSlash && lastDot !== -1) {
        return path.substring(lastDot + 1).toLowerCase();
    }
    return '';
}


/**
 * Validate basic file path format
 * Returns true if the text looks like a file path
 */
export function validateFilePathFormat(path: string): boolean {
    if (!path || path.trim().length === 0) {
        return false;
    }

    const cleanPath = path.trim();

    // Must contain path separators OR have an extension
    const hasPathSeparator = cleanPath.includes('/') || cleanPath.includes('\\');
    const hasExtension = getFileExtension(cleanPath) !== '';

    // Reject if it's just a bare name without extension
    if (!hasPathSeparator && !hasExtension) {
        return false;
    }

    // Reject if it contains invalid characters for file paths
    const invalidChars = /[<>"|*?]/;
    if (invalidChars.test(cleanPath)) {
        return false;
    }

    // Reject if it looks like a URL
    if (cleanPath.includes('://')) {
        return false;
    }

    return true;
}

/**
 * Check if text looks like a file path (has extension + path structure)
 */
export function looksLikeFilePath(text: string): boolean {
    const parsed = parseFilePathWithLines(text);
    const cleanPath = removeQuotes(parsed.path);
    const extension = getFileExtension(cleanPath);

    return validateFilePathFormat(cleanPath) && extension !== '';
}

/**
 * Main file path detection function
 */
export function tryFilePathDetection(text: string): FilePathDetectionResult {
    // Parse potential line numbers first
    const { path, lineNumber, lineRange } = parseFilePathWithLines(text);

    // Remove quotes if present
    const cleanPath = removeQuotes(path);

    // Basic path validation (contains path separators or has extension)
    const isValidPath = validateFilePathFormat(cleanPath);

    return {
        isValidPath,
        cleanPath,
        originalText: text,
        lineNumber,
        lineRange
    };
}