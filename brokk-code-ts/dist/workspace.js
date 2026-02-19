import { existsSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
export function resolveWorkspaceDir(inputPath) {
    const resolved = resolve(inputPath);
    let current = resolved;
    if (existsSync(resolved) && !statSync(resolved).isDirectory()) {
        current = dirname(resolved);
    }
    while (true) {
        const gitDir = `${current}/.git`;
        if (existsSync(gitDir)) {
            return current;
        }
        const parent = dirname(current);
        if (parent === current) {
            return resolved;
        }
        current = parent;
    }
}
