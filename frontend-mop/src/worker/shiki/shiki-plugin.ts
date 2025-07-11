import type {Plugin} from 'svelte-exmarkdown';
import rehypeShikiFromHighlighter from '@shikijs/rehype/core';
import {createHighlighterCore} from 'shiki/core';
import {createJavaScriptRegexEngine} from 'shiki/engine/javascript';
import {createCssVariablesTheme} from 'shiki/core';
import {langLoaders} from './shiki-lang-loaders';

// Initial languages to pre-load
import js from 'shiki/langs/javascript.mjs';
import ts from 'shiki/langs/typescript.mjs';
import python from 'shiki/langs/python.mjs';
import java from 'shiki/langs/java.mjs';
import bash from 'shiki/langs/bash.mjs';
import json from 'shiki/langs/json.mjs';
import yaml from 'shiki/langs/yaml.mjs';
import markdown from 'shiki/langs/markdown.mjs';

// Define a CSS variables theme
const cssVarsTheme = createCssVariablesTheme({
    name: 'css-vars',
    variablePrefix: '--shiki-',
    variableDefaults: {},
    fontStyle: true
});

// Custom transformer to add data-language attribute dynamically
const languageAttributeTransformer = {
    name: 'add-language-attributes',
    pre(node) {
        node.properties = node.properties || {};
        node.properties['data-language'] = this.options.lang;
        console.log('shiki lang detedted:', this.options.lang);
        return node;
    }
};

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
    },
});

// Singleton promise for the Shiki plugin
export const shikiPluginPromise: Promise<Plugin> = highlighterPromise.then(highlighter => ({
    rehypePlugin: [
        rehypeShikiFromHighlighter,
        highlighter,
        {
            theme: 'css-vars',
            colorsRendering: 'css-vars',
            transformers: [languageAttributeTransformer],
        }
    ]
}));

// Function to dynamically load additional languages
export async function ensureLang(langId: string): Promise<void> {
    const highlighter = await highlighterPromise;
    langId = langId.toLowerCase();

    if (highlighter.getLoadedLanguages().includes(langId)) {
        return;
    }

    const loader = langLoaders[langId];
    if (!loader) {
        console.warn('[Shiki] No loader for language:', langId);
        return;
    }

    try {
        const mod = await loader();
        await highlighter.loadLanguage(mod.default ?? mod);
        console.debug('[Shiki] Loaded language:', langId);
    } catch (e) {
        console.error('[Shiki] Failed to load language:', langId, e);
    }
}
