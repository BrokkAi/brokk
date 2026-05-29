package ai.brokk.executor.jobs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Best-effort job-backed artifact sink. Persistence failure must not change child-agent execution semantics.
 */
public final class JobChildAgentArtifactSink implements ChildAgentArtifactSink {
    private static final Logger logger = LogManager.getLogger(JobChildAgentArtifactSink.class);

    private final JobStore store;
    private final String parentJobId;

    public JobChildAgentArtifactSink(JobStore store, String parentJobId) {
        this.store = store;
        this.parentJobId = parentJobId;
    }

    @Override
    public void record(ChildAgentArtifact artifact) {
        try {
            store.appendChildAgentArtifact(parentJobId, artifact);
        } catch (Exception e) {
            logger.warn(
                    "Failed to persist child agent artifact for job {} childRunId={}: {}",
                    parentJobId,
                    artifact.childRunId(),
                    e.getMessage(),
                    e);
        }
    }

    @Override
    public String parentJobId() {
        return parentJobId;
    }
}
