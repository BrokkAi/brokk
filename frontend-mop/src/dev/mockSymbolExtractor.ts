export interface MockSymbolResult {
    fqn: string | null;
    highlightRanges: Array<[number, number]>;
    isPartialMatch: boolean;
    originalText: string;
}

/**
 * Mock symbol extractor - will be replaced by backend responses
 * This simulates what the backend will eventually provide
 */
export function mockExtractSymbol(symbolText: string): MockSymbolResult | null {
    // Java method reference pattern: ClassName.method or Package.Class.method
    const javaMethodMatch = /^([A-Z][a-zA-Z0-9_]*(?:\.[A-Z][a-zA-Z0-9_]*)*?)\.([a-z_][a-zA-Z0-9_]*)$/.exec(symbolText);

    if (javaMethodMatch) {
        const className = javaMethodMatch[1];
        const methodName = javaMethodMatch[2];

        return {
            fqn: `mock.${className}`, // Mock FQN
            highlightRanges: [[0, className.length]], // Highlight only the class part
            isPartialMatch: true,
            originalText: symbolText
        };
    }

    // Simple class name: ClassName
    if (/^[A-Z][a-zA-Z0-9_]*$/.test(symbolText)) {
        return {
            fqn: `mock.${symbolText}`,
            highlightRanges: [[0, symbolText.length]], // Highlight entire text
            isPartialMatch: false,
            originalText: symbolText
        };
    }

    return null;
}