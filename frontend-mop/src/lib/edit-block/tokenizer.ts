import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx, SafeFx } from './util';

/**
 * Tokenizer for edit blocks.
 */
export const tokenize: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx(effects, ctx);
    return start;

    function start(code: Code): State {
        if (code !== codes.lessThan) return nok(code);
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
                return nok(code);
            }
            if (code !== codes.space) {
                fx.exit('editBlockHead');
                fx.exit('editBlock');
                return nok(code);
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
                    return nok(code);
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
            return searchLineStart(code);        // reuse existing logic
        }

        // 2. Skip one or more spaces/tabs (optional)
        if (code === codes.space || code === codes.tab) {
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
            return searchLineStart(code);
        }
        fx.consume(code);
        return inFilename;
    }

    function afterDividerCheck(code: Code): State {
        fx.exit('editBlockSearchContent');
        // Now consume the divider
        return effects.attempt(
            { tokenize: tokenizeDivider, partial: true },
            afterDividerConsumed,
            nok
        )(code);
    }

    function afterDividerConsumed(code: Code): State {
        fx.enter('editBlockReplaceContent');
        return replaceLineStart(code);
    }

    function afterTailCheck(code: Code): State {
        fx.exit('editBlockReplaceContent');
        // Now consume the tail
        return effects.attempt(
            { tokenize: tokenizeTail, partial: true },
            afterTailConsumed,
            nok
        )(code);
    }

    function afterTailConsumed(code: Code): State {
        fx.exit('editBlock');
        return ok(code);
    }

    // Search content state machine
    function searchLineStart(code: Code): State {
        if (code === codes.eof) {
            return inSearch(code);
        }
        if (markdownLineEnding(code)) {
            // Blank line - emit it as its own empty chunk
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return searchLineStart;
        }
        if (code === codes.equalsTo) {
            // Look-ahead for the divider (=======)
            return effects.check(
                { tokenize: tokenizeDivider, partial: true },
                afterDividerCheck, // Success: transition without including divider
                searchChunkStart // Failure: treat as regular content
            )(code);
        }
        return searchChunkStart(code);
    }

    function searchChunkStart(code: Code): State {
        fx.enter('chunk');
        return searchChunkContinue(code);
    }

    function searchChunkContinue(code: Code): State {
        if (code === codes.eof) {
            fx.exit('chunk');
            return inSearch(code);
        }
        if (markdownLineEnding(code)) {
            fx.consume(code);
            fx.exit('chunk');
            return searchLineStart; // New logical line
        }
        fx.consume(code); // Regular payload
        return searchChunkContinue;
    }

    function inSearch(code: Code): State {
        // This function is reached when we are at EOF, and we haven't found a divider.
        fx.exit('editBlockSearchContent');
        fx.exit('editBlock');
        return ok(code);
    }

    // Replace content state machine
    function replaceLineStart(code: Code): State {
        if (code === codes.eof) {
            return inReplace(code);
        }
        if (markdownLineEnding(code)) {
            // Blank line - emit it as its own empty chunk
            fx.enter('chunk');
            fx.consume(code);
            fx.exit('chunk');
            return replaceLineStart;
        }
        if (code === codes.greaterThan) {
            // Look-ahead for the tail (>>>>>>> REPLACE ...)
            return effects.check(
                { tokenize: tokenizeTail, partial: true },
                afterTailCheck, // Success: transition without including tail
                replaceChunkStart // Failure: treat as regular content
            )(code);
        }
        return replaceChunkStart(code);
    }

    function replaceChunkStart(code: Code): State {
        fx.enter('chunk');
        return replaceChunkContinue(code);
    }

    function replaceChunkContinue(code: Code): State {
        if (code === codes.eof) {
            fx.exit('chunk');
            return inReplace(code);
        }
        if (markdownLineEnding(code)) {
            fx.consume(code);
            fx.exit('chunk');
            return replaceLineStart;
        }
        fx.consume(code);
        return replaceChunkContinue;
    }



    function inReplace(code: Code): State {
        // Reached at EOF without tail.
        fx.exit('editBlockReplaceContent');
        fx.exit('editBlock');
        return ok(code);
    }
};

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
            if (markdownLineEnding(code) || code === codes.eof) {
                fx.exit('editBlockDivider');
                return ok(code);
            }
            // Some other content on divider line.
            fx.exit('editBlockDivider');
            return nok(code);
        };
    }
};

/**
 * Tokenizer for edit block tail.
 */
export const tokenizeTail: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx(effects, ctx);
    return start;

    function start(code: Code): State {
        if (code !== codes.greaterThan) return nok(code);
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
                return nok(code);
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
                return nok(code);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                fx.exit('editBlockTailKeyword');
                fx.exit('editBlockTail');
                return ok(code);
            }
            // Something else on the line.
            fx.exit('editBlockTailKeyword');
            fx.exit('editBlockTail');
            return nok(code);
        };
    }
};
