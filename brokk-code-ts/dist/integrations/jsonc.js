import { rename, writeFile } from "node:fs/promises";
export class ExistingBrokkCodeEntryError extends Error {
}
export function splitLeadingJsonPrefix(text) {
    const idx = text.search(/[\[{]/);
    if (idx < 0) {
        return { prefix: text, jsonText: "" };
    }
    return { prefix: text.slice(0, idx), jsonText: text.slice(idx) };
}
export function stripJsoncComments(text) {
    return text
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/(^|[^:])\/\/.*$/gm, "$1");
}
export function removeTrailingCommas(text) {
    return text.replace(/,\s*([}\]])/g, "$1");
}
export function loadJsonOrJsonc(text) {
    const stripped = text.trim();
    if (!stripped) {
        return {};
    }
    try {
        return JSON.parse(text);
    }
    catch {
        const cleaned = removeTrailingCommas(stripJsoncComments(text));
        return JSON.parse(cleaned);
    }
}
export async function atomicWriteSettings(path, payload) {
    await atomicWriteText(path, `${JSON.stringify(payload, null, 2)}\n`);
}
export async function atomicWriteText(path, text) {
    const tmp = `${path}.${Date.now()}.tmp`;
    await writeFile(tmp, text, "utf-8");
    await rename(tmp, path);
}
