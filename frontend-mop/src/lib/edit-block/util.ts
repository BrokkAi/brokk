import type {Code, Effects, State, TokenizeContext} from 'micromark-util-types';

/**
 * Debug logging function for edit block parsing.
 */
let loggingEnabled = true;
let contextInfo = '';

export function log(msg: any, newLine = false): void {
    if (!loggingEnabled) return;
    console.log(`[${contextInfo}] `, msg);
    if (newLine) console.log('');
}

export function dbg(msg: string, code?: number, context?: TokenizeContext, newLine = false): void {
    if (!loggingEnabled) return;
    const txt1 = `[${contextInfo}] ${msg}${context ? ` at line ${context.now().line}, col ${context.now().column}` : ''}`;
    const txt2 = code !== undefined ? `char: ${String.fromCharCode(code)}` : '';
    console.log(txt1, txt2);
    if (newLine) console.log('');
}

/**
 * Type definition for safe effects operations.
 */
export type SafeFx = {
    consume: (c: Code) => void;
    enter: (name: string) => void;
    exit: (name: string) => void;
    ok: (c: Code) => State;
    nok: (c: Code) => State;
};

/**
 * Factory function to create safe effects operations bound to a specific effects instance.
 */
let lastExit = ''
export function makeSafeFx(info: string, effects: Effects, ctx: TokenizeContext, ok: State, nok: State): SafeFx {
    contextInfo = info;

    if (loggingEnabled && !(effects as any)._isPatched) {
        const origCheck = effects.check.bind(effects);
        effects.check = function (construct: any, ok2, nok2) {
            log(`--- LOOK AHEAD CHECK START ${construct.tokenize.prototype.constructor.name}  ---`);
            const wrappedOk = (code: Code) => {
                log('--- LOOK AHEAD CHECK END (OK) ---');
                return ok2(code);
            };
            const wrappedNok = (code: Code) => {
                log('--- LOOK AHEAD CHECK END (NOK) ---');
                return nok2(code);
            };
            return origCheck(construct, wrappedOk, wrappedNok);
        };
        (effects as any)._isPatched = true;
    }


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
            lastExit = name;
            dbg('exit  ' + name, undefined, ctx);
            effects.exit(name as any);
        },
        ok(code): State {
            dbg('ok', code, ctx, true);
            if (lastExit === "editBlock") {
                //console.log('ok', code, ctx);
            }
            return ok(code);
        },
        nok(code): State {
            dbg('nok', code, ctx, true);
            if (lastExit === "editBlock") {
                //console.log('nok', code, ctx);
            }
            return nok(code);
        }
    };
}
