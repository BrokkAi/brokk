import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Tokenizer, Code, State } from 'micromark-util-types';
import { makeSafeFx } from '../util';

/**
 * Tokenizer for the header of an edit block.
 * Recognizes `<<<<<<< SEARCH [optional-filename]` followed by a line-ending.
 * On success:
 *   - Leaves the stream positioned *after* the newline/EOF
 *   - Has entered/exited: editBlockHead, editBlockSearchKeyword, (optional) editBlockFilename
 *   - Leaves the parser *inside* `editBlockSearchContent` so that the body tokenizer can start immediately.
 */
export const tokenizeHeader: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('header', effects, ctx, ok, nok);

    let ltCount = 0;

    return start;

    function start(code: Code): State {
        if (code !== codes.lessThan) return fx.nok(code);

        fx.enter('editBlockHead');
        fx.consume(code);
        ltCount = 1;
        return consumeLessThan;
    }

    function consumeLessThan(code: Code): State {
        // Incomplete check
        if (code === codes.eof) {
            fx.exit('editBlockHead');
            return fx.ok(code);
        }

        if (code === codes.lessThan) {
            ltCount++;
            fx.consume(code);
            return consumeLessThan;
        }

        if (ltCount < 7 || code !== codes.space) {
            fx.exit('editBlockHead');
            return fx.nok(code);
        }
        fx.consume(code);
        fx.exit('editBlockHead');
        fx.enter('editBlockSearchKeyword');
        return checkSearchKeyword(0);
    }

    function checkSearchKeyword(index: number): State {
        return function (code: Code): State {
            // Incomplete check
            if (code === codes.eof) {
                fx.exit('editBlockSearchKeyword');
                return fx.ok(code);
            }

            const keyword = 'SEARCH';
            if (index < keyword.length) {
                if (code !== keyword.charCodeAt(index)) {
                    fx.exit('editBlockSearchKeyword');
                    return fx.nok(code);
                }
                fx.consume(code);
                return checkSearchKeyword(index + 1);
            }
            return afterSearchKeyword(code);
        };
    }

    function afterSearchKeyword(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockSearchKeyword');
            return fx.ok(code);
        }
        if (code === codes.space || code === codes.horizontalTab) {
            fx.consume(code);
            return afterSearchKeyword;
        }
        fx.exit('editBlockSearchKeyword');
        fx.enter('editBlockFilename');
        return inFilename(code);
    }

    function inFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockFilename');
            return fx.ok(code);
        }
        if (code === codes.space || code === codes.horizontalTab) {
            fx.exit('editBlockFilename');
            return fx.nok(code);
        }
        fx.consume(code);
        return inFilename;
    }
};
