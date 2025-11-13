// Test file for import/export edge cases
// This file tests various import and export patterns to ensure the analyzer handles them without errors

// ===== Re-exports =====

// Re-export all exports from a module
export * from './module';

// Re-export specific named exports
export { A, B } from './other';

// Re-export with rename
export { OriginalName as RenamedExport } from './utils';

// Re-export default as named
export { default as ModuleDefault } from './defaults';

// Re-export all as namespace
export * as NamespaceExport from './namespace';

// ===== Namespace imports =====

// Import entire module as namespace
import * as NS from './module';

// Use namespace import
export function useNamespace() {
    return NS.someFunction();
}

// Import from node_modules (external dependency)
import * as React from 'react';

// ===== Default + Named imports =====

// Default import with named imports
import DefaultExport, { namedExport1, namedExport2 } from './combined';

// Named imports with aliases
import { longName as short, anotherLongName as another } from './aliases';

// Default import only
import OnlyDefault from './default-only';

// Named imports only
import { justNamed, alsoNamed } from './named-only';

// ===== Side-effect imports =====

// Import for side effects only (CSS, polyfills, etc.)
import './styles.css';
import './polyfills';
import './global-setup.js';

// ===== Mixed patterns =====

// Combination: default, named, and side-effect
import MainComponent, { helper1, helper2 } from './main';

// Type-only imports (TypeScript specific)
import type { TypeOnly } from './types';
import type DefaultType from './default-type';

// ===== Local declarations to test analyzer doesn't confuse imports with declarations =====

export class LocalClass {
    method() {
        return "local";
    }
}

export interface LocalInterface {
    id: number;
    name: string;
}

export function localFunction() {
    return "local function";
}

export const localConst = 42;

// ===== Dynamic imports (edge case) =====

export async function dynamicImport() {
    const module = await import('./dynamic');
    return module.default;
}

// ===== Circular dependency pattern =====

import { CircularA } from './circular-a';

export class CircularB {
    useA() {
        return new CircularA();
    }
}

// ===== Barrel exports pattern =====

export { UtilA, UtilB } from './utils/util-a';
export { HelperC, HelperD } from './utils/helper-c';
export * from './utils/common';
