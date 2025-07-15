import {markdownLineEnding} from 'micromark-util-character';
import {codes} from 'micromark-util-symbol';
import type {Extension, TokenizeContext, Tokenizer} from 'micromark-util-types';

import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Extension, TokenizeContext, Tokenizer, Construct } from 'micromark-util-types';

/**
 * Micromark extension to detect edit blocks in Markdown text.
 * Recognizes unfenced edit blocks starting with "<<<<<<< SEARCH [filename]".
 */
export function gfmEditBlock(): Extension {
    return { flow: { [codes.lessThan]: editBlock } };
}



/**
 * Initial tokenizer for edit block syntax.
 * Detects the pattern: "<<<<<<< SEARCH [filename]".
 */
let text = '';
const initialTokenize: Tokenizer = function (effects, ok, nok) {
    const self = this;

    // Debug wrappers to log enter, exit, and consume operations
    function dbg(msg: string, code?: number) {
        const txt1 = `[micromark-edit-block] ${msg} at line ${self.now().line}, col ${self.now().column}`;
        const txt2 = code !== undefined ? `char: ${String.fromCharCode(code)}` : '';
        console.log(txt1, txt2);
        text += txt1 + ' ' + txt2 + '\n';
    }

    function safeConsume(code: number) {
        dbg('consume', code);
        effects.consume(code);
    }

    function safeEnter(name: string) {
        dbg('enter ' + name);
        effects.enter(name);
    }

    function safeExit(name: string) {
        dbg('exit  ' + name);
        effects.exit(name);
    }

    return start;

    function start(code: number): any {
        if (code !== codes.lessThan) return nok(code);
        safeEnter('editBlock');
        safeEnter('editBlockHead');
        safeConsume(code);
        let count = 1;
        return checkHeadLessThan(count);
    }

    function checkHeadLessThan(count: number): any {
        return function(code: number): any {
            if (code === codes.lessThan) {
                safeConsume(code);
                return checkHeadLessThan(count + 1);
            }
            if (count < 6) {
                safeExit('editBlockHead');
                safeExit('editBlock');
                return nok(code);
            }
            if (code !== codes.space) {
                safeExit('editBlockHead');
                safeExit('editBlock');
                return nok(code);
            }
            safeConsume(code);
            safeExit('editBlockHead');
            safeEnter('editBlockSearchKeyword');
            return checkSearchKeyword(0);
        };
    }

    function checkSearchKeyword(index: number): any {
        return function(code: number): any {
            const keyword = 'SEARCH';
            if (index < keyword.length) {
                if (code !== keyword.charCodeAt(index)) {
                    safeExit('editBlockSearchKeyword');
                    safeExit('editBlock');
                    return nok(code);
                }
                safeConsume(code);
                if (index === keyword.length - 1) {
                    safeExit('editBlockSearchKeyword');
                    safeEnter('editBlockFilename');
                }
                return checkSearchKeyword(index + 1);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                safeConsume(code);
                safeExit('editBlockFilename');
                safeEnter('editBlockSearchContent');
                // Initialize container state
                self.containerState = { phase: 'search' };
                return inSearch;
            }
            safeConsume(code);
            return checkSearchKeyword(index);
        };
    }

    function inSearch(code: number): any {
        if (code === codes.equalsTo) {
            safeEnter('editBlockDivider');
            safeConsume(code);
            return checkDivider(1);
        }
        safeConsume(code);
        return inSearch;
    }

    function checkDivider(count: number): any {
        return function(code: number): any {
            if (code === codes.equalsTo) {
                safeConsume(code);
                return checkDivider(count + 1);
            }
            if (count < 6) {
                safeExit('editBlockDivider');
                safeExit('editBlockSearchContent');
                safeExit('editBlock');
                return nok(code);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                safeConsume(code);
                safeExit('editBlockDivider');
                safeExit('editBlockSearchContent');
                safeEnter('editBlockReplaceContent');
                self.containerState.phase = 'replace';
                return inReplace;
            }
            safeConsume(code);
            return checkDivider(count);
        };
    }

    function inReplace(code: number): any {
        if (code === codes.greaterThan) {
            safeEnter('editBlockTail');
            safeConsume(code);
            return checkTailGreaterThan(1);
        }
        safeConsume(code);
        return inReplace;
    }

    function checkTailGreaterThan(count: number): any {
        return function(code: number): any {
            if (code === codes.greaterThan) {
                safeConsume(code);
                return checkTailGreaterThan(count + 1);
            }
            if (count < 6) {
                safeExit('editBlockTail');
                safeExit('editBlockReplaceContent');
                safeExit('editBlock');
                return nok(code);
            }
            safeConsume(code);
            safeEnter('editBlockTailKeyword');
            return checkTailKeyword(0);
        };
    }

    function checkTailKeyword(index: number): any {
        return function(code: number): any {
            const keyword = 'REPLACE';
            if (index < keyword.length) {
                if (code !== keyword.charCodeAt(index)) {
                    safeExit('editBlockTailKeyword');
                    safeExit('editBlockTail');
                    safeExit('editBlockReplaceContent');
                    safeExit('editBlock');
                    return nok(code);
                }
                safeConsume(code);
                return checkTailKeyword(index + 1);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                safeConsume(code);
                safeExit('editBlockTailKeyword');
                safeExit('editBlockTail');
                safeExit('editBlockReplaceContent');
                safeExit('editBlock');
                self.containerState.phase = 'done';
                return ok;
            }
            safeConsume(code);
            return checkTailKeyword(index);
        };
    }
};

/**
 * Resolver to map micromark tokens to mdast nodes for edit blocks.
 */
export function editBlockFromMarkdown() {
    return {
        enter: {
            editBlock(tok: any) {
                this.enter({type: 'editBlock', data: {}}, tok);
            },
        },
        exit: {
            editBlock(tok: any) {
                this.exit(tok);
            },
        },
    };
}

/**
 * Define the edit block construct as a container.
 */
const editBlock: Construct = {
    name: 'editBlock',
    tokenize: initialTokenize,
    // continuation: {
    //     tokenize: continuationTokenize
    // },
    exit: function(this: TokenizeContext, effects: any) {
        // Optional cleanup or final token emission if needed
        effects.exit('editBlock');
    },
    concrete: true
};
