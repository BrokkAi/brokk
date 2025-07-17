import type { Construct } from 'micromark-util-types';
import { tokenizeUnfenced } from './unfenced-tokenizer';

/**
 * Define the edit block construct as a container.
 */
export const editBlock: Construct = {
    name: 'editBlock',
    tokenize: tokenizeUnfenced,
    concrete: true
};
