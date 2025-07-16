import type { Extension } from 'micromark-util-types';
import { codes } from 'micromark-util-symbol';
import { editBlock } from './construct';

/**
 * Micromark extension to detect edit blocks in Markdown text.
 * Recognizes unfenced edit blocks starting with "<<<<<<< SEARCH [filename]".
 */
export function gfmEditBlock(): Extension {
    return { flow: { [codes.lessThan]: editBlock } };
}
