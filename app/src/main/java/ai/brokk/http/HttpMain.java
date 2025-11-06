package ai.brokk.http;

import com.google.common.base.Splitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HttpMain implements Runnable {

    private String listenAddr;
    private String authToken;
    private boolean daemon;

    private static final Logger logger = LogManager.getLogger(HttpMain.class);

    private static Map<String, String> parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var withoutPrefix = arg.substring(2);
                String key;
                String value;
                if (withoutPrefix.contains("=")) {
                    var parts = withoutPrefix.split("=", 2);
                    key = parts[0];
                    value = parts.length > 1 ? parts[1] : "";
                } else {
                    key = withoutPrefix;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "";
                    }
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private static String getArgValue(Map<String, String> parsedArgs, String argKey, String envVarName) {
        var argValue = parsedArgs.get(argKey);
        if (argValue != null && !argValue.isBlank()) {
            return argValue;
        }
        return System.getenv(envVarName);
    }

    public void configure(Map<String, String> parsedArgs) {
        this.listenAddr = getArgValue(parsedArgs, "listen-addr", "LISTEN_ADDR");
        if (this.listenAddr == null || this.listenAddr.isBlank()) {
            this.listenAddr = "localhost:8080";
        }
        this.authToken = getArgValue(parsedArgs, "auth-token", "AUTH_TOKEN");
        var daemonValue = getArgValue(parsedArgs, "daemon", "DAEMON");
        this.daemon = daemonValue != null && !daemonValue.isBlank();
    }

    @Override
    public void run() {
        try {
            List<String> parts = Splitter.on(':').trimResults().omitEmptyStrings().splitToList(listenAddr);
            if (parts.isEmpty() || parts.size() > 2) {
                throw new IllegalArgumentException("--listen-addr must be in the form host:port or host");
            }
            String host = parts.get(0);
            int port = parts.size() == 2 ? Integer.parseInt(parts.get(1)) : 8080;

            logger.info("Starting HTTP server endpoints: /health, /v1/review/cli, /v1/review on {}:{}", host, port);
            if (authToken != null && !authToken.isBlank()) {
                logger.info("Using auth token (masked)");
            }

            HttpServer server = new HttpServer(port);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop(5);
                } catch (Exception e) {
                    logger.warn("Error during HTTP server shutdown", e);
                }
            }, "brokk-http-shutdown"));

            if (daemon) {
                server.start();
                logger.info("HTTP server started on port {} (non-blocking)", server.getPort());
                Thread.currentThread().join();
            } else {
                server.startAndJoin();
            }
        } catch (Exception e) {
            logger.error("Failed to start HTTP server", e);
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        try {
            var parsedArgs = parseArgs(args);
            var main = new HttpMain();
            main.configure(parsedArgs);
            main.run();
        } catch (Exception e) {
            logger.error("Failed to start HTTP server", e);
            System.exit(1);
        }
    }
}
