// Advanced Type Constructs Test File

// ===== Tuple Types =====

// Basic tuple type
export type Coord = [number, number];

// Tuple with optional elements
export type Point3D = [number, number, number?];

// Tuple with rest elements
export type RestTuple = [string, ...number[]];

// Named tuple elements (TypeScript 4.0+)
export type Range = [start: number, end: number];

// Readonly tuple
export type ReadonlyCoord = readonly [number, number];

// ===== Mapped Types =====

// Basic mapped type
export type Readonly<T> = {
    readonly [P in keyof T]: T[P];
};

// Mapped type with modifier
export type Partial<T> = {
    [P in keyof T]?: T[P];
};

// Mapped type with key remapping
export type Getters<T> = {
    [P in keyof T as `get${Capitalize<string & P>}`]: () => T[P];
};

// Mapped type with filtering
export type OnlyStrings<T> = {
    [P in keyof T as T[P] extends string ? P : never]: T[P];
};

// ===== Conditional Types =====

// Basic conditional type
export type Extract<T, U> = T extends U ? T : never;

// Conditional type with infer
export type ReturnType<T> = T extends (...args: any[]) => infer R ? R : never;

// Nested conditional type
export type Flatten<T> = T extends Array<infer U> ? U : T;

// Conditional type with multiple conditions
export type TypeName<T> = T extends string
    ? "string"
    : T extends number
    ? "number"
    : T extends boolean
    ? "boolean"
    : "object";

// Distributive conditional type
export type ToArray<T> = T extends any ? T[] : never;

// ===== Intersection Types =====

// Basic intersection
type TypeA = { a: string };
type TypeB = { b: number };
type TypeC = { c: boolean };
export type Combined = TypeA & TypeB & TypeC;

// Intersection with primitives (creates never type)
export type StringAndNumber = string & number;

// Complex intersection
export type Mergeable<T, U> = T & U & { merged: true };

// Intersection with function types
type Logger = (message: string) => void;
type ErrorLogger = (error: Error) => void;
export type UniversalLogger = Logger & ErrorLogger & { level: string };

// ===== Union Types (for comparison) =====

export type StringOrNumber = string | number;
export type Result<T> = { success: true; data: T } | { success: false; error: string };

// ===== Template Literal Types =====

export type EventName<T extends string> = `${T}Changed`;
export type PropEventName<T extends string> = `on${Capitalize<T>}`;

// ===== Complex Combined Types =====

// Combination of mapped and conditional types
export type PickByType<T, U> = {
    [P in keyof T as T[P] extends U ? P : never]: T[P];
};

// Combination of tuple, conditional, and mapped types
export type TupleToUnion<T extends readonly any[]> = T[number];
export type UnionToIntersection<U> = (U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never;

// Recursive conditional type
export type DeepReadonly<T> = T extends object
    ? { readonly [P in keyof T]: DeepReadonly<T[P]> }
    : T;

// ===== Utility Type Aliases =====

export type NonNullable<T> = T extends null | undefined ? never : T;
export type Parameters<T extends (...args: any) => any> = T extends (...args: infer P) => any ? P : never;
export type ConstructorParameters<T extends new (...args: any) => any> = T extends new (...args: infer P) => any ? P : never;
