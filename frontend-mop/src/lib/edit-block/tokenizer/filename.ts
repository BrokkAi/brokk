import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from '../util';

/**
 * Tokenizer for the filename in an edit block.
 * The filename can appear inline with the opening fence or on the next line.
 * It is optional; if not present, succeeds with zero length.
 * Rejects if whitespace is found in the filename.
 * Leaves the stream positioned after the terminating newline or at the current position if no filename.
 */
export const tokenizeFilename: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('filename', effects, ctx, ok, nok);

    let hasFilename = false;

    return start;

    function start(code: Code): State {
        // If the first byte after the fence is a newline, check the next line for filename
        if (markdownLineEnding(code)) {
            return afterFenceNewline;
        }

        // Otherwise, treat this line as an inline filename
        if (code === codes.eof) {
            return fx.ok(code); // No filename, succeed with zero length
        }

        if (code === codes.space || code === codes.horizontalTab) {
            return fx.nok(code); // Reject space after fence
        }

        fx.enter('editBlockFilename');
        hasFilename = true;
        return inlineFilename(code);
    }

    function afterFenceNewline(code: Code): State {
        if (markdownLineEnding(code)) {
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return afterFenceNewline; // Skip additional blank lines if any
        }

        if (code === codes.eof) {
            return fx.ok(code); // No filename, succeed with zero length
        }

        if (code === codes.lessThan) {
            return fx.ok(code); // Header starts, no filename, succeed with zero length
        }

        fx.enter('editBlockFilename');
        hasFilename = true;
        return nextLineFilename(code);
    }

    function inlineFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockFilename');
            return fx.ok; // Don't consume the newline, let the next tokenizer handle it
        }

        if (code === codes.space || code === codes.horizontalTab) {
            fx.exit('editBlockFilename');
            return fx.nok(code); // Reject whitespace in filename
        }

        fx.consume(code);
        return inlineFilename;
    }

    function nextLineFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockFilename');
            return fx.ok; // Don't consume the newline, let the next tokenizer handle it
        }

        if (code === codes.space || code === codes.horizontalTab) {
            fx.exit('editBlockFilename');
            return fx.nok(code); // Reject whitespace in filename
        }

        fx.consume(code);
        return nextLineFilename;
    }
};
