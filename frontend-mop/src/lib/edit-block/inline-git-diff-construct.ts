import type {Construct} from 'micromark-util-types';
import {tokenizeInlineGitDiff} from './inline-git-diff-tokenizer';

/**
 * Define the inline git diff construct for handling ```[git diff content]``` format.
 * This handles the case where git diff content is on the same line as the backticks.
 */
export const inlineGitDiffConstruct: Construct = {
    name: 'editBlock', // Same node type as fenced blocks
    tokenize: tokenizeInlineGitDiff,
    concrete: true
};