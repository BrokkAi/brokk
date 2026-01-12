package ai.brokk.gui.agents;

import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Manages background agent instances, each running in its own worktree with a headless executor subprocess.
 * Handles agent lifecycle: creation, monitoring, job submission, and shutdown.
 */
public final class AgentManager {
    private static final Logger logger = LogManager.getLogger(AgentManager.class);
    
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 30;
    private static final int HEALTH_CHECK_INTERVAL_MS = 500;
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final MainProject mainProject;
    private final Map<UUID, AgentInstance> agents = new ConcurrentHashMap<>();
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    
    /**
     * Listener for agent state changes.
     */
    public interface AgentListener {
        void onAgentAdded(AgentInstance agent);
        void onAgentStateChanged(AgentInstance agent);
        void onAgentRemoved(AgentInstance agent);
        void onAgentEvent(AgentInstance agent, JsonNode event);
    }
    
    public AgentManager(MainProject mainProject) {
        this.mainProject = mainProject;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            var t = new Thread(r, "AgentManager-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }
    
    public void addListener(AgentListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(AgentListener listener) {
        listeners.remove(listener);
    }
    
    public List<AgentInstance> getAgents() {
        return new ArrayList<>(agents.values());
    }
    
    public @Nullable AgentInstance getAgent(UUID id) {
        return agents.get(id);
    }
    
    /**
     * Create a new agent with a worktree on the specified branch.
     *
     * @param branchName The branch name for the worktree
     * @param createNewBranch If true, create a new branch from HEAD
     * @param sourceBranch Source branch when creating a new branch (ignored if createNewBranch is false)
     * @return The created agent instance
     * @throws IOException If worktree creation or subprocess launch fails
     * @throws GitAPIException If git operations fail
     */
    public AgentInstance createAgent(String branchName, boolean createNewBranch, @Nullable String sourceBranch) 
            throws IOException, GitAPIException {
        IGitRepo repo = mainProject.getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            throw new IOException("Git repository required for agent creation");
        }
        
        // Determine worktree path
        Path worktreesDir = mainProject.getRoot().resolve(".brokk").resolve("agent-worktrees");
        Path worktreePath = worktreesDir.resolve(branchName);
        
        // Create the branch if needed
        if (createNewBranch && sourceBranch != null) {
            gitRepo.createBranch(branchName, sourceBranch);
        }
        
        // Create the worktree
        gitRepo.addWorktree(branchName, worktreePath);
        logger.info("Created worktree for agent at {} on branch {}", worktreePath, branchName);
        
        // Find an available port
        int port = findAvailablePort();
        
        // Generate auth token
        String authToken = UUID.randomUUID().toString();
        
        // Create agent instance
        UUID agentId = UUID.randomUUID();
        var agent = new AgentInstance(agentId, branchName, worktreePath, port, authToken);
        agents.put(agentId, agent);
        
        // Notify listeners
        for (var listener : listeners) {
            try {
                listener.onAgentAdded(agent);
            } catch (Exception e) {
                logger.warn("Error notifying listener of agent added", e);
            }
        }
        
        // Launch subprocess asynchronously
        scheduler.submit(() -> launchAgentSubprocess(agent));
        
        return agent;
    }
    
    /**
     * Launch the headless executor subprocess for an agent.
     */
    private void launchAgentSubprocess(AgentInstance agent) {
        try {
            // Build the command to launch HeadlessExecutorMain
            String javaHome = System.getProperty("java.home");
            String javaBin = Path.of(javaHome, "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");
            
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.add("-cp");
            command.add(classpath);
            command.add("ai.brokk.executor.HeadlessExecutorMain");
            command.add("--exec-id");
            command.add(agent.getId().toString());
            command.add("--listen-addr");
            command.add("127.0.0.1:" + agent.getPort());
            command.add("--auth-token");
            command.add(agent.getAuthToken());
            command.add("--workspace-dir");
            command.add(agent.getWorktreePath().toString());
            
            logger.info("Launching agent subprocess: {}", String.join(" ", command));
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(agent.getWorktreePath().toFile());
            pb.redirectErrorStream(true);
            // Redirect output to a log file
            Path logFile = agent.getWorktreePath().resolve(".brokk").resolve("agent.log");
            logFile.getParent().toFile().mkdirs();
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            
            Process process = pb.start();
            agent.setProcess(process);
            
            logger.info("Agent subprocess started with PID: {}", process.pid());
            
            // Wait for liveness check first (doesn't require session)
            boolean live = waitForHealthLive(agent);
            if (!live) {
                agent.setState(AgentInstance.State.FAILED);
                agent.setLastError("Liveness check timeout - subprocess may have crashed");
                logger.error("Agent {} failed liveness check", agent.getName());
                notifyStateChanged(agent);
                return;
            }
            
            logger.info("Agent {} is live, creating session...", agent.getName());
            
            // Create session (this enables /health/ready)
            boolean sessionCreated = createSession(agent);
            if (!sessionCreated) {
                agent.setState(AgentInstance.State.FAILED);
                agent.setLastError("Failed to create session");
                logger.error("Agent {} failed to create session", agent.getName());
                notifyStateChanged(agent);
                return;
            }
            
            // Now wait for ready (session must exist)
            boolean ready = waitForHealthReady(agent);
            if (ready) {
                agent.setState(AgentInstance.State.READY);
                logger.info("Agent {} is ready", agent.getName());
            } else {
                agent.setState(AgentInstance.State.FAILED);
                agent.setLastError("Ready check timeout after session creation");
                logger.error("Agent {} failed to become ready", agent.getName());
            }
            
            notifyStateChanged(agent);
            
            // Monitor process for unexpected termination
            scheduler.submit(() -> monitorProcess(agent));
            
        } catch (Exception e) {
            logger.error("Failed to launch agent subprocess for {}", agent.getName(), e);
            agent.setState(AgentInstance.State.FAILED);
            agent.setLastError(e.getMessage());
            notifyStateChanged(agent);
        }
    }
    
    /**
     * Wait for the agent's liveness endpoint (doesn't require session).
     */
    private boolean waitForHealthLive(AgentInstance agent) {
        return waitForHealthEndpoint(agent, "/health/live");
    }
    
    /**
     * Wait for the agent's readiness endpoint (requires session).
     */
    private boolean waitForHealthReady(AgentInstance agent) {
        return waitForHealthEndpoint(agent, "/health/ready");
    }
    
    /**
     * Wait for a health endpoint to return 200.
     */
    private boolean waitForHealthEndpoint(AgentInstance agent, String endpoint) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = HEALTH_CHECK_TIMEOUT_SECONDS * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(agent.getBaseUrl() + endpoint))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (Exception e) {
                // Expected during startup, continue polling
                logger.debug("Health check {} not ready yet for agent {}: {}", endpoint, agent.getName(), e.getMessage());
            }
            
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            
            // Check if process died
            Process process = agent.getProcess();
            if (process != null && !process.isAlive()) {
                logger.error("Agent process died during startup");
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Create a session on the agent.
     * @return true if session was created successfully
     */
    private boolean createSession(AgentInstance agent) {
        try {
            String sessionName = "Agent Session - " + agent.getBranchName();
            String body = mapper.writeValueAsString(Map.of("name", sessionName));
            
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(agent.getBaseUrl() + "/v1/sessions"))
                    .header("Authorization", "Bearer " + agent.getAuthToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                JsonNode json = mapper.readTree(response.body());
                String sessionIdStr = json.get("sessionId").asText();
                agent.setSessionId(UUID.fromString(sessionIdStr));
                logger.info("Created session {} for agent {}", sessionIdStr, agent.getName());
                return true;
            } else {
                logger.warn("Failed to create session for agent {}: {} {}", 
                        agent.getName(), response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to create session for agent {}", agent.getName(), e);
            return false;
        }
    }
    
    /**
     * Monitor an agent's subprocess for unexpected termination.
     */
    private void monitorProcess(AgentInstance agent) {
        Process process = agent.getProcess();
        if (process == null) {
            return;
        }
        
        try {
            int exitCode = process.waitFor();
            logger.info("Agent {} process exited with code {}", agent.getName(), exitCode);
            
            if (agent.getState() != AgentInstance.State.SHUTTING_DOWN 
                    && agent.getState() != AgentInstance.State.STOPPED) {
                agent.setState(AgentInstance.State.FAILED);
                agent.setLastError("Process exited unexpectedly with code " + exitCode);
                notifyStateChanged(agent);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Submit a job to an agent.
     *
     * @param agent The agent to submit to
     * @param taskInput The task description
     * @param onEvent Callback for job events
     * @return The job ID if submission succeeded
     */
    public @Nullable String submitJob(AgentInstance agent, String taskInput, Consumer<JsonNode> onEvent) {
        if (!agent.canAcceptJobs()) {
            logger.warn("Agent {} cannot accept jobs in state {}", agent.getName(), agent.getState());
            return null;
        }
        
        try {
            String idempotencyKey = UUID.randomUUID().toString();
            
            Map<String, Object> jobSpec = Map.of(
                    "taskInput", taskInput,
                    "autoCommit", false,
                    "autoCompress", true,
                    "plannerModel", "claude-sonnet-4-20250514",
                    "tags", Map.of("mode", agent.getMode().name())
            );
            
            String body = mapper.writeValueAsString(jobSpec);
            
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(agent.getBaseUrl() + "/v1/jobs"))
                    .header("Authorization", "Bearer " + agent.getAuthToken())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                String jobId = json.get("jobId").asText();
                agent.setCurrentJobId(jobId);
                agent.setState(AgentInstance.State.RUNNING);
                agent.setLastEventSeq(-1);
                notifyStateChanged(agent);
                
                // Start polling for events
                startEventPolling(agent, onEvent);
                
                logger.info("Submitted job {} to agent {}", jobId, agent.getName());
                return jobId;
            } else {
                logger.error("Failed to submit job to agent {}: {} {}", 
                        agent.getName(), response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            logger.error("Error submitting job to agent {}", agent.getName(), e);
            return null;
        }
    }
    
    /**
     * Start polling for job events.
     */
    private void startEventPolling(AgentInstance agent, Consumer<JsonNode> onEvent) {
        scheduler.scheduleWithFixedDelay(() -> {
            String jobId = agent.getCurrentJobId();
            if (jobId == null) {
                return;
            }
            
            try {
                String url = agent.getBaseUrl() + "/v1/jobs/" + jobId + "/events?after=" + agent.getLastEventSeq();
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + agent.getAuthToken())
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonNode json = mapper.readTree(response.body());
                    JsonNode events = json.get("events");
                    long nextAfter = json.get("nextAfter").asLong();
                    
                    if (events != null && events.isArray()) {
                        for (JsonNode event : events) {
                            try {
                                onEvent.accept(event);
                                for (var listener : listeners) {
                                    listener.onAgentEvent(agent, event);
                                }
                            } catch (Exception e) {
                                logger.warn("Error processing event for agent {}", agent.getName(), e);
                            }
                        }
                    }
                    
                    agent.setLastEventSeq(nextAfter);
                }
                
                // Check job status
                checkJobStatus(agent);
                
            } catch (Exception e) {
                logger.debug("Error polling events for agent {}: {}", agent.getName(), e.getMessage());
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Check the current job status.
     */
    private void checkJobStatus(AgentInstance agent) {
        String jobId = agent.getCurrentJobId();
        if (jobId == null) {
            return;
        }
        
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(agent.getBaseUrl() + "/v1/jobs/" + jobId))
                    .header("Authorization", "Bearer " + agent.getAuthToken())
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                String state = json.get("state").asText();
                
                switch (state) {
                    case "COMPLETED" -> {
                        agent.setState(AgentInstance.State.COMPLETED);
                        notifyStateChanged(agent);
                    }
                    case "FAILED" -> {
                        agent.setState(AgentInstance.State.FAILED);
                        JsonNode error = json.get("error");
                        if (error != null) {
                            agent.setLastError(error.asText());
                        }
                        notifyStateChanged(agent);
                    }
                    case "CANCELLED" -> {
                        agent.setState(AgentInstance.State.CANCELLED);
                        notifyStateChanged(agent);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking job status for agent {}: {}", agent.getName(), e.getMessage());
        }
    }
    
    /**
     * Cancel the current job on an agent.
     */
    public void cancelJob(AgentInstance agent) {
        String jobId = agent.getCurrentJobId();
        if (jobId == null) {
            return;
        }
        
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(agent.getBaseUrl() + "/v1/jobs/" + jobId + "/cancel"))
                    .header("Authorization", "Bearer " + agent.getAuthToken())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            logger.info("Sent cancel request for job {} on agent {}", jobId, agent.getName());
        } catch (Exception e) {
            logger.error("Error cancelling job on agent {}", agent.getName(), e);
        }
    }
    
    /**
     * Shutdown an agent and clean up its worktree.
     */
    public void shutdownAgent(AgentInstance agent) {
        agent.setState(AgentInstance.State.SHUTTING_DOWN);
        notifyStateChanged(agent);
        
        // Cancel any running job
        cancelJob(agent);
        
        // Stop the subprocess
        Process process = agent.getProcess();
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if (!terminated) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        
        // Remove the worktree (but keep the branch)
        try {
            IGitRepo repo = mainProject.getRepo();
            if (repo instanceof GitRepo gitRepo) {
                gitRepo.worktrees().removeWorktree(agent.getWorktreePath(), false);
                logger.info("Removed worktree for agent {}", agent.getName());
            }
        } catch (Exception e) {
            logger.error("Failed to remove worktree for agent {}", agent.getName(), e);
        }
        
        agent.setState(AgentInstance.State.STOPPED);
        agents.remove(agent.getId());
        
        for (var listener : listeners) {
            try {
                listener.onAgentRemoved(agent);
            } catch (Exception e) {
                logger.warn("Error notifying listener of agent removed", e);
            }
        }
        
        logger.info("Agent {} has been shut down", agent.getName());
    }
    
    /**
     * Shutdown all agents.
     */
    public void shutdownAll() {
        for (var agent : new ArrayList<>(agents.values())) {
            shutdownAgent(agent);
        }
        scheduler.shutdown();
    }
    
    private void notifyStateChanged(AgentInstance agent) {
        for (var listener : listeners) {
            try {
                listener.onAgentStateChanged(agent);
            } catch (Exception e) {
                logger.warn("Error notifying listener of agent state change", e);
            }
        }
    }
    
    /**
     * Find an available port for the agent's HTTP server.
     */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
