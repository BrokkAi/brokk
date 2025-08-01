import type {Construct} from 'micromark-util-types';
import {tokenizeUnfencedEditBlock} from './unfenced-edit-block';

/**
 * Define the edit block construct as a container.
 */
export const unfencedEditBlock: Construct = {
    name: 'editBlock',
    tokenize: tokenizeUnfencedEditBlock,
    concrete: true
};
