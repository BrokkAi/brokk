import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from './util';

/**
 * Tokenizer for edit block divider.
 */
export const tokenizeDivider: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx(effects, ctx);
    return start;

    function start(code: Code): State {
        if (code !== codes.equalsTo) return nok(code);
        fx.enter('editBlockDivider');
        fx.consume(code);
        return sequence(1);
    }

    function sequence(count: number): State {
        return function (code: Code): State {
            if (code === codes.equalsTo) {
                fx.consume(code);
                return sequence(count + 1);
            }
            if (count < 7) {
                fx.exit('editBlockDivider');
                return nok(code);
            }
            // Optional whitespace before filename
            if (code === codes.space || code === codes.horizontalTab) {
                fx.consume(code);
                return dividerFilenameStart;
            }
            // Accept immediate EOL/EOF
            if (markdownLineEnding(code) || code === codes.eof) {
                fx.exit('editBlockDivider');
                return ok(code);
            }
            // Any other character starts the filename
            fx.enter('editBlockDividerFilename');
            return dividerFilenameContinue(code);
        };
    }

    function dividerFilenameStart(code: Code): State {
        // If only spaces were provided before EOL/EOF
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockDivider');
            return ok(code);
        }
        fx.enter('editBlockDividerFilename');
        return dividerFilenameContinue(code);
    }

    function dividerFilenameContinue(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockDividerFilename');
            fx.exit('editBlockDivider');
            return ok(code);
        }
        fx.consume(code);
        return dividerFilenameContinue;
    }
};
