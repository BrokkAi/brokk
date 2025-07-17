import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from './util';
import { makeEditBlockBodyTokenizer } from './body-tokenizer';
import { tokenizeDivider } from './divider-tokenizer';
import { tokenizeTail } from './tail-tokenizer';

/**
 * Tokenizer for edit blocks.
 */
export const tokenizeUnfenced: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('tokenizeUnfenced', effects, ctx, ok, nok);
    const bodyTokenizer = makeEditBlockBodyTokenizer({
        divider: tokenizeDivider,
        tail: tokenizeTail
    });
    return start;

    function start(code: Code): State {
        if (code !== codes.lessThan) return fx.nok(code);
        fx.enter('editBlock');
        fx.enter('editBlockHead');
        fx.consume(code);
        return checkHeadLessThan(1);
    }

    function checkHeadLessThan(count: number): State {
        return function (code: Code): State {
            if (code === codes.lessThan) {
                fx.consume(code);
                return checkHeadLessThan(count + 1);
            }
            if (count < 7) {
                fx.exit('editBlockHead');
                fx.exit('editBlock');
                return fx.nok(code);
            }
            if (code !== codes.space) {
                fx.exit('editBlockHead');
                fx.exit('editBlock');
                return fx.nok(code);
            }
            fx.consume(code);
            fx.exit('editBlockHead');
            fx.enter('editBlockSearchKeyword');
            return checkSearchKeyword(0);
        };
    }

    function checkSearchKeyword(index: number): State {
        return function (code: Code): State {
            const keyword = 'SEARCH';
            if (index < keyword.length) {
                if (code !== keyword.charCodeAt(index)) {
                    fx.exit('editBlockSearchKeyword');
                    fx.exit('editBlock');
                    return fx.nok(code);
                }
                fx.consume(code);
                return checkSearchKeyword(index + 1);
            }
            return afterSearchKeyword(code);
        };
    }

    function afterSearchKeyword(code: Code): State {
        // 1. Header ends right away  -->  go to search content
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockSearchKeyword');
            fx.enter('editBlockSearchContent');
            return delegateToBody(code);
        }

        // 2. Skip one or more spaces/tabs (optional)
        if (code === codes.space || code === codes.horizontalTab) {
            fx.consume(code);
            return afterSearchKeyword;           // keep swallowing whitespace
        }

        // 3. A real filename starts here
        fx.exit('editBlockSearchKeyword');
        fx.enter('editBlockFilename');
        return inFilename(code);
    }

    function inFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockFilename');
            fx.enter('editBlockSearchContent');
            return delegateToBody(code);
        }
        fx.consume(code);
        return inFilename;
    }

    function delegateToBody(code: Code): State {
        return effects.attempt(
            { tokenize: bodyTokenizer, concrete: true },
            afterBody,
            fx.nok
        )(code);
    }

    function afterBody(code: Code): State {
        fx.exit('editBlock');
        return fx.ok(code);
    }
};
