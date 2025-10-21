package io.github.jbellis.brokk.sync;

import java.util.List;
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
              permits SessionSync.UploadOp, SessionSync.DownloadOp, SessionSync.DeleteLocalOp, SessionSync.DeleteRemoteOp, SessionSync.NoopOp, SessionSync.DecisionRequiredOp {
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
    public SyncPlan {
      ops = List.copyOf(ops);
    }
  }

  private SessionSync() {}
}
