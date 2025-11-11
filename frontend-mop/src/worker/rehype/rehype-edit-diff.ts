import type {Root} from 'hast';
import type {HighlighterCore} from 'shiki/core';
import {visit} from 'unist-util-visit';
import {buildUnifiedDiff, getMdLanguageTag} from '../../lib/diff-utils';
import {currentExpandIds, userCollapsedIds} from '../expand-state';
import type {EditBlockProperties} from '../shared';
import {transformerDiffLines} from '../shiki/shiki-diff-transformer';
import { createWorkerLogger } from '../../lib/logging';
import type { VFile } from 'vfile';

export function rehypeEditDiff(highlighter: HighlighterCore) {
    // History sequences start at this threshold; see historyStore.ts (nextHistoryBubbleSeq).
    const HISTORY_SEQ_START = 1_000_000;
    const diffLog = createWorkerLogger('rehype-edit-diff');

    return function (this: any, tree: Root, file: VFile) {
        // Bridge: mirror per-run sequence from VFile onto the HAST root for downstream plugins
        const seqFromFile = (file as any)?.data?.parseSeq as number | undefined;
        if (typeof seqFromFile === 'number') {
            (tree.data ??= {}).parseSeq = seqFromFile;
        }
        // Identify whether this parse run is for history or live by reading from tree.data
        const seq: number | undefined = (tree as any).data?.parseSeq;
        const isHistory = typeof seq === 'number' && seq >= HISTORY_SEQ_START;

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

            // Auto-expand only when ALL of the following hold:
            //  - The edit block is structurally complete (p.complete comes from the micro-parser via from-markdown).
            //  - The diff is small (adds + dels <= 50) and non-empty.
            //  - We haven't already marked it expanded in this worker pass.
            //  - The user has not previously collapsed this block during the stream (opened then closed)
            //    — userCollapsedIds suppresses any auto-open after manual collapse.
            const totalChanges = (p.adds ?? 0) + (p.dels ?? 0);
            const AUTO_EXPAND_MAX = 50;

            // Auto-expand only for live (non-history) parses when conditions are met.
            if (
                !isHistory &&
                (p.complete === true) &&
                totalChanges > 0 &&
                totalChanges <= AUTO_EXPAND_MAX &&
                !currentExpandIds.has(p.id) &&
                !userCollapsedIds.has(p.id)
            ) {
                currentExpandIds.add(p.id);
            } else if (
                isHistory &&
                (p.complete === true) &&
                totalChanges > 0 &&
                totalChanges <= AUTO_EXPAND_MAX
            ) {
                // Debug visibility: auto-expansion suppressed solely due to history context
                diffLog.debug(
                    'auto-expand suppressed for history seq',
                    String(seq),
                    'blockId=',
                    p.id
                );
            }

            // Respect user collapse even if some other path marked it expanded.
            // Also allow manual expansion in history (worker state via expand-diff).
            if (!currentExpandIds.has(p.id) || userCollapsedIds.has(p.id)) return;
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
