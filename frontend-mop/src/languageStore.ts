import { writable } from 'svelte/store';
import { ensureLang } from './shiki-plugin';

export const loadedLangs = writable<Set<string>>(new Set());

/**
 * Ensures a language is loaded into Shiki and tracks its state.
 * This function is idempotent and can be called multiple times for the same language.
 * @param lang The language identifier to load.
 */
export async function ensureAndTrack(lang: string) {
  const lowerLang = lang.toLowerCase();
  // `ensureLang` is already idempotent, so we can call it without checking.
  await ensureLang(lowerLang);

  loadedLangs.update(set => {
    if (set.has(lowerLang)) {
      return set; // Avoid triggering updates if language is already in the set.
    }
    const newSet = new Set(set);
    newSet.add(lowerLang);
    return newSet;
  });
}
