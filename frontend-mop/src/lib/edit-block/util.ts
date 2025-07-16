import type { Code, Effects, TokenizeContext } from 'micromark-util-types';

/**
 * Debug logging function for edit block parsing.
 */
function dbg(msg: string, code?: number, context?: TokenizeContext) {
    const txt1 = `[micromark-edit-block] ${msg}${context ? ` at line ${context.now().line}, col ${context.now().column}` : ''}`;
    const txt2 = code !== undefined ? `char: ${String.fromCharCode(code)}` : '';
    console.log(txt1, txt2);
}

/**
 * Type definition for safe effects operations.
 */
export type SafeFx = {
    consume: (c: Code) => void;
    enter: (name: string) => void;
    exit: (name: string) => void;
};

/**
 * Factory function to create safe effects operations bound to a specific effects instance.
 */
export function makeSafeFx(effects: Effects, ctx: TokenizeContext): SafeFx {
    return {
        consume(code) {
            dbg('consume', code, ctx);
            effects.consume(code);
        },
        enter(name) {
            dbg('enter ' + name, undefined, ctx);
            effects.enter(name as any);
        },
        exit(name) {
            dbg('exit  ' + name, undefined, ctx);
            effects.exit(name as any);
        }
    };
}
