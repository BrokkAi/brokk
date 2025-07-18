import {markdownLineEnding} from 'micromark-util-character';
import {codes} from 'micromark-util-symbol';
import {Code, State, Tokenizer} from 'micromark-util-types';
import {makeEditBlockBodyTokenizer} from './tokenizer/body-tokenizer';
import {tokenizeDivider} from './tokenizer/divider-tokenizer';
import {tokenizeFilename} from './tokenizer/filename';
import {makeTokenizeHeader} from './tokenizer/header';
import {tokenizeTail} from './tokenizer/tail-tokenizer';
import {makeSafeFx} from './util';

// ---------------------------------------------------------------------------
// 1.  Orchestrator for edit block parsing
// ---------------------------------------------------------------------------
export const tokenizeUnfencedEditBlock: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('unfencedEditBlock', effects, ctx, ok, nok);

    function eatEndLineAndCheckEof(code: Code, next: State) {
        if (markdownLineEnding(code)) {
            fx.enter("chunk")
            fx.consume(code);
            fx.exit("chunk")
            return next;
        }
        //eager recognition check
        if (code === codes.eof) {
            return done(code);
        }
        return null;
    }

    // Use the existing body tokenizer for search/replace content, with fence close guard
    const tokenizeBody = makeEditBlockBodyTokenizer({
        divider: tokenizeDivider,
        tail: tokenizeTail
    });

    // Use strict header tokenizer for unfenced blocks to ensure complete header
    const tokenizeHeaderStrict = makeTokenizeHeader({ strict: true });

    return start;

    function start(code: Code): State {
        fx.enter('editBlock');
        const next = eatEndLineAndCheckEof(code, start);
        if (next) return next;

        return effects.attempt(
            { tokenize: tokenizeHeaderStrict, concrete: true },
            afterHeader,
            fx.nok
        )(code);
    }


    function afterHeader(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterHeader)
        if (next) return next;
        return effects.attempt(
            { tokenize: tokenizeBody, concrete: true },
            done,
            fx.nok
        )(code);
    }

    function done(code: Code): State {
        fx.exit('editBlock');
        return fx.ok(code);
    }
};
