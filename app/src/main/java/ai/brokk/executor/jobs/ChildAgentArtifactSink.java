package ai.brokk.executor.jobs;

/**
 * Receiver for durable schema-aware custom-agent child artifacts.
 */
public interface ChildAgentArtifactSink {
    void record(ChildAgentArtifact artifact);

    String parentJobId();

    static ChildAgentArtifactSink noop() {
        return NoopChildAgentArtifactSink.INSTANCE;
    }

    enum NoopChildAgentArtifactSink implements ChildAgentArtifactSink {
        INSTANCE;

        @Override
        public void record(ChildAgentArtifact artifact) {}

        @Override
        public String parentJobId() {
            return "";
        }
    }
}
