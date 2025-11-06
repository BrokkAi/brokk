/* Shims for missing runtime/browser globals and module types used in the frontend.
   These are intentionally permissive (any) to allow TypeScript compilation in CI
   environments where frontend dependencies/typings are not installed. */

/* Common packages referenced in the frontend but not present in this workspace */
declare module 'svelte';
declare module 'svelte/*';
declare module 'svelte/store';
declare module 'diff';
declare module 'shiki';
declare module 'shiki/*';
declare module 'shiki/core';
declare module '@shikijs/rehype/core';
declare module 'hast';
declare module 'hast/*';
declare module 'hast-util-*';
declare module 'unist';
declare module '*.svelte' {
  const Component: any;
  export default Component;
}

/* Runtime globals used by the frontend; make them permissive to satisfy compile-time checks */
declare var document: any;
declare var window: any;
declare var localStorage: any;
declare function setTimeout(handler: any, timeout?: any): any;
declare var self: any;

/* Worker url injected at build time in some setups */
declare var __WORKER_URL__: string | undefined;

/* Bridge / host callbacks that the frontend expects to exist on window/other global objects.
   Provide permissive (optional) typings so code accessing them type-checks. */
declare global {
  interface Window {
    toggleWrapStatus?: (wrap?: boolean) => void;
    onBridgeReady?: (...args: any[]) => void;
    searchStateChanged?: (...args: any[]) => void;
    jsLog?: (level: string, message: string) => void;
    brokkBridge?: { jsLog?: (level: string, message: string) => void; [k: string]: any };
    // Allow arbitrary additional properties used by tests or host shims
    [key: string]: any;
  }

  interface ImportMeta {
    readonly env?: Record<string, unknown>;
  }
}

/* Ensure this file is treated as a module and augmentations apply */
export {};
