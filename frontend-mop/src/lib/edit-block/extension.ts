import type { Extension } from 'micromark-util-types';
import { codes } from 'micromark-util-symbol';
import { unfencedEditBlock } from './unfenced-construct';
import { fencedEditBlock } from './fenced-construct';

/**
 * Micromark extension to detect edit blocks in Markdown text.
 * Recognizes both unfenced edit blocks starting with "<<<<<<< SEARCH [filename]"
 * and fenced edit blocks starting with "```".
 */
export function gfmEditBlock(): Extension {
    return {
        flow: {
            [codes.lessThan]: unfencedEditBlock, // Unfenced edit blocks
            [codes.graveAccent]: fencedEditBlock // Fenced edit blocks with ```
        }
    };
}
