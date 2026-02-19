import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";
const MAX_CONTEXTS_JSONL_BYTES = 1024 * 1024;
const MAX_CONTEXTS_JSONL_LINES = 1000;
export function stateDir(workspaceDir) {
    return join(workspaceDir, ".brokk");
}
export function lastSessionFile(workspaceDir) {
    return join(stateDir(workspaceDir), "last_session.json");
}
export async function sessionZipPath(workspaceDir, sessionId) {
    const sessionsDir = join(stateDir(workspaceDir), "sessions");
    await mkdir(sessionsDir, { recursive: true });
    return join(sessionsDir, `${sessionId}.zip`);
}
export async function saveLastSessionId(workspaceDir, sessionId) {
    const path = lastSessionFile(workspaceDir);
    const tmp = `${path}.tmp`;
    await mkdir(stateDir(workspaceDir), { recursive: true });
    await writeFile(tmp, `${JSON.stringify({ sessionId }, null, 2)}\n`, "utf-8");
    await rename(tmp, path);
}
export async function loadLastSessionId(workspaceDir) {
    try {
        const raw = await readFile(lastSessionFile(workspaceDir), "utf-8");
        const data = JSON.parse(raw);
        return typeof data.sessionId === "string" ? data.sessionId : undefined;
    }
    catch {
        return undefined;
    }
}
function findZipEntry(buffer, name) {
    const localFileSig = 0x04034b50;
    let offset = 0;
    while (offset + 30 <= buffer.length) {
        if (buffer.readUInt32LE(offset) !== localFileSig) {
            offset += 1;
            continue;
        }
        const compressionMethod = buffer.readUInt16LE(offset + 8);
        const compressedSize = buffer.readUInt32LE(offset + 18);
        const filenameLength = buffer.readUInt16LE(offset + 26);
        const extraLength = buffer.readUInt16LE(offset + 28);
        const nameStart = offset + 30;
        const nameEnd = nameStart + filenameLength;
        if (nameEnd > buffer.length) {
            return undefined;
        }
        const filename = buffer.subarray(nameStart, nameEnd).toString("utf-8");
        const dataStart = nameEnd + extraLength;
        const dataEnd = dataStart + compressedSize;
        if (dataEnd > buffer.length) {
            return undefined;
        }
        if (filename === name) {
            if (compressionMethod === 0) {
                return buffer.subarray(dataStart, dataEnd);
            }
            if (compressionMethod === 8) {
                return inflateRawSync(buffer.subarray(dataStart, dataEnd));
            }
            return undefined;
        }
        offset = dataEnd;
    }
    return undefined;
}
function lineHasTask(line) {
    const trimmed = line.trim();
    if (!trimmed) {
        return false;
    }
    try {
        const contextData = JSON.parse(trimmed);
        if (!Array.isArray(contextData.tasks)) {
            return false;
        }
        return contextData.tasks.some((task) => {
            if (!task || typeof task !== "object") {
                return false;
            }
            const rec = task;
            const hasMeta = rec.taskType !== undefined ||
                rec.primaryModelName !== undefined ||
                rec.primaryModelReasoning !== undefined;
            return hasMeta && typeof rec.sequence === "number";
        });
    }
    catch {
        return false;
    }
}
export async function hasTasks(zipPath) {
    try {
        const zipData = await readFile(zipPath);
        const contexts = findZipEntry(zipData, "contexts.jsonl");
        if (!contexts) {
            return false;
        }
        const text = contexts.toString("utf-8");
        const lines = text.split(/\r?\n/);
        let bytesRead = 0;
        let linesRead = 0;
        for (const line of lines) {
            linesRead += 1;
            bytesRead += Buffer.byteLength(line, "utf-8");
            if (linesRead > MAX_CONTEXTS_JSONL_LINES || bytesRead > MAX_CONTEXTS_JSONL_BYTES) {
                return false;
            }
            if (lineHasTask(line)) {
                return true;
            }
        }
        return false;
    }
    catch {
        return false;
    }
}
