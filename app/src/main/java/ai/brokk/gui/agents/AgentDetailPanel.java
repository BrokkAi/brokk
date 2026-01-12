package ai.brokk.gui.agents;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.mop.webview.MOPWebViewHost;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detail panel for a single agent showing mode selector, prompt input, and embedded MOP output.
 */
public class AgentDetailPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(AgentDetailPanel.class);
    
    private final Chrome chrome;
    private final AgentManager agentManager;
    private final AgentInstance agent;
    
    private final JComboBox<AgentInstance.Mode> modeCombo;
    private final JTextArea promptArea;
    private final MaterialButton runButton;
    private final MaterialButton stopButton;
    private final JLabel statusLabel;
    private final MOPWebViewHost mopHost;
    
    public AgentDetailPanel(Chrome chrome, AgentManager agentManager, AgentInstance agent) {
        super(new BorderLayout(5, 5));
        this.chrome = chrome;
        this.agentManager = agentManager;
        this.agent = agent;
        
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        // Top panel with agent info and status
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        
        // Agent info
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        infoPanel.add(new JLabel("Agent:"));
        JLabel nameLabel = new JLabel(agent.getName());
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        infoPanel.add(nameLabel);
        infoPanel.add(new JLabel("Branch:"));
        infoPanel.add(new JLabel(agent.getBranchName()));
        
        // Status indicator
        statusLabel = new JLabel(agent.getState().name());
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        updateStatusColor();
        infoPanel.add(Box.createHorizontalStrut(20));
        infoPanel.add(new JLabel("Status:"));
        infoPanel.add(statusLabel);
        
        topPanel.add(infoPanel, BorderLayout.CENTER);
        
        // Mode selector panel
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        modePanel.add(new JLabel("Mode:"));
        modeCombo = new JComboBox<>(AgentInstance.Mode.values());
        modeCombo.setSelectedItem(agent.getMode());
        modeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AgentInstance.Mode mode) {
                    setText(mode.getDisplayName());
                    setToolTipText(mode.getDescription());
                }
                return this;
            }
        });
        modeCombo.addActionListener(e -> {
            AgentInstance.Mode selectedMode = (AgentInstance.Mode) modeCombo.getSelectedItem();
            if (selectedMode != null) {
                agent.setMode(selectedMode);
            }
        });
        modePanel.add(modeCombo);
        topPanel.add(modePanel, BorderLayout.SOUTH);
        
        add(topPanel, BorderLayout.NORTH);
        
        // Center panel with MOP output
        mopHost = new MOPWebViewHost();
        String themeName = MainProject.getTheme();
        boolean wrapMode = MainProject.getCodeBlockWrapMode();
        boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
        mopHost.setRuntimeTheme(themeName, isDevMode, wrapMode);
        
        JPanel mopPanel = new JPanel(new BorderLayout());
        mopPanel.setBorder(BorderFactory.createTitledBorder("Output"));
        mopPanel.add(mopHost, BorderLayout.CENTER);
        mopPanel.setPreferredSize(new Dimension(400, 300));
        
        add(mopPanel, BorderLayout.CENTER);
        
        // Bottom panel with prompt input and run button
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createTitledBorder("Task"));
        
        promptArea = new JTextArea(4, 40);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        bottomPanel.add(promptScroll, BorderLayout.CENTER);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        
        runButton = new MaterialButton("Run");
        runButton.setIcon(Icons.PLAY);
        SwingUtil.applyPrimaryButtonStyle(runButton);
        runButton.addActionListener(e -> submitTask());
        
        stopButton = new MaterialButton("Stop");
        stopButton.setIcon(Icons.STOP);
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopTask());
        
        buttonPanel.add(stopButton);
        buttonPanel.add(runButton);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Initial state
        updateButtonStates();
    }
    
    private void submitTask() {
        String task = promptArea.getText().trim();
        if (task.isEmpty()) {
            chrome.toolError("Please enter a task description", "Empty Task");
            return;
        }
        
        if (!agent.canAcceptJobs()) {
            chrome.toolError("Agent is not ready to accept jobs", "Agent Not Ready");
            return;
        }
        
        // Clear previous output
        mopHost.clear();
        
        // Submit the job
        String jobId = agentManager.submitJob(agent, task, this::handleEvent);
        if (jobId != null) {
            logger.info("Submitted job {} to agent {}", jobId, agent.getName());
            updateButtonStates();
        } else {
            chrome.toolError("Failed to submit job to agent", "Job Submission Error");
        }
    }
    
    private void stopTask() {
        agentManager.cancelJob(agent);
    }
    
    /**
     * Handle an event from the agent's job execution.
     */
    public void handleEvent(JsonNode event) {
        if (event == null) {
            return;
        }
        
        String type = event.has("type") ? event.get("type").asText() : "";
        JsonNode data = event.get("data");
        
        SwingUtilities.invokeLater(() -> {
            switch (type) {
                case "LLM_TOKEN" -> {
                    if (data != null) {
                        String token = data.has("token") ? data.get("token").asText() : "";
                        boolean isNew = data.has("isNewMessage") && data.get("isNewMessage").asBoolean();
                        boolean isReasoning = data.has("isReasoning") && data.get("isReasoning").asBoolean();
                        String msgTypeStr = data.has("messageType") ? data.get("messageType").asText() : "AI";
                        
                        ChatMessageType msgType = switch (msgTypeStr) {
                            case "USER" -> ChatMessageType.USER;
                            case "SYSTEM" -> ChatMessageType.SYSTEM;
                            default -> ChatMessageType.AI;
                        };
                        
                        mopHost.append(token, isNew, msgType, true, isReasoning);
                    }
                }
                case "NOTIFICATION" -> {
                    if (data != null) {
                        String message = data.has("message") ? data.get("message").asText() : "";
                        if (!message.isEmpty()) {
                            mopHost.showTransientMessage(message);
                            // Auto-hide after a delay
                            Timer hideTimer = new Timer(3000, e -> mopHost.hideTransientMessage());
                            hideTimer.setRepeats(false);
                            hideTimer.start();
                        }
                    }
                }
                case "ERROR" -> {
                    if (data != null) {
                        String message = data.has("message") ? data.get("message").asText() : "Unknown error";
                        String title = data.has("title") ? data.get("title").asText() : "Error";
                        chrome.toolError(message, title);
                    }
                }
                case "STATE_HINT" -> {
                    if (data != null) {
                        String name = data.has("name") ? data.get("name").asText() : "";
                        if ("outputSpinner".equals(name)) {
                            boolean show = data.has("value") && data.get("value").asBoolean();
                            if (show) {
                                mopHost.showSpinner("Processing...");
                            } else {
                                mopHost.hideSpinner();
                            }
                        } else if ("taskInProgress".equals(name)) {
                            boolean inProgress = data.has("value") && data.get("value").asBoolean();
                            mopHost.setTaskInProgress(inProgress);
                        }
                    }
                }
            }
        });
    }
    
    /**
     * Update the panel state based on agent state changes.
     */
    public void updateState() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(agent.getState().name());
            updateStatusColor();
            updateButtonStates();
            
            // Show error if agent failed
            String error = agent.getLastError();
            if (agent.getState() == AgentInstance.State.FAILED && error != null) {
                chrome.toolError(error, "Agent Error");
            }
        });
    }
    
    private void updateStatusColor() {
        switch (agent.getState()) {
            case READY, COMPLETED -> statusLabel.setForeground(new Color(0, 150, 0));
            case RUNNING -> statusLabel.setForeground(new Color(0, 100, 200));
            case FAILED -> statusLabel.setForeground(Color.RED);
            case STARTING, SHUTTING_DOWN -> statusLabel.setForeground(Color.ORANGE);
            default -> statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
    }
    
    private void updateButtonStates() {
        boolean canRun = agent.canAcceptJobs();
        boolean isRunning = agent.getState() == AgentInstance.State.RUNNING;
        
        runButton.setEnabled(canRun);
        stopButton.setEnabled(isRunning);
        modeCombo.setEnabled(canRun);
        promptArea.setEnabled(canRun);
    }
}
