// Comprehensive test file for TypeScript patterns found in VSCode codebase
// Tests namespace-as-package, index signatures, call signatures, arrow functions, etc.

namespace vs.base.common {
    // Test 1: Class in namespace (namespace-as-package)
    export class Disposable {
        dispose(): void {
            // cleanup
        }
    }

    // Test 2: Interface with index signature
    export interface IStringDictionary<V = any> {
        [index: string]: V;
    }

    // Test 3: Interface with call signature
    export interface IDisposable {
        (): void;
    }

    // Test 4: Arrow function in namespace
    export const createCancelablePromise = <T>(executor: (resolve: (value: T) => void) => void): Promise<T> => {
        return new Promise<T>(executor);
    };

    // Test 7: Type alias in namespace
    export type Event<T> = (listener: (e: T) => any) => IDisposable;

    // Test 5: Nested namespace
    export namespace strings {
        export function format(template: string, ...args: any[]): string {
            return template.replace(/{(\d+)}/g, (match, index) => {
                return typeof args[index] !== 'undefined' ? args[index] : match;
            });
        }
    }
}

// Test 6: Top-level arrow function
export const timeout = (ms: number): Promise<void> => {
    return new Promise(resolve => setTimeout(resolve, ms));
};
