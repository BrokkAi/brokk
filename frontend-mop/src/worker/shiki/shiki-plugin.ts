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
    })
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

export const codeLoggingTransformer = {
    name: 'code-logging',
    pre(node: any) {
        const lang = (this.options.lang ?? '').toLowerCase();
        workerLog('info', `[SHIKI-LOGGER] Processing code block:`);
        workerLog('info', `  - language: "${lang}"`);
        workerLog('info', `  - node.tagName: "${node.tagName}"`);
        workerLog('info', `  - node.properties: ${JSON.stringify(node.properties || {})}`);
        workerLog('info', `  - children count: ${node.children?.length || 0}`);

        if (node.children && node.children.length > 0) {
            const textContent = node.children
                .filter((child: any) => child.type === 'text')
                .map((child: any) => child.value)
                .join('');
            workerLog('info', `  - text content preview: "${textContent.substring(0, 100)}${textContent.length > 100 ? '...' : ''}"`);
        }
        workerLog('info', `  ---`);

        return node;
    },
    code(node: any) {
        // Log inline code elements
        workerLog('info', `[SHIKI-LOGGER] Processing inline code:`);
        workerLog('info', `  - node content: "${node.value || 'null'}"`);
        workerLog('info', `  - node meta: ${JSON.stringify(node.meta || {})}`);
        workerLog('info', `  ---`);
        return node;
    }
};

// Worker logging helper
function workerLog(level: 'info' | 'warn' | 'error' | 'debug', message: string) {
    self.postMessage({ type: 'worker-log', level, message });
}

// Singleton promise for the Shiki plugin
export const shikiPluginPromise: Promise<Plugin> = highlighterPromise.then(highlighter => ({
    rehypePlugin: [
        rehypeShikiFromHighlighter,
        highlighter,
        {
            theme: 'css-vars',
            colorsRendering: 'css-vars',
            transformers: [codeLoggingTransformer, languageAttributeTransformer]
        }
    ]
}));
