package io.github.jbellis.brokk;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Static registry to track which session is active in each worktree within the current JVM.
 * This prevents the same session from being opened in multiple worktrees simultaneously.
 */
public final class SessionRegistry {
    // key = worktree root path, value = currently-active session UUID
    private static final ConcurrentMap<Path, UUID> activeSessions = new ConcurrentHashMap<>();

    private SessionRegistry() {
        // Utility class
    }

    /**
     * Attempts to claim a session for the given worktree.
     * 
     * @param worktree The worktree root path
     * @param sessionId The session UUID to claim
     * @return true if the session was successfully claimed, false if this worktree already has an active session
     */
    public static boolean claim(Path worktree, UUID sessionId) {
        return activeSessions.putIfAbsent(worktree, sessionId) == null;
    }

    /**
     * Releases the active session for the given worktree.
     * 
     * @param worktree The worktree root path
     */
    public static void release(Path worktree) {
        activeSessions.remove(worktree);
    }

    /**
     * Checks if the given session is active in any worktree other than the current one.
     * 
     * @param currentWorktree The current worktree root path (to exclude from the check)
     * @param sessionId The session UUID to check
     * @return true if the session is active elsewhere, false otherwise
     */
    public static boolean isSessionActiveElsewhere(Path currentWorktree, UUID sessionId) {
        return activeSessions.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(currentWorktree) && e.getValue().equals(sessionId));
    }

    /**
     * Updates the session for a given worktree. This is used when switching sessions.
     * 
     * @param worktree The worktree root path
     * @param sessionId The new session UUID, or null to clear
     */
    public static void update(Path worktree, UUID sessionId) {
        if (sessionId == null) {
            activeSessions.remove(worktree);
        } else {
            activeSessions.put(worktree, sessionId);
        }
    }
}
