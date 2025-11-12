export const currentExpandIds = new Set<string>();

// Tracks edit-block IDs that the user explicitly collapsed.
// Used to suppress auto-expansion across reparses in the worker.
export const userCollapsedIds = new Set<string>();
