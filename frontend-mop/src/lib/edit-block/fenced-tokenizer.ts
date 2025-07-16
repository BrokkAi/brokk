import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';

import { makeSafeFx } from './util';
import { makeEditBlockBodyTokenizer } from './body-tokenizer';
import { tokenizeDivider } from './divider-tokenizer';
import { tokenizeTail } from './tail-tokenizer';

/**
 * Tokeniser for ```-wrapped edit blocks.
 *
 * Re-uses:
 *   - header states from tokenizer.ts (checkHeadLessThan, ...)
 *   - bodyTokenizer (search / replace, divider, tail)
 */
export const tokenizeFenced: Tokenizer = function (effects, ok, nok) {
    // Setup helpers
    const ctx = this;
    const fx = makeSafeFx(effects, ctx);

    // Same body tokenizer we already ship
    const bodyTokenizer = makeEditBlockBodyTokenizer({
        divider: tokenizeDivider,
        tail: tokenizeTail,
        makeSafeFx
    });

    // Will hold ` (back-tick) and how many we saw
    let fenceMarker: Code;
    let fenceSize = 0;

    // State machine
    let filenameSeen = false; // Track if we've seen a filename line
    return openingSequence;

    // 1. Absorb ```
    function openingSequence(code: Code): State {
        fenceMarker ??= code; // Remember whether it's `
        if (code !== fenceMarker) return nok(code);

        fx.enter('editBlock');
        fx.enter('editBlockFenceOpen'); // Purely cosmetic, useful for debugging
        consumeFence(code, 1);
        return openingSequenceTail;
    }

    function openingSequenceTail(code: Code): State {
        if (code === fenceMarker) {
            consumeFence(code, fenceSize + 1);
            return openingSequenceTail;
        }

        if (fenceSize < 3) { // GFM: ≥3 markers constitute a fence
            fx.exit('editBlockFenceOpen');
            fx.exit('editBlock');
            return nok(code);
        }

        // Absorb optional spaces, info string (= filename) up to first EOL
        if (!markdownLineEnding(code)) {
            fx.exit('editBlockFenceOpen');
            fx.enter('editBlockFilename'); // Info string *is* our filename
            return infoString(code);
        } else {
            fx.consume(code);
            fx.exit('editBlockFenceOpen');
        }

        return afterOpeningLine; // Immediately jumped to next line
    }

    function consumeFence(c: Code, newSize: number) {
        fenceSize = newSize;
        fx.consume(c);
    }

    function infoString(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.consume(code);
            fx.exit('editBlockFilename');
            filenameSeen = true;
            return afterOpeningLine;
        }
        fx.consume(code);
        return infoString;
    }

    // 2. We are *after* the opening fence’s end-of-line
    //    → maybe one line with a filename, otherwise expect <<<<<<<
    function afterOpeningLine(code: Code): State {
        if (markdownLineEnding(code)) { // Blank line – ignore
            fx.consume(code);
            return afterOpeningLine;
        }

        // If this line starts with <<<<<<< SEARCH we can go straight to header
        if (code === codes.lessThan) {
            return parseHeaderStart(code);
        }

        // If we've already seen a filename line, we must have the search marker now
        if (filenameSeen) {
            fx.exit('editBlock');
            return nok(code); // No search marker after filename, not an edit block
        }

        // Otherwise treat **this whole line** as "filename only" (2nd test variant)
        filenameSeen = true;
        fx.enter('editBlockFilename');
        return filenameLine(code);
    }

    function filenameLine(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            fx.consume(code);
            fx.exit('editBlockFilename');
            return afterOpeningLine; // Now next line *must* be header
        }
        // Check if the filename line contains the search marker inline
        if (code === codes.lessThan) {
            fx.exit('editBlockFilename');
            return parseHeaderStart(code);
        }
        fx.consume(code);
        return filenameLine;
    }

    // 3. Delegate to the *existing* header parser logic
    function parseHeaderStart(code: Code): State {
        let count = 0;
        fx.enter('editBlockHead');
        return checkHeadLessThan(code);

        function checkHeadLessThan(code2: Code): State {
            if (code2 === codes.lessThan) {
                fx.consume(code2);
                count++;
                return checkHeadLessThan;
            }
            if (count < 7 || code2 !== codes.space) {
                fx.exit('editBlockHead');
                fx.exit('editBlock');
                return nok(code2);
            }
            fx.consume(code2);
            fx.exit('editBlockHead');
            fx.enter('editBlockSearchKeyword');
            return checkSearchKeyword(0);
        }

        function checkSearchKeyword(idx: number): (c: Code) => State {
            return function (c: Code): State {
                const kw = 'SEARCH';
                if (idx < kw.length) {
                    if (c !== kw.charCodeAt(idx)) return fail(c);
                    fx.consume(c);
                    return checkSearchKeyword(idx + 1);
                }
                return afterSearchKeyword(c);
            };
        }

        function afterSearchKeyword(code3: Code): State {
            if (markdownLineEnding(code3) || code3 === codes.eof) {
                fx.exit('editBlockSearchKeyword');
                fx.enter('editBlockSearchContent');
                return delegateToBody(code3);
            }
            if (code3 === codes.space || code3 === codes.horizontalTab) {
                fx.consume(code3);
                return afterSearchKeyword; // Skip whitespace
            }
            fx.exit('editBlockSearchKeyword');
            fx.enter('editBlockFilename');
            return inFilename(code3);
        }

        function inFilename(code4: Code): State {
            if (markdownLineEnding(code4) || code4 === codes.eof) {
                fx.consume(code4);
                fx.exit('editBlockFilename');
                fx.enter('editBlockSearchContent');
                return delegateToBody(code4);
            }
            fx.consume(code4);
            return inFilename;
        }

        function fail(bad: Code): State {
            fx.exit('editBlockHead');
            fx.exit('editBlock');
            return nok(bad);
        }
    }

    // 4. Hand the heavy lifting to body-tokenizer
    function delegateToBody(code: Code): State {
        return effects.attempt({ tokenize: bodyTokenizer, partial: true }, afterBody, nok)(code);
    }

    // 5. Once bodyTokenizer is done we *must* see the closing fence
    function afterBody(code: Code): State {
        return effects.attempt({ tokenize: closingFenceTokenizer, partial: true }, done, nok)(code);
    }

    function done(code: Code): State {
        fx.exit('editBlock'); // Match the very first enter('editBlock')
        return ok(code);
    }

    // Closing fence tokenizer
    function closingFenceTokenizer (eff, ok2, nok2) {
        // Make sure the very same marker + count close the block
        return closeStart;

        function closeStart(c: Code): State {

            if (markdownLineEnding(c) || c === codes.eof) {
                // respect newlines
                fx.enter('chunk')
                fx.consume(c);
                fx.exit('chunk')
                return closeStart;
            }

            if (c !== fenceMarker) return nok2(c);
            let seen = 0;
            fx.enter('editBlockFenceClose');
            return closeSeq(c);

            function closeSeq(code5: Code): State {
                if (code5 === fenceMarker) {
                    seen++;
                    fx.consume(code5);
                    return closeSeq;
                }
                if (seen < fenceSize) { // Must be >= opening size
                    fx.exit('editBlockFenceClose');
                    return nok2(code5);
                }
                // Optional whitespace till EOL / EOF
                if (!markdownLineEnding(code5) && code5 !== codes.eof) {
                    fx.consume(code5);
                    return closeSeq;
                }
                fx.exit('editBlockFenceClose');
                return ok2(code5);
            }
        }
    }

};
