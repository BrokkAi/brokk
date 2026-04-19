// Test file for TypeScript function overload signature extraction

// Test 1: Basic function overloads with different parameter types
export function add(a: number, b: number): number;
export function add(a: string, b: string): string;
export function add(a: any, b: any): any {
    return a + b;
}

// Test 2: Optional parameters
export function query(id: number): UserRecord;
export function query(id: number, options?: QueryOptions): UserRecord;
export function query(id: any, options?: any): any {
    return null;
}

// Test 3: Rest parameters
export function combine(first: string): string;
export function combine(first: string, ...rest: string[]): string;
export function combine(first: any, ...rest: any[]): any {
    return first + rest.join('');
}

// Test 4: Complex generic types
export function map<T, U>(arr: T[], fn: (item: T) => U): U[];
export function map<T>(arr: T[], fn: (item: T, idx: number) => T): T[];
export function map(arr: any[], fn: any): any[] {
    return arr.map(fn);
}

// Test 5: Function types as parameters
export function process(fn: (x: number) => string): void;
export function process(fn: (x: string) => number): void;
export function process(fn: any): void {
    // implementation
}

// Test 6: Union and intersection types
export function convert(value: string | number): string;
export function convert(value: boolean): number;
export function convert(value: any): any {
    return value;
}

// Test 7: Class method overloads
export class Calculator {
    multiply(a: number, b: number): number;
    multiply(a: string, b: number): string;
    multiply(a: any, b: any): any {
        return a * b;
    }
}

// Test 8: Constructor overloads
export class Position {
    constructor(x: number, y: number);
    constructor(coordinates: [number, number]);
    constructor(...args: any[]) {
        // implementation
    }
}

// Helper types for tests
interface UserRecord {
    id: number;
    name: string;
}

interface QueryOptions {
    includeDeleted?: boolean;
}
