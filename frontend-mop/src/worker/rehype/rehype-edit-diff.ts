import type {Root} from 'hast';
import type {HighlighterCore} from 'shiki/core';
import {visit} from 'unist-util-visit';
import {buildUnifiedDiff, getMdLanguageTag} from '../../lib/diff-utils';
import {currentExpandIds} from '../expand-state';
import type {EditBlockProperties} from '../shared';
import {transformerDiffLines} from '../shiki/shiki-diff-transformer';

export function rehypeEditDiff(highlighter: HighlighterCore) {
    return (tree: Root) => {
        // Aggregate totals per parsed bubble/tree
        let totalAdds = 0;
        let totalDels = 0;

        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any) => {
            const p: EditBlockProperties = node.properties;
            // add an empty object here to not remount the exiting EditBlock when children are added
            node.children = [{type: 'text', value: ''}];
            if (!p.headerOk) return;

            // Compute lightweight diff metrics for header display even when collapsed
            // Works for both SEARCH/REPLACE format and git diff format
            const {text, added, removed} = buildUnifiedDiff(p.search, p.replace);
            p.adds = added.length;
            p.dels = removed.length;

            // Update running totals
            totalAdds += p.adds;
            totalDels += p.dels;

            if (!currentExpandIds.has(p.id)) return;
            p.isExpanded = true;

            const lang = getMdLanguageTag(p.filename);

            const data = (tree.data ??= {});
            data.detectedDiffLangs ??= new Set<string>();
            data.detectedDiffLangs.add(lang);

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

        // Expose a compact summary for this bubble for UI aggregation
        const data = (tree.data ??= {});
        data.diffSummary = {adds: totalAdds, dels: totalDels};
    };
}
