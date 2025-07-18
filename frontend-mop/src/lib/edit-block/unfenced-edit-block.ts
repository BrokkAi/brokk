import {markdownLineEnding} from 'micromark-util-character';
import {codes} from 'micromark-util-symbol';
import {Code, State, Tokenizer} from 'micromark-util-types';
import {makeEditBlockBodyTokenizer} from './tokenizer/body-tokenizer';
import {tokenizeDivider} from './tokenizer/divider-tokenizer';
import {tokenizeFilename} from './tokenizer/filename';
import {tokenizeHeader} from './tokenizer/header';
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

    return start;


    function start(code: Code): State {
        fx.enter('editBlock');
        const next = eatEndLineAndCheckEof(code, start);
        if (next) return next;

        //Filename is optional, so we need to check for the header first

        return effects.check(
            { tokenize: tokenizeHeader, concrete: true },
            parseHeader,
            parseFilename
        )(code);
    }

    function parseFilename(code: Code): State {
        return effects.attempt(
            { tokenize: tokenizeFilename, concrete: true },
            afterFilename,
            fx.nok // Fail on any real error in filename parsing
        )(code);
    }

    function parseHeader(code: Code): State {
        return effects.attempt(
            { tokenize: tokenizeHeader, concrete: true },
            afterHeader,
            fx.nok
        )(code);
    }

    function afterFilename(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterFilename)
        if (next) return next;
        return effects.attempt(
            { tokenize: tokenizeHeader, concrete: true },
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
