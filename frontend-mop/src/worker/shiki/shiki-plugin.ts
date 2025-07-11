import rehypeShikiFromHighlighter from '@shikijs/rehype/core';
import {createCssVariablesTheme, createHighlighterCore} from 'shiki/core';
import {createJavaScriptRegexEngine} from 'shiki/engine/javascript';
import bash from 'shiki/langs/bash.mjs';
import java from 'shiki/langs/java.mjs';

// Initial languages to pre-load
import js from 'shiki/langs/javascript.mjs';
import json from 'shiki/langs/json.mjs';
import markdown from 'shiki/langs/markdown.mjs';
import python from 'shiki/langs/python.mjs';
import ts from 'shiki/langs/typescript.mjs';
import yaml from 'shiki/langs/yaml.mjs';
import type {Plugin} from 'svelte-exmarkdown';
import {langLoaders} from './shiki-lang-loaders';

// Define a CSS variables theme
const cssVarsTheme = createCssVariablesTheme({
    name: 'css-vars',
    variablePrefix: '--shiki-',
    variableDefaults: {},
    fontStyle: true
});

// Singleton promise for the Shiki highlighter
export const highlighterPromise = createHighlighterCore({
    themes: [cssVarsTheme],
    langs: [js, ts, python, java, bash, json, yaml, markdown],
    engine: createJavaScriptRegexEngine({
        target: 'ES2018',
        forgiving: true
    }),
    langAlias: {
        svelte: 'html'
    }
});

export const languageAttributeTransformer = {
    name: 'add-language-attributes',
    pre(node: any) {
        const lang = (this.options.lang ?? '').toLowerCase();
        if (lang) {
            node.properties ??= {};
            node.properties['data-language'] = lang;
        }
        return node;
    }
};

// Singleton promise for the Shiki plugin
export const shikiPluginPromise: Promise<Plugin> = highlighterPromise.then(highlighter => ({
    rehypePlugin: [
        rehypeShikiFromHighlighter,
        highlighter,
        {
            theme: 'css-vars',
            colorsRendering: 'css-vars',
            transformers: [languageAttributeTransformer]
        }
    ]
}));

const langPromiseCache = new Map<string, Promise<void>>();

/**
 * Ensures `langId` is available in the highlighter.
 *
 * • Completely idempotent: call it as many times as you like.
 * • If several calls race on the same lang they all await the same promise.
 * • Returns *immediately* resolved promise if the language is already loaded.
 */
export function ensureLang(langId: string): Promise<void> {
    langId = langId.toLowerCase();
    if (!langId) {
        return Promise.resolve();
    }

    // Return cached promise if a load is already in flight.
    if (langPromiseCache.has(langId)) {
        return langPromiseCache.get(langId)!;
    }

    const p = highlighterPromise.then(async highlighter => {
        if (highlighter.getLoadedLanguages().includes(langId)) {
            return;
        }

        const loader = langLoaders[langId];
        if (!loader) {
            console.warn('[Shiki] No loader registered for:', langId);
            return;
        }

        try {
            const mod = await loader();
            await highlighter.loadLanguage(mod.default ?? mod);
            console.log('[Shiki] Language loaded:', langId);
        } catch (e) {
            console.error('[Shiki] Language load failed:', langId, e);
            // On failure, remove from cache so we can retry.
            langPromiseCache.delete(langId);
        }
    });

    // Cache the promise *before* returning it to dedupe races.
    langPromiseCache.set(langId, p);
    return p;
}
