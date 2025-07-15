import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Extension, TokenizeContext, Tokenizer, Construct, State, Code } from 'micromark-util-types';

/**
 * Micromark extension to detect edit blocks in Markdown text.
 * Recognizes unfenced edit blocks starting with "<<<<<<< SEARCH [filename]".
 */
export function gfmEditBlock(): Extension {
    return { flow: { [codes.lessThan]: editBlock } };
}

let _context: TokenizeContext
let _effects: any

let text;
function dbg(msg: string, code?: number) {
    const txt1 = `[micromark-edit-block] ${msg} at line ${_context.now().line}, col ${_context.now().column}`;
    const txt2 = code !== undefined ? `char: ${String.fromCharCode(code)}` : '';
    text += txt1 + ' ' + txt2 + '\n';
    console.log(txt1, txt2);
}

function safeConsume(code: number) {
    dbg('consume', code);
    _effects.consume(code);
}

function safeEnter(name: string) {
    dbg('enter ' + name);
    _effects.enter(name);
}

function safeExit(name: string) {
    dbg('exit  ' + name);
    _effects.exit(name);
}

const tokenize: Tokenizer = function (effects, ok, nok) {
    _context = this;
    _effects = effects;
    return start;

    function start(code: Code): State {
        if (code !== codes.lessThan) return nok(code);

        safeEnter('editBlock');
        safeEnter('editBlockHead');
        safeConsume(code);
        return checkHeadLessThan(1);
    }

    function checkHeadLessThan(count: number): State {
        return function(code: Code): State {
            if (code === codes.lessThan) {
                safeConsume(code);
                return checkHeadLessThan(count + 1);
            }
            if (count < 7) {
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

    function checkSearchKeyword(index: number): State {
        return function(code: Code): State {
            const keyword = 'SEARCH';
            if (index < keyword.length) {
                if (code !== keyword.charCodeAt(index)) {
                    safeExit('editBlockSearchKeyword');
                    safeExit('editBlock');
                    return nok(code);
                }
                safeConsume(code);
                return checkSearchKeyword(index + 1);
            }
            safeConsume(code);
            safeExit('editBlockSearchKeyword');
            safeEnter('editBlockFilename');
            return inFilename;
        };
    }

    function inFilename(code: Code): State {
        if (markdownLineEnding(code) || code === codes.eof) {
            safeExit('editBlockFilename');
            safeEnter('editBlockSearchContent');
            return content(inSearch, afterDividerCheck)(code);
        }
        safeConsume(code);
        return inFilename;
    }

    function afterDividerCheck(code: Code): State {
        safeExit('editBlockSearchContent');
        // Now consume the divider
        return effects.attempt({tokenize: tokenizeDivider, partial: true}, 
            afterDividerConsumed, 
            nok)(code);
    }

    function afterDividerConsumed(code: Code): State {
        safeEnter('editBlockReplaceContent');
        return content(inReplace, afterTailCheck)(code);
    }

    function afterTailCheck(code: Code): State {
        safeExit('editBlockReplaceContent');
        // Now consume the tail
        return effects.attempt({tokenize: tokenizeTail, partial: true},
            afterTailConsumed,
            nok)(code);
    }

    function afterTailConsumed(code: Code): State {
        safeExit('editBlock');
        return ok(code);
    }

    function content(next: State, transitionCheck: State): (code: Code) => State {
        const contentChunk: State = (code) => {
            safeEnter('chunk');
            return contentContinue(code);
        };

        const contentContinue: State = (code) => {
            if (code === codes.eof) {
                safeExit('chunk');
                return next(code);
            }
            if (markdownLineEnding(code)) {
                safeConsume(code); // Consume newline while chunk is still open
                safeExit('chunk');
                return content(next, transitionCheck);
            }
            safeConsume(code);
            return contentContinue;
        }

        return function(code: Code): State {
            if (code === codes.eof) {
                return next(code);
            }
            if (markdownLineEnding(code)) {
                // Consume the line ending as part of content
                safeEnter('chunk');
                safeConsume(code);
                safeExit('chunk');
                return content(next, transitionCheck);
            }

            // Check for markers at the start of a line
            if (next === inSearch && code === codes.equalsTo) {
                // Try to parse divider without consuming it yet
                return effects.check({tokenize: tokenizeDivider, partial: true}, 
                    transitionCheck,  // Success: transition without including divider
                    contentChunk      // Failure: treat as regular content
                )(code);
            }
            if (next === inReplace && code === codes.greaterThan) {
                // Try to parse tail without consuming it yet
                return effects.check({tokenize: tokenizeTail, partial: true},
                    transitionCheck,  // Success: transition without including tail
                    contentChunk      // Failure: treat as regular content
                )(code);
            }

            return contentChunk(code);
        }
    }

    function inSearch(code: Code): State {
        // This function is reached when we are at EOF, and we haven't found a divider.
        safeExit('editBlockSearchContent');
        safeExit('editBlock');
        return ok(code);
    }

    function inReplace(code: Code): State {
        // Reached at EOF without tail.
        safeExit('editBlockReplaceContent');
        safeExit('editBlock');
        return ok(code);
    }
};

const tokenizeDivider: Tokenizer = function(effects, ok, nok) {
    return start;

    function start(code: Code): State {
        if (code !== codes.equalsTo) return nok(code);
        safeEnter('editBlockDivider');
        safeConsume(code);
        return sequence(1);
    }

    function sequence(count: number): State {
        return function(code: Code): State {
            if (code === codes.equalsTo) {
                safeConsume(code);
                return sequence(count + 1);
            }
            if (count < 7) {
                safeExit('editBlockDivider');
                return nok(code);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                safeExit('editBlockDivider');
                return ok(code);
            }
            // Some other content on divider line.
            safeExit('editBlockDivider');
            return nok(code);
        };
    }
};

const tokenizeTail: Tokenizer = function(effects, ok, nok) {
    return start;

    function start(code: Code): State {
        if (code !== codes.greaterThan) return nok(code);
        safeEnter('editBlockTail');
        safeConsume(code);
        return sequence(1);
    }

    function sequence(count: number): State {
        return function(code: Code): State {
            if (code === codes.greaterThan) {
                safeConsume(code);
                return sequence(count + 1);
            }
            if (count < 7) {
                safeExit('editBlockTail');
                return nok(code);
            }
            if (code === codes.space) {
                safeConsume(code);
            }
            safeEnter('editBlockTailKeyword');
            return keyword(0);
        };
    }

    function keyword(index: number): State {
        return function(code: Code): State {
            const keywordText = 'REPLACE';
            if (index < keywordText.length) {
                if (code === keywordText.charCodeAt(index)) {
                    safeConsume(code);
                    return keyword(index + 1);
                }
                safeExit('editBlockTailKeyword');
                safeExit('editBlockTail');
                return nok(code);
            }
            if (markdownLineEnding(code) || code === codes.eof) {
                safeExit('editBlockTailKeyword');
                safeExit('editBlockTail');
                return ok(code);
            }
            // Something else on the line.
            safeExit('editBlockTailKeyword');
            safeExit('editBlockTail');
            return nok(code);
        };
    }
};


/**
 * mdast build logic for edit-blocks.
 */
export function editBlockFromMarkdown() {
    return {
        enter: {
            // Create the node and remember it.
            editBlock(tok) {
                console.log('enter editBlock');
                const node = {
                    type: 'editBlock',
                    data: {
                        filename: undefined as string | undefined,
                        search: undefined as string | undefined,
                        replace: undefined as string | undefined
                    }
                };
                this.enter(node, tok);
                this.data.currentEditBlock = node; // store a reference
            },

            // Filename
            editBlockFilename() {
                console.log('enter editBlockFilename');
                //this.buffer(); // start collecting raw filename
            },

            // Search text
            editBlockSearchContent() {
                console.log('enter editBlockSearchContent');
                //this.buffer(); // start collecting *search* text
            },

            // Replace text
            editBlockReplaceContent() {
                console.log('enter editBlockReplaceContent');
                // this.buffer(); // start collecting *replace* text
            }
        },
        exit: {
            editBlockFilename(tok) {
                console.log('exit editBlockFilename');
                const node = this.data.currentEditBlock;
                node.data.filename = this.sliceSerialize(tok);
            },

            editBlockSearchContent(tok) {
                console.log('exit editBlockSearchContent');
                const node = this.data.currentEditBlock;
                node.data.search = this.sliceSerialize(tok);
            },

            editBlockReplaceContent(tok) {
                console.log('exit editBlockReplaceContent');
                const node = this.data.currentEditBlock;
                node.data.replace = this.sliceSerialize(tok);
            },

            editBlock(tok) {
                console.log('exit editBlock');
                delete this.data['currentEditBlock']; // clear helper
                this.exit(tok); // close the node
            }
        },
    };
}

/**
 * Define the edit block construct as a container.
 */
const editBlock: Construct = {
    name: 'editBlock',
    tokenize,
    concrete: true
};
