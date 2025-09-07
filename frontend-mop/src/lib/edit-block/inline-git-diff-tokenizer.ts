import {markdownLineEnding} from 'micromark-util-character';
import {codes} from 'micromark-util-symbol';
import {Code, State, Tokenizer} from 'micromark-util-types';
import {tokenizeGitDiff} from './tokenizer/git-diff-parser';
import {makeSafeFx} from './util';

/**
 * Tokenizer for inline git diff format: ```[git diff content]```
 * Handles the case where everything is on one line.
 */
export const tokenizeInlineGitDiff: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('inlineGitDiff', effects, ctx, ok, nok);

    let fenceSize = 0;
    let inGitDiff = false;

    return start;

    function start(code: Code): State {
        // Must start with backticks
        if (code !== codes.graveAccent) {
            return fx.nok(code);
        }

        fx.enter('editBlock');
        return consumeFenceStart(code);
    }

    function consumeFenceStart(code: Code): State {
        if (code === codes.graveAccent) {
            fenceSize++;
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return consumeFenceStart;
        }

        // Need at least 3 backticks
        if (fenceSize < 3) {
            fx.exit('editBlock');
            return fx.nok(code);
        }

        // Must be followed by [
        if (code !== codes.leftSquareBracket) {
            fx.exit('editBlock');
            return fx.nok(code);
        }

        // Start git diff parsing
        inGitDiff = true;
        return effects.attempt(
            { tokenize: tokenizeGitDiff, concrete: true },
            afterGitDiff,
            fx.nok
        )(code);
    }

    function afterGitDiff(code: Code): State {
        // After git diff, expect closing bracket
        if (code === codes.rightSquareBracket) {
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return expectClosingFence;
        }

        fx.exit('editBlock');
        return fx.nok(code);
    }

    function expectClosingFence(code: Code): State {
        // Should be followed by closing backticks
        if (code === codes.graveAccent) {
            return consumeFenceEnd(code);
        }

        // Allow line ending or EOF
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlock');
            return fx.ok(code);
        }

        fx.exit('editBlock');
        return fx.nok(code);
    }

    function consumeFenceEnd(code: Code): State {
        if (code === codes.graveAccent) {
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return consumeFenceEnd;
        }

        // Done with closing fence
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlock');
            return fx.ok(code);
        }

        fx.exit('editBlock');
        return fx.ok(code);
    }
};