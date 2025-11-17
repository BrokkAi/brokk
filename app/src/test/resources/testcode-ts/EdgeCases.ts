// ===== Section 1: Empty File / Whitespace / Comments Only =====
// This section intentionally contains only comments and whitespace
// to test that the analyzer handles files with no actual declarations

/*
 * Multi-line comment block
 * Still no code here
 */

// More comments


// ===== Section 2: Deeply Nested Namespace Structure (5 levels) =====
namespace A {
    export namespace B {
        export namespace C {
            export namespace D {
                export namespace E {
                    export class Deeply {
                        value: number = 42;
                        
                        method(): string {
                            return "deeply nested";
                        }
                    }
                    
                    export function deepFunction(): void {
                        console.log("deep");
                    }
                    
                    export const DEEP_CONSTANT = "very deep";
                }
            }
        }
    }
}

// Test access to deeply nested class
const deepInstance = new A.B.C.D.E.Deeply();

// ===== Section 3: String Enums =====
export enum Status {
    Active = 'ACTIVE',
    Inactive = 'INACTIVE',
    Pending = 'PENDING',
    Archived = 'ARCHIVED'
}

// Non-exported string enum
enum LogLevel {
    Debug = 'DEBUG',
    Info = 'INFO',
    Warning = 'WARN',
    Error = 'ERROR'
}

// ===== Section 4: Heterogeneous Enums (Mixed String/Number Members) =====
export enum MixedEnum {
    No = 0,
    Yes = 'YES',
    Unknown = 1,
    Maybe = 'MAYBE',
    Certainly = 2
}

// Another heterogeneous enum with various patterns
enum ResponseCode {
    Success = 200,
    SuccessMessage = 'OK',
    NotFound = 404,
    NotFoundMessage = 'Not Found',
    ServerError = 500
}

// ===== Section 5: Computed Enum Members =====
export enum Flags {
    None = 0,
    Read = 1 << 0,      // 1
    Write = 1 << 1,     // 2
    Execute = 1 << 2,   // 4
    Delete = 1 << 3,    // 8
    All = Read | Write | Execute | Delete
}

// Computed enum with expressions
enum Permissions {
    ViewOnly = 1,
    Edit = ViewOnly << 1,           // 2
    Admin = Edit << 1,              // 4
    SuperAdmin = Admin << 1,        // 8
    FullAccess = ViewOnly | Edit | Admin | SuperAdmin  // 15
}

// Computed enum with arithmetic
export enum FileSize {
    KB = 1024,
    MB = KB * 1024,
    GB = MB * 1024,
    TB = GB * 1024
}

// ===== Additional Edge Case: Enum with Explicit and Implicit Members =====
enum Counter {
    First,          // 0
    Second,         // 1
    Third = 10,     // 10
    Fourth,         // 11
    Fifth = 20,     // 20
    Sixth           // 21
}

// ===== Edge Case: Empty Enum =====
export enum EmptyEnum {
}

// ===== Edge Case: Single Member Enum =====
enum SingleMember {
    OnlyOne = 'ONLY'
}
