import type { Construct } from 'micromark-util-types';
import { tokenizeFenced } from './fenced-tokenizer';

/**
 * Define the fenced edit block construct as a container.
 */
export const fencedEditBlock: Construct = {
    name: 'editBlock', // Same node type – mdast stays unchanged
    tokenize: tokenizeFenced,
    concrete: true
};
