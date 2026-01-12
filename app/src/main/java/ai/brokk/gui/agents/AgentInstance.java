package ai.brokk.gui.agents;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a running background agent with its own worktree and headless executor subprocess.
 * Each agent has a dedicated worktree, session, and can execute jobs in various modes.
 */
public final class AgentInstance {
    
    /**
     * Execution modes available for agent jobs.
     * Must match the modes defined in JobRunner.Mode.
     */
    public enum Mode {
        ARCHITECT("Architect", "Full planning and code generation"),
        CODE("Code", "Direct code generation without planning"),
        ASK("Ask", "Read-only question answering"),
        SEARCH("Search", "Repository search and context gathering"),
        REVIEW("Review", "PR/code review mode"),
        LUTZ("Lutz", "Search-driven task decomposition and execution");
        
        private final String displayName;
        private final String description;
        
        Mode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Current state of the agent.
     */
    public enum State {
        STARTING,      // Subprocess launching
        READY,         // Waiting for tasks
        RUNNING,       // Executing a job
        COMPLETED,     // Job completed successfully
        FAILED,        // Job failed
        CANCELLED,     // Job was cancelled
        SHUTTING_DOWN, // Agent is being shut down
        STOPPED        // Agent has been stopped
    }
    
    private final UUID id;
    private final String name;
    private final String branchName;
    private final Path worktreePath;
    private final int port;
    private final String authToken;
    
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ARCHITECT);
    
    private volatile @Nullable Process process;
    private volatile @Nullable UUID sessionId;
    private volatile @Nullable String currentJobId;
    private volatile @Nullable String lastError;
    private volatile long lastEventSeq = -1;
    
    /**
     * Create a new AgentInstance.
     *
     * @param id Unique identifier for this agent
     * @param branchName The git branch name for this agent's worktree
     * @param worktreePath Path to the git worktree directory
     * @param port HTTP port for the headless executor
     * @param authToken Authentication token for the headless executor API
     */
    public AgentInstance(UUID id, String branchName, Path worktreePath, int port, String authToken) {
        this.id = id;
        this.branchName = branchName;
        this.name = "agent-" + branchName;
        this.worktreePath = worktreePath;
        this.port = port;
        this.authToken = authToken;
    }
    
    public UUID getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public String getBranchName() {
        return branchName;
    }
    
    public Path getWorktreePath() {
        return worktreePath;
    }
    
    public int getPort() {
        return port;
    }
    
    public String getAuthToken() {
        return authToken;
    }
    
    public State getState() {
        return state.get();
    }
    
    public void setState(State newState) {
        state.set(newState);
    }
    
    public Mode getMode() {
        return mode.get();
    }
    
    public void setMode(Mode newMode) {
        mode.set(newMode);
    }
    
    public @Nullable Process getProcess() {
        return process;
    }
    
    public void setProcess(@Nullable Process process) {
        this.process = process;
    }
    
    public @Nullable UUID getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(@Nullable UUID sessionId) {
        this.sessionId = sessionId;
    }
    
    public @Nullable String getCurrentJobId() {
        return currentJobId;
    }
    
    public void setCurrentJobId(@Nullable String jobId) {
        this.currentJobId = jobId;
    }
    
    public @Nullable String getLastError() {
        return lastError;
    }
    
    public void setLastError(@Nullable String error) {
        this.lastError = error;
    }
    
    public long getLastEventSeq() {
        return lastEventSeq;
    }
    
    public void setLastEventSeq(long seq) {
        this.lastEventSeq = seq;
    }
    
    /**
     * Get the base URL for the headless executor HTTP API.
     */
    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }
    
    /**
     * Check if the agent is in a terminal state (stopped, failed, etc).
     */
    public boolean isTerminal() {
        var s = state.get();
        return s == State.STOPPED || s == State.FAILED;
    }
    
    /**
     * Check if the agent can accept new jobs.
     */
    public boolean canAcceptJobs() {
        var s = state.get();
        return s == State.READY || s == State.COMPLETED || s == State.CANCELLED;
    }
    
    @Override
    public String toString() {
        return "AgentInstance{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", branch='" + branchName + '\'' +
                ", state=" + state.get() +
                ", port=" + port +
                '}';
    }
}
