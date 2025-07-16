import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, Effects, State, Tokenizer } from 'micromark-util-types';
import { SafeFx } from './util';

export interface BodyTokenizerOpts {
    divider: Tokenizer;
    tail: Tokenizer;
    makeSafeFx: (effects: Effects, ctx: any) => SafeFx;
}

/**
 * Returns a tokenizer that handles the body of an edit block, starting in search mode
 * and finishing after the tail has been consumed.
 */
export function makeEditBlockBodyTokenizer(
    { divider, tail, makeSafeFx }: BodyTokenizerOpts
): Tokenizer {
    return function tokenizeBody(effects, ok, nok) {
        const ctx = this;
        const fx = makeSafeFx(effects, ctx);

        // Search content state machine
        function searchLineStart(code: Code): State {
            if (code === codes.eof) {
                return inSearch(code);
            }

            if (markdownLineEnding(code) || code === codes.space || code === codes.horizontalTab) {
                // Blank line - emit it as its own empty chunk
                fx.enter('data');
                fx.consume(code);
                fx.exit('data');
                return searchLineStart;
            }
            if (code === codes.equalsTo) {
                // Look-ahead for the divider (=======)
                return effects.check(
                    { tokenize: divider, partial: true },
                    afterDividerCheck, // Success: transition without including divider
                    searchChunkStart // Failure: treat as regular content
                )(code);
            }
            return searchChunkStart(code);
        }

        function searchChunkStart(code: Code): State {
            fx.enter('data');
            return searchChunkContinue(code);
        }

        function searchChunkContinue(code: Code): State {
            if (code === codes.eof) {
                fx.exit('data');
                return inSearch(code);
            }
            if (markdownLineEnding(code)) {
                fx.consume(code);
                fx.exit('data');
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

        function afterDividerCheck(code: Code): State {
            fx.exit('editBlockSearchContent');
            // Now consume the divider
            return effects.attempt(
                { tokenize: divider, partial: true },
                afterDividerConsumed,
                nok
            )(code);
        }

        function afterDividerConsumed(code: Code): State {
            fx.enter('editBlockReplaceContent');
            return replaceLineStart(code);
        }

        // Replace content state machine
        function replaceLineStart(code: Code): State {
            if (code === codes.eof) {
                return inReplace(code);
            }

            if (markdownLineEnding(code) || code === codes.space || code === codes.horizontalTab) {
                // Blank line - emit it as its own empty chunk
                fx.enter('data');
                fx.consume(code);
                fx.exit('data');
                return replaceLineStart;
            }
            if (code === codes.greaterThan) {
                // Look-ahead for the tail (>>>>>>> REPLACE ...)
                return effects.check(
                    { tokenize: tail, partial: true },
                    afterTailCheck, // Success: transition without including tail
                    replaceChunkStart // Failure: treat as regular content
                )(code);
            }
            return replaceChunkStart(code);
        }

        function replaceChunkStart(code: Code): State {
            fx.enter('data');
            return replaceChunkContinue(code);
        }

        function replaceChunkContinue(code: Code): State {
            if (code === codes.eof) {
                fx.exit('data');
                return inReplace(code);
            }
            if (markdownLineEnding(code)) {
                fx.consume(code);
                fx.exit('data');
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

        function afterTailCheck(code: Code): State {
            fx.exit('editBlockReplaceContent');
            // Now consume the tail
            return effects.attempt(
                { tokenize: tail, partial: true },
                afterTailConsumed,
                nok
            )(code);
        }

        function afterTailConsumed(code: Code): State {
            return ok(code);
        }

        // Start in search mode
        return searchLineStart;
    };
}
