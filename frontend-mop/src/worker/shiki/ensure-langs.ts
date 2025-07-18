import {langLoaders} from './shiki-lang-loaders';
import {highlighterPromise} from './shiki-plugin';

const loadedLangs = new Set<string>();
const permanentlyFailed = new Set<string>();
const pendingLoads = new Map<string, Promise<boolean>>();

/**
 * Ensures `langId` is available in the highlighter.
 *
 * • Completely idempotent: call it as many times as you like.
 * • If several calls race on the same lang they all await the same promise.
 * • Returns a promise resolving to `true` if the language was newly loaded, `false` if already loaded or failed.
 */
export function ensureLang(langId: string): Promise<boolean> {
    langId = langId.toLowerCase();

    if (loadedLangs.has(langId) || permanentlyFailed.has(langId)) {
        return Promise.resolve(false);
    }
    const cached = pendingLoads.get(langId);
    if (cached) return cached;

    const p = highlighterPromise.then(async highlighter => {
        if (highlighter.getLoadedLanguages().includes(langId)) {
            loadedLangs.add(langId);
            return false;
        }

        const loader = langLoaders[langId];
        if (!loader) {
            permanentlyFailed.add(langId);
            console.warn('[Shiki] Unsupported language:', langId);
            return false;
        }

        try {
            const mod: any = await loader();
            await highlighter.loadLanguage(mod.default ?? mod);
            loadedLangs.add(langId);
            console.log('[Shiki] Language loaded:', langId);
            return true;
        } catch (e) {
            permanentlyFailed.add(langId);
            console.error('[Shiki] Language load failed:', langId, e);
            return false;
        } finally {
            pendingLoads.delete(langId);
        }
    });

    pendingLoads.set(langId, p);
    return p;
}
