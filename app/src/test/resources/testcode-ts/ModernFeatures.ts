// Modern TypeScript Features Test File

// ===== Satisfies Operator (TypeScript 4.9+) =====

type Config = {
    x: number;
    y?: number;
};

// Basic satisfies usage
export const config = { x: 1, y: 2 } satisfies Config;

// Satisfies with type narrowing
export const strictConfig = { x: 10 } satisfies Config;

// Satisfies with complex types
type ColorConfig = {
    color: "red" | "green" | "blue";
    opacity: number;
};

export const themeConfig = {
    color: "red" as const,
    opacity: 0.8
} satisfies ColorConfig;

// Satisfies in function return
export function getConfig() {
    return { x: 5, y: 10 } satisfies Config;
}

// ===== Type Predicate Functions =====

// Basic type predicate
export function isString(x: any): x is string {
    return typeof x === "string";
}

// Type predicate with complex type
export function isNumber(value: unknown): value is number {
    return typeof value === "number" && !isNaN(value);
}

// Type predicate with generic
export function isArray<T>(value: any): value is Array<T> {
    return Array.isArray(value);
}

// Type predicate for object type
interface User {
    name: string;
    age: number;
}

export function isUser(obj: any): obj is User {
    return obj && typeof obj.name === "string" && typeof obj.age === "number";
}

// Type predicate with null check
export function isNonNull<T>(value: T | null | undefined): value is T {
    return value != null;
}

// Class method with type predicate
export class TypeChecker {
    isStringValue(x: any): x is string {
        return typeof x === "string";
    }
    
    static isNumberValue(x: any): x is number {
        return typeof x === "number";
    }
}

// ===== Assertion Signatures =====

// Basic assertion signature
export function assert(condition: any): asserts condition {
    if (!condition) {
        throw new Error("Assertion failed");
    }
}

// Assertion signature with message
export function assertWithMessage(condition: any, message: string): asserts condition {
    if (!condition) {
        throw new Error(message);
    }
}

// Assertion signature with type predicate
export function assertIsString(value: any): asserts value is string {
    if (typeof value !== "string") {
        throw new Error("Value is not a string");
    }
}

// Assertion signature with generic
export function assertIsArray<T>(value: any): asserts value is Array<T> {
    if (!Array.isArray(value)) {
        throw new Error("Value is not an array");
    }
}

// Assertion signature for non-null
export function assertNonNull<T>(value: T | null | undefined): asserts value is T {
    if (value == null) {
        throw new Error("Value is null or undefined");
    }
}

// Class method with assertion signature
export class Validator {
    assertPositive(value: number): asserts value is number {
        if (value <= 0) {
            throw new Error("Value must be positive");
        }
    }
    
    static assertNotEmpty(str: string): asserts str is string {
        if (!str || str.length === 0) {
            throw new Error("String is empty");
        }
    }
}

// ===== This Parameters =====

// Basic this parameter
export function greet(this: User, greeting: string): string {
    return `${greeting}, ${this.name}!`;
}

// This parameter with return type
export function getUserAge(this: User): number {
    return this.age;
}

// This parameter with generics
export function getValue<T>(this: { value: T }): T {
    return this.value;
}

// This parameter with void return
export function logUser(this: User, prefix: string): void {
    console.log(`${prefix}: ${this.name}, age ${this.age}`);
}

// Multiple parameters with this
export function updateUser(this: User, name: string, age: number): void {
    this.name = name;
    this.age = age;
}

// Class with methods using this parameter explicitly
export class Calculator {
    value: number = 0;
    
    // Method with explicit this parameter
    add(this: Calculator, amount: number): Calculator {
        this.value += amount;
        return this;
    }
    
    // Static method with this parameter
    static create(this: typeof Calculator): Calculator {
        return new Calculator();
    }
}

// ===== Const Type Parameters (TypeScript 5.0+) =====

// Basic const type parameter
export function identity<const T>(value: T): T {
    return value;
}

// Const type parameter with array
export function tuple<const T extends readonly any[]>(...args: T): T {
    return args;
}

// Const type parameter preserving literal types
export function asConst<const T>(value: T): T {
    return value;
}

// Const type parameter with object
export function freeze<const T extends Record<string, any>>(obj: T): T {
    return Object.freeze(obj);
}

// Const type parameter with multiple parameters
export function pair<const T, const U>(first: T, second: U): [T, U] {
    return [first, second];
}

// Class with const type parameter
export class Container<const T> {
    constructor(public value: T) {}
    
    getValue(): T {
        return this.value;
    }
}

// Function with const type parameter and constraint
export function createRecord<const K extends string, V>(key: K, value: V): Record<K, V> {
    return { [key]: value } as Record<K, V>;
}

// ===== Combined Modern Features =====

// Type predicate with const type parameter
export function isLiteralArray<const T extends readonly any[]>(
    value: unknown
): value is T {
    return Array.isArray(value);
}

// Assertion with const type parameter
export function assertLiteral<const T>(
    value: unknown,
    expected: T
): asserts value is T {
    if (value !== expected) {
        throw new Error(`Expected ${expected}, got ${value}`);
    }
}

// This parameter with const type parameter
export function bindMethod<const T extends (...args: any[]) => any>(
    this: any,
    fn: T
): T {
    return fn.bind(this) as T;
}

// Satisfies with type predicate result
export const validator = {
    check: (x: any): x is string => typeof x === "string"
} satisfies { check: (x: any) => boolean };

// Complex combination: const type parameter + type predicate + this
export class TypeSafeBuilder<const T extends Record<string, any>> {
    private data: Partial<T> = {};
    
    set<K extends keyof T>(this: TypeSafeBuilder<T>, key: K, value: T[K]): this {
        this.data[key] = value;
        return this;
    }
    
    isComplete(this: TypeSafeBuilder<T>): this is TypeSafeBuilder<T> & { data: T } {
        return Object.keys(this.data).length > 0;
    }
    
    build(this: TypeSafeBuilder<T>): T {
        return this.data as T;
    }
}
