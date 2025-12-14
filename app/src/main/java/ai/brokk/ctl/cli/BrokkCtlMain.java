package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Minimal CLI entrypoint used by tests.
 *
 * Supports:
 *   instances list [--ttl <ms>] [--all] [--config-dir <path>] [--request-id <id>]
 *
 * Delegates behavior to InstancesList.execute(...) and returns its exit code.
 */
public final class BrokkCtlMain {
    private BrokkCtlMain() {}

    /**
             * Run the minimal brokkctl CLI command subset needed for tests.
             *
             * @param args  command-line style args (e.g. "instances", "list", "--ttl", "1000")
             * @param out   stdout target
             * @param err   stderr target
             * @return exit code (0 success, non-zero error)
             */
           public static int run(String[] args, PrintStream out, PrintStream err) {
                  if (args == null || args.length < 2) {
                          err.println("Missing command. Expected: instances list");
                          return 2;
                  }

                  String cmd0 = args[0];
                  String cmd1 = args[1];

                  // defaults
                  long ttlMs = 1000L;
                  boolean includeAll = false;
                  String configDir = null;
                  String requestId = null;

                  // new selection flags
                  String instanceSelector = null;
                  boolean autoSelect = false;

                  // command-specific args
                  String pathArg = null;
                  String sessionArg = null;
                  String nameArg = null;

                  // parse flags starting at index 2
                  for (int i = 2; i < args.length; i++) {
                          String a = args[i];
                          switch (a) {
                                 case "--ttl":
                                        if (i + 1 >= args.length) {
                                               err.println("--ttl requires a numeric argument");
                                               return 2;
                                        }
                                        String ttlArg = args[++i];
                                        try {
                                               ttlMs = Long.parseLong(ttlArg);
                                        } catch (NumberFormatException nfe) {
                                               err.println("Invalid ttl value: " + ttlArg);
                                               return 2;
                                        }
                                        break;
                                 case "--all":
                                        includeAll = true;
                                        break;
                                 case "--instance":
                                        if (i + 1 >= args.length) {
                                               err.println("--instance requires an instance id (or comma-separated ids)");
                                               return 2;
                                        }
                                        instanceSelector = args[++i];
                                        break;
                                 case "--auto-select":
                                        autoSelect = true;
                                        break;
                                 case "--config-dir":
                                        if (i + 1 >= args.length) {
                                               err.println("--config-dir requires a path argument");
                                               return 2;
                                        }
                                        configDir = args[++i];
                                        break;
                                 case "--request-id":
                                        if (i + 1 >= args.length) {
                                               err.println("--request-id requires an identifier");
                                               return 2;
                                        }
                                        requestId = args[++i];
                                        break;
                                 case "--path":
                                        if (i + 1 >= args.length) {
                                               err.println("--path requires a path argument");
                                               return 2;
                                        }
                                        pathArg = args[++i];
                                        break;
                                 case "--session":
                                        if (i + 1 >= args.length) {
                                               err.println("--session requires a session id");
                                               return 2;
                                        }
                                        sessionArg = args[++i];
                                        break;
                                 case "--name":
                                        if (i + 1 >= args.length) {
                                               err.println("--name requires a value");
                                               return 2;
                                        }
                                        nameArg = args[++i];
                                        break;
                                 default:
                                        err.println("Unknown flag: " + a);
                                        return 2;
                          }
                  }

                  CtlConfigPaths cfg;
                  try {
                          if (configDir != null && !configDir.isBlank()) {
                                 cfg = CtlConfigPaths.forBaseConfigDir(Path.of(configDir));
                          } else {
                                 cfg = CtlConfigPaths.defaults();
                          }
                  } catch (Exception e) {
                          err.println("Failed to resolve config dir: " + e.getMessage());
                          return 2;
                  }

                  // Dispatch supported commands
                  if ("instances".equals(cmd0) && "list".equals(cmd1)) {
                          return InstancesList.execute(cfg, ttlMs, includeAll, instanceSelector, autoSelect, requestId, out, err);
                  } else if ("projects".equals(cmd0) && "open".equals(cmd1)) {
                          return CtlCommands.executeProjectOpen(cfg, ttlMs, includeAll, instanceSelector, autoSelect, requestId, pathArg, out, err);
                  } else if ("sessions".equals(cmd0) && "active".equals(cmd1)) {
                          return CtlCommands.executeSessionActive(cfg, ttlMs, includeAll, instanceSelector, autoSelect, requestId, out, err);
                  } else if ("sessions".equals(cmd0) && "create".equals(cmd1)) {
                          return CtlCommands.executeSessionCreate(cfg, ttlMs, includeAll, instanceSelector, autoSelect, requestId, nameArg, out, err);
                  } else if ("sessions".equals(cmd0) && "switch".equals(cmd1)) {
                          return CtlCommands.executeSessionSwitch(cfg, ttlMs, includeAll, instanceSelector, autoSelect, requestId, sessionArg, out, err);
                  } else {
                          err.println("Unsupported command. Only 'instances list', 'projects open', and 'sessions {active,create,switch}' are implemented in tests.");
                          return 2;
                  }
           }
}
