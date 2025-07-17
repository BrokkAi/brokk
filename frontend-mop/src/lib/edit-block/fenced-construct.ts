import type { Construct } from 'micromark-util-types';
import {tokenizeEditBlock} from './edit-block-orchestrator';
import { tokenizeFenced } from './fenced-tokenizer';

/**
 * Define the fenced edit block construct as a container.
 */
export const fencedEditBlock: Construct = {
    name: 'editBlock', // Same node type – mdast stays unchanged
    tokenize: tokenizeEditBlock,
    concrete: true
};
