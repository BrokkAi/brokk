import { homedir } from "node:os";
import { formatTokenCount } from "../tokenFormat.js";
const SEPARATOR = " • ";
function normalizeSlashes(pathValue) {
    return pathValue.replace(/\\/g, "/");
}
export function normalizeWorkspaceDisplay(workspace, homeDir = homedir()) {
    if (workspace === "unknown") {
        return workspace;
    }
    try {
        const normalizedWorkspace = normalizeSlashes(workspace);
        const normalizedHome = normalizeSlashes(homeDir);
        if (normalizedWorkspace === normalizedHome) {
            return "~";
        }
        if (normalizedWorkspace.startsWith(`${normalizedHome}/`)) {
            return `~/${normalizedWorkspace.slice(normalizedHome.length + 1)}`;
        }
        return normalizedWorkspace;
    }
    catch {
        return normalizeSlashes(workspace);
    }
}
export function formatStatusMetadata(metadata) {
    const workspaceDisplay = normalizeWorkspaceDisplay(metadata.workspace);
    return [
        String(metadata.mode),
        `${String(metadata.model)} (${String(metadata.reasoning)})`,
        workspaceDisplay,
        String(metadata.branch)
    ].join(SEPARATOR);
}
export function formatFragmentStatus(description, sizeTokens) {
    const compact = formatTokenCount(sizeTokens);
    return `${description} (${compact} tokens)`;
}
