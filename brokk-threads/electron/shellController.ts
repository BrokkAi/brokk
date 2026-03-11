import type { InitialShellState, ShellControllerDeps, ThreadMetadata } from "./types";

function pickInitialThreadId(threads: ThreadMetadata[]): string | null {
  if (threads.length === 0) {
    return null;
  }

  const sortedByUpdatedAtDesc = [...threads].sort((a, b) =>
    b.updatedAt.localeCompare(a.updatedAt)
  );

  return sortedByUpdatedAtDesc[0]?.id ?? null;
}

export async function loadInitialShellState(
  deps: ShellControllerDeps
): Promise<InitialShellState> {
  const threads = await deps.metadataStore.readThreadMetadata();
  const selectedThreadId = pickInitialThreadId(threads);

  return {
    threads,
    selectedThreadId
  };
}
