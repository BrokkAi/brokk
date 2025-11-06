package ai.brokk.sessions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe in-memory registry of active sessions.
 */
public class SessionRegistry {
    private final ConcurrentMap<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();

    public void create(SessionInfo sessionInfo) {
        sessions.put(sessionInfo.id(), sessionInfo);
    }

    public @Nullable SessionInfo get(UUID id) {
        return sessions.get(id);
    }

    public List<SessionSummary> list() {
        return sessions.values().stream().map(SessionInfo::toSummary).toList();
    }

    public @Nullable SessionInfo delete(UUID id) {
        return sessions.remove(id);
    }

    public int size() {
        return sessions.size();
    }
}
