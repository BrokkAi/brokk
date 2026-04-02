// Test file for anonymous default exports
// TypeScript only allows ONE default export per file

// Anonymous default class - TypeScript does not support truly anonymous classes in default exports
// The class must have a name even if it's the default export
// This is a limitation of TypeScript's grammar
export default class AnonymousDefault {
    private value: number = 42;
    
    getValue(): number {
        return this.value;
    }
    
    setValue(v: number): void {
        this.value = v;
    }
}

// Named export for comparison
export class NamedClass {
    name: string = "named";
}

// Named function for comparison
export function namedFunction(): string {
    return "named function";
}

// Named const for comparison
export const namedConst: number = 100;
