import type { Construct } from 'micromark-util-types';
import { tokenize } from './tokenizer';

/**
 * Define the edit block construct as a container.
 */
export const editBlock: Construct = {
    name: 'editBlock',
    tokenize,
    concrete: true
};
