package io.github.jbellis.brokk.sync;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.sync.SessionSync.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SessionSyncTest {

  private static LocalSessionMeta lm(UUID id, String name, long modified, boolean exists, boolean unreadable) {
    return new LocalSessionMeta(id, name, modified, exists, unreadable);
  }

  private static RemoteSessionMetaView rm(UUID id, String name, long modified, boolean exists, boolean unreadable) {
    return new RemoteSessionMetaView(id, name, modified, exists, unreadable);
  }

  private static LocalState ls(Map<UUID, LocalSessionMeta> locals, Set<UUID> open, Set<UUID> unreadable) {
    return new LocalState(locals, open, unreadable);
  }

  private static RemoteState rs(Map<UUID, RemoteSessionMetaView> remotes) {
    return new RemoteState(remotes);
  }

  @Test
  void uploadOnly_localOnly() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    LocalState local = ls(Map.of(id, lm(id, "L1", 10L, true, false)), Set.of(), Set.of());
    RemoteState remote = rs(Map.of());
    var plan = SessionSync.getSyncPlan(local, remote, List.of());
    assertEquals(List.of(new UploadOp(id)), plan.ops());
  }
}
