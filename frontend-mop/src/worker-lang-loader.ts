import type { LanguageInput, HighlighterCore } from 'shiki/core';

// This will be initialized in the worker.
export let highlighter: HighlighterCore;
export function setHighlighter(h: HighlighterCore) {
    highlighter = h;
}

// Lazy import map
export const langLoaders: Record<string, () => Promise<{ default: LanguageInput }>> = {
  javascript: () => import('shiki/langs/javascript.mjs'),
  typescript: () => import('shiki/langs/typescript.mjs'),
  java: () => import('shiki/langs/java.mjs'),
  python: () => import('shiki/langs/python.mjs'),
  bash: () => import('shiki/langs/bash.mjs'),
  json: () => import('shiki/langs/json.mjs'),
  yaml: () => import('shiki/langs/yaml.mjs'),
  markdown: () => import('shiki/langs/markdown.mjs')
};

const inflight = new Map<string, Promise<void>>();

export function ensureLang(id: string): Promise<void> {
  id = id.toLowerCase();
  if (!highlighter) {
      return Promise.reject('Highlighter not set');
  }
  if (highlighter.getLoadedLanguages().includes(id)) return Promise.resolve();

  let p = inflight.get(id);
  if (!p) {
    const loader = langLoaders[id];
    if (!loader) {
      console.warn('[Shiki] no loader for', id);
      return Promise.resolve();
    }
    p = loader()
      .then(mod => highlighter.loadLanguage(mod.default ?? mod))
      .catch(e => console.error('[Shiki] load failed', id, e));
    inflight.set(id, p);
  }
  return p;
}
