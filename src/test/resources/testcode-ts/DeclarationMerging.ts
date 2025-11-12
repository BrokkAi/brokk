// Test file for TypeScript declaration merging

// Interface merging - multiple declarations of the same interface
interface User {
    id: number;
}

interface User {
    name: string;
    email?: string;
}

interface User {
    createdAt: Date;
    updateProfile(name: string): void;
}

// Function + namespace merging
function buildQuery(base: string): string {
    return base + "?";
}

namespace buildQuery {
    export function withParams(params: Record<string, string>): string {
        return Object.entries(params).map(([k, v]) => `${k}=${v}`).join("&");
    }
    export const version: string = "1.0";
}

// Enum + namespace merging
enum Status {
    Active = "ACTIVE",
    Inactive = "INACTIVE"
}

namespace Status {
    export function isActive(status: Status): boolean {
        return status === Status.Active;
    }
}

// Class + namespace merging
class Config {
    constructor(public data: Record<string, any>) {}
    
    get(key: string): any {
        return this.data[key];
    }
}

namespace Config {
    export const DEFAULT_CONFIG = { timeout: 5000 };
    export function create(data: Record<string, any>): Config {
        return new Config(data);
    }
}

// Multiple interface declarations with method overloads
interface Calculator {
    add(a: number, b: number): number;
}

interface Calculator {
    add(a: string, b: string): string;
    subtract(a: number, b: number): number;
}

interface Calculator {
    multiply(a: number, b: number): number;
    divide(a: number, b: number): number;
}

// Export merged interface
export interface ApiResponse {
    status: number;
    data: any;
}

export interface ApiResponse {
    headers: Record<string, string>;
    timestamp: number;
}

// Conflicting property types should use the last declaration (TypeScript behavior)
interface Conflicting {
    value: string;
}

interface Conflicting {
    value: number; // This will override the previous declaration
    extra: boolean;
}
