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

    // Helper tokenizer to detect and consume a leading '//' plus an optional single space.
    // Used only on the "next line" filename path, so inline-filename strictness remains unchanged.
    const tokenizeDoubleSlashPrefix: Tokenizer = function (effects, ok, nok) {
        const fx2 = makeSafeFx('filenameCommentPrefix', effects, ctx as any, ok, nok);

        return startComment;

        function startComment(code: Code): State {
            if (code !== codes.slash) return fx2.nok(code);
            fx2.enter('chunk');
            fx2.consume(code); // first '/'
            fx2.exit('chunk');
            return afterFirstSlash;
        }

        function afterFirstSlash(code: Code): State {
            if (code !== codes.slash) return fx2.nok(code);
            fx2.enter('chunk');
            fx2.consume(code); // second '/'
            fx2.exit('chunk');
            return maybeSpaceAfterComment;
        }

        function maybeSpaceAfterComment(code: Code): State {
            if (code === codes.space) {
                fx2.enter('chunk');
                fx2.consume(code); // optional single space
                fx2.exit('chunk');
            }
            return fx2.ok(code);
        }
    };

    return start;

    function start(code: Code): State {
        // If the first byte after the fence is a newline, check the next line for filename
        if (markdownLineEnding(code)) {
            return afterFenceNewline;
        }

        if (code === codes.space || code === codes.horizontalTab) {
            return fx.nok(code); // Reject space after fence
        }

        // Detect if we are at the beginning of a line. If so, treat this as the "next line" path
        // even though the orchestrator consumed the newline already.
        const atLineStart =
            typeof (ctx as any).now === 'function' ? (ctx as any).now().column === 1 : false;

        if (atLineStart) {
            // Support a comment-prefixed filename line: // filename
            if (code === codes.slash) {
                return effects.check(
                    { tokenize: tokenizeDoubleSlashPrefix, concrete: true },
                    afterCommentCheck,
                    notComment
                )(code);
            }

            // Regular next-line filename (no internal whitespace allowed)
            fx.enter('editBlockFilename');
            hasFilename = true;
            return nextLineFilename(code);
        }

        // Inline filename (same line as fence) stays strict
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

        // Support a comment-prefixed filename line: // filename
        if (code === codes.slash) {
            return effects.check(
                { tokenize: tokenizeDoubleSlashPrefix, concrete: true },
                afterCommentCheck,
                notComment
            )(code);
        }

        fx.enter('editBlockFilename');
        hasFilename = true;
        return nextLineFilename(code);
    }

    function afterCommentCheck(code: Code): State {
        // Actually consume the comment prefix now
        return effects.attempt(
            { tokenize: tokenizeDoubleSlashPrefix, concrete: true },
            afterCommentConsumed,
            fx.nok
        )(code);
    }

    function afterCommentConsumed(code: Code): State {
        // If nothing follows on the line, treat as "no filename" and let header parsing proceed.
        if (markdownLineEnding(code) || code === codes.eof) {
            return fx.ok(code);
        }
        fx.enter('editBlockFilename');
        hasFilename = true;
        return nextLineFilename(code);
    }

    function notComment(code: Code): State {
        // Fall back to normal next-line filename parsing (e.g., for paths that start with '/')
        fx.enter('editBlockFilename');
        hasFilename = true;
        return nextLineFilename(code);
    }

    function inlineFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.exit('editBlockFilename');
            return fx.ok(code); // Don't consume the newline, let the next tokenizer handle it
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
