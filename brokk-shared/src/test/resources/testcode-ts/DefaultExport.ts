// This file is for testing default exports.

export default class MyDefaultClass {
    constructor() {
        // constructor body
    }

    doSomething(): void {
        // method body
    }

    get value(): string {
        return "default value";
    }
}

export default function myDefaultFunction(param: string): string {
    return "Processed: " + param;
}

// Note: Only one default export is allowed per module.
// The parser might handle this, or it could be a TypeScript error.
// For testing, we are interested in how the analyzer handles the `export default` keyword.
// Let's assume the TS code is valid and has only one of these active at a time if needed for compilation.
// For skeleton generation, both structures are interesting.
// For a real scenario, a module would have only one default export.
// To test both, one could be in a different file or commented out.
// For analyzer testing, we assume the structure is parsable.

export const utilityRate: number = 0.15;

// Another named export in the same file
export class AnotherNamedClass {
    name: string = "Named";
}

export default type DefaultAlias = boolean;
