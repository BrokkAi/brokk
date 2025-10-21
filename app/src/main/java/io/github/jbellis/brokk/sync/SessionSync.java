package io.github.jbellis.brokk.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SessionSync {
  public static final record LocalSessionMeta(
              UUID id,
              String name,
              long modified,
              boolean exists,
              boolean unreadable) {
  }

  public static final record RemoteSessionMetaView(
              UUID id,
              String name,
              long modified,
              boolean exists,
              boolean unreadable) {
  }

  public enum Decision {
    COPY_BEFORE_UPDATE,
    COPY_BEFORE_DELETE
  }

  public static final record DecisionInput(UUID sessionId, Decision decision) {
  }

  public sealed interface SyncOp
              permits UploadOp, DownloadOp, DeleteLocalOp, DeleteRemoteOp, NoopOp, DecisionRequiredOp {
  }

  public static final record UploadOp(UUID localId) implements SessionSync.SyncOp {
  }

  public static final record DownloadOp(UUID remoteId) implements SessionSync.SyncOp {
  }

  public static final record DeleteLocalOp(UUID localId) implements SessionSync.SyncOp {
  }

  public static final record DeleteRemoteOp(UUID remoteId) implements SessionSync.SyncOp {
  }

  public static final record NoopOp() implements SessionSync.SyncOp {
  }

  public static final record DecisionRequiredOp(UUID sessionId, String sessionName, Decision decisionType)
              implements SessionSync.SyncOp {
  }

  public static final record SyncPlan(List<SyncOp> ops) {
  }

  public static final record LocalState(
              Map<UUID, LocalSessionMeta> localSessions,
              Set<UUID> openLocalSessionIds,
              Set<UUID> unreadableLocalSessionIds) {
  }

  public static final record RemoteState(
              Map<UUID, RemoteSessionMetaView> remoteSessions) {
  }

  /**
   * Pure planner: produces a deterministic SyncPlan from local state, remote state, and optional user decisions.
   * No I/O, no side effects. Returns ops in deterministic order (sorted by UUID.toString()).
   */
  public static SyncPlan getSyncPlan(
              LocalState localState,
              RemoteState remoteState,
              List<DecisionInput> decisions) {
    var ops = new ArrayList<SyncOp>();
    var decisionMap = decisions.stream()
            .collect(java.util.stream.Collectors.toMap(DecisionInput::sessionId, DecisionInput::decision));

    var allIds = new java.util.TreeSet<String>();
    localState.localSessions().keySet().forEach(id -> allIds.add(id.toString()));
    remoteState.remoteSessions().keySet().forEach(id -> allIds.add(id.toString()));

    for (String idStr : allIds) {
      UUID id = UUID.fromString(idStr);
      LocalSessionMeta local = localState.localSessions().get(id);
      RemoteSessionMetaView remote = remoteState.remoteSessions().get(id);

      boolean localExists = local != null && local.exists();
      boolean remoteExists = remote != null && remote.exists();
      boolean localUnreadable = local != null && (local.unreadable() || localState.unreadableLocalSessionIds().contains(id));
      boolean remoteUnreadable = remote != null && remote.unreadable();
      boolean remoteTombstone = remote != null && !remote.exists();
      boolean localTombstone = local != null && !local.exists();

      // Skip unreadable sessions
      if (localUnreadable || remoteUnreadable) {
        continue;
      }

      // Both missing
      if (local == null && remote == null) {
        continue;
      }

      // Local exists, remote missing
      if (localExists && remote == null) {
        ops.add(new UploadOp(id));
        continue;
      }

      // Remote exists, local missing
      if (remoteExists && local == null) {
        ops.add(new DownloadOp(id));
        continue;
      }

      // Both exist (and readable)
      if (localExists && remoteExists) {
        if (local.modified() > remote.modified()) {
          ops.add(new UploadOp(id));
        } else if (remote.modified() > local.modified()) {
          if (localState.openLocalSessionIds().contains(id)) {
            SyncOp op = new DecisionRequiredOp(id, local.name(), Decision.COPY_BEFORE_UPDATE);
            if (decisionMap.containsKey(id) && decisionMap.get(id) == Decision.COPY_BEFORE_UPDATE) {
              ops.add(new UploadOp(id));
              ops.add(new DownloadOp(id));
            } else {
              ops.add(op);
            }
          } else {
            ops.add(new DownloadOp(id));
          }
        }
        // else: timestamps equal, no op
        continue;
      }

      // Remote tombstone
      if (remoteTombstone) {
        if (localExists) {
          if (localState.openLocalSessionIds().contains(id)) {
            SyncOp op = new DecisionRequiredOp(id, local.name(), Decision.COPY_BEFORE_DELETE);
            if (decisionMap.containsKey(id) && decisionMap.get(id) == Decision.COPY_BEFORE_DELETE) {
              ops.add(new UploadOp(id));
            } else {
              ops.add(op);
            }
          } else {
            ops.add(new DeleteLocalOp(id));
          }
        }
        continue;
      }

      // Local tombstone, remote exists
      if (localTombstone && remoteExists) {
        ops.add(new DownloadOp(id));
      }
    }

    return new SyncPlan(List.copyOf(ops));
  }

  private SessionSync() {}
}
