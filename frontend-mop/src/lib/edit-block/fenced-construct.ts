import type {Construct} from 'micromark-util-types';
import {tokenizeFencedEditBlock} from './fenced-edit-block';

/**
 * Define the fenced edit block construct as a container.
 */
export const fencedEditBlock: Construct = {
    name: 'editBlock', // Same node type – mdast stays unchanged
    tokenize: tokenizeFencedEditBlock,
    concrete: true
};
