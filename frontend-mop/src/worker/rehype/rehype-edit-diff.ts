import type {Root} from 'hast';
import type {HighlighterCore} from 'shiki/core';
import {visit} from 'unist-util-visit';
import {buildUnifiedDiff, getMdLanguageTag} from '../../lib/diff-utils';
import {currentExpandIds} from '../expand-state';
import type {EditBlockProperties} from '../shared';
import {transformerDiffLines} from '../shiki/shiki-diff-transformer';

export function rehypeEditDiff(highlighter: HighlighterCore) {
    return (tree: Root) => {

        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any) => {
            const p: EditBlockProperties = node.properties;
            // add an empty object here to not remount the exiting EditBlock when children are added
            node.children = [{type: 'text', value: ''}];
            if (!p.headerOk) return;

            // Compute lightweight diff metrics for header display even when collapsed
            const {text, added, removed} = buildUnifiedDiff(p.search, p.replace);
            p.adds = added.length;
            p.dels = removed.length;

            if (!currentExpandIds.has(p.id)) return;
            p.isExpanded = true;

            const lang = getMdLanguageTag(p.filename);

            tree.data ??= {};
            const data = tree.data as any;
            data.detectedDiffLangs ??= new Set<string>();
            (data.detectedDiffLangs as Set<string>).add(lang);

            // if the language is not loaded, use txt as a placeholder
            // it will be-reparsed after the lang is loaded automatically
            const notLoaded = !highlighter.getLoadedLanguages().includes(lang);

            // maybe later we should cache the response?
            node.children = [
                highlighter.codeToHast(text, {
                    lang: notLoaded ? 'txt' : lang,
                    colorsRendering: 'css-vars',
                    theme: 'css-vars',
                    transformers: [transformerDiffLines(added, removed)],
                })
            ];
        });
    };
}
