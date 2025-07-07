import type { Plugin } from 'svelte-exmarkdown';
import rehypeShikiFromHighlighter from '@shikijs/rehype/core';
import { createHighlighterCore } from 'shiki/core';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';
import { createCssVariablesTheme } from 'shiki/core';

// Initial languages and themes
import js from 'shiki/langs/javascript.mjs';
import ts from 'shiki/langs/typescript.mjs';
import python from 'shiki/langs/python.mjs';
import java from 'shiki/langs/java.mjs';
import bash from 'shiki/langs/bash.mjs';

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
    return node;
  }
};

// Singleton promise for the Shiki plugin
export const shikiPluginPromise: Promise<Plugin> = createHighlighterCore({
  themes: [cssVarsTheme],
  langs: [js, ts, python, java, bash],
  engine: createJavaScriptRegexEngine({
    target: 'ES2018',
    forgiving: true
  }),
}).then(highlighter => ({
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

// Function to dynamically load additional languages
export async function ensureLang(langId: string) {
  const highlighter = await shikiPluginPromise.then(p => p.rehypePlugin[1]);
  if (!highlighter.getLoadedLanguages().includes(langId)) {
    try {
      const langModule = await import(`shiki/langs/${langId}.mjs`);
      await highlighter.loadLanguage(langModule.default ?? langModule);
      console.log(`Loaded language: ${langId}`);
    } catch (error) {
      console.error(`Failed to load language ${langId}:`, error);
    }
  }
}

