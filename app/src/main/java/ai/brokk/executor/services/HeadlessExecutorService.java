package ai.brokk.executor.services;

import ai.brokk.executor.HeadlessExecutorMain;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HeadlessExecutorService {

    private static final Logger logger = LogManager.getLogger(HeadlessExecutorService.class);

    public record StartResult(Process process, int port, String authToken) {}

    public StartResult start(Path worktreePath, UUID sessionId) throws IOException {
        int port = findFreePort();
        String authToken = UUID.randomUUID().toString();
        UUID execId = UUID.randomUUID();

        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.add("-cp");
        command.add(classpath);
        command.add(HeadlessExecutorMain.class.getName());
        command.add("--workspace-dir");
        command.add(worktreePath.toAbsolutePath().toString());
        command.add("--listen-addr");
        command.add("127.0.0.1:" + port);
        command.add("--auth-token");
        command.add(authToken);
        command.add("--exec-id");
        command.add(execId.toString());

        logger.info("Starting headless executor with command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();

        Process process = processBuilder.start();

        return new StartResult(process, port, authToken);
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
