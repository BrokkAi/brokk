import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from '../util';

/**
 * Tokenizer for edit block tail.
 */
export const tokenizeTail: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('tokenizeTail', effects, ctx, ok, nok);
    return start;

    function start(code: Code): State {
        if (code !== codes.greaterThan) return fx.nok(code);
        fx.enter('editBlockTail');
        fx.consume(code);
        return sequence(1);
    }

    function sequence(count: number): State {
        return function (code: Code): State {
            if (code === codes.greaterThan) {
                fx.consume(code);
                return sequence(count + 1);
            }
            if (count < 7) {
                fx.exit('editBlockTail');
                return fx.nok(code);
            }
            if (code === codes.space) {
                fx.consume(code);
            }
            fx.enter('editBlockTailKeyword');
            return keyword(0);
        };
    }

    function keyword(index: number): State {
        return function (code: Code): State {
            const keywordText = 'REPLACE';
            if (index < keywordText.length) {
                if (code === keywordText.charCodeAt(index)) {
                    fx.consume(code);
                    return keyword(index + 1);
                }
                fx.exit('editBlockTailKeyword');
                fx.exit('editBlockTail');
                return fx.nok(code);
            }
            // Optional whitespace before filename
            if (code === codes.space || code === codes.horizontalTab) {
                fx.consume(code);
                return tailFilenameStart;
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                fx.exit('editBlockTailKeyword');
                fx.exit('editBlockTail');
                return fx.ok(code);
            }
            // Something else on the line starts the filename
            fx.enter('editBlockTailFilename');
            return tailFilenameContinue(code);
        };
    }

    function tailFilenameStart(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockTailKeyword');
            fx.exit('editBlockTail');
            return fx.ok(code);
        }
        fx.enter('editBlockTailFilename');
        return tailFilenameContinue(code);
    }

    function tailFilenameContinue(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockTailFilename');
            fx.exit('editBlockTailKeyword');
            fx.exit('editBlockTail');
            return fx.ok(code);
        }
        fx.consume(code);
        return tailFilenameContinue;
    }
};
