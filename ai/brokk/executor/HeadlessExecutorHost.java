package ai.brokk.executor;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStatus;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * HTTP adapter that binds a HeadlessExecutorService to a SimpleHttpServer.
 *
 * Exposes:
 *  - start()
 *  - stop(int delaySeconds)
 *  - getPort()
 *
 * Endpoints:
 *  - Unauthenticated:
 *      GET  /health/live            -> { "status": "LIVE" }
 *      GET  /health/ready           -> { "ready": boolean }
 *      GET  /v1/executor            -> { "execId": "<uuid>" }
 *  - Authenticated:
 *      POST /v1/session            -> accepts zip bytes, optional X-Session-Id header
 *      POST /v1/jobs               -> create job (idempotency via header)
 *      GET  /v1/jobs/{jobId}       -> job status
 *      GET  /v1/jobs/{jobId}/events?after=&limit= -> job events
 *      POST /v1/jobs/{jobId}/cancel -> request cancel
 *      GET  /v1/executor/diff      -> { "diff": "<text>" }
 */
public final class HeadlessExecutorHost {
  private static final Logger logger = LogManager.getLogger(HeadlessExecutorHost.class);

  private final HeadlessExecutorService service;
  private final SimpleHttpServer server;

  public HeadlessExecutorHost(
      HeadlessExecutorService service, String host, int port, String authToken, int threadCount) {
    this.service = Objects.requireNonNull(service);
    this.server = new SimpleHttpServer(host, port, Objects.requireNonNull(authToken), threadCount);
    registerEndpoints();
  }

  private void registerEndpoints() {
    // Unauthenticated endpoints
    server.registerUnauthenticatedContext(
        "/health/live",
        exchange -> {
          try {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "LIVE"));
          } catch (Exception e) {
            logger.error("/health/live failed", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });

    server.registerUnauthenticatedContext(
        "/health/ready",
        exchange -> {
          try {
            var ready = service.getCurrentSessionId() != null;
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("ready", ready));
          } catch (Exception e) {
            logger.error("/health/ready failed", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });

    server.registerUnauthenticatedContext(
        "/v1/executor",
        exchange -> {
          try {
            SimpleHttpServer.sendJsonResponse(
                exchange, Map.of("execId", service.getExecId().toString()));
          } catch (Exception e) {
            logger.error("/v1/executor failed", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });

    // Authenticated endpoints
    server.registerAuthenticatedContext(
        "/v1/session",
        exchange -> {
          try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
              SimpleHttpServer.sendErrorResponse(exchange, 405, "Method Not Allowed");
              return;
            }
            var bytes = exchange.getRequestBody().readAllBytes();
            var headerSessionId = getHeaderCaseInsensitive(exchange, "X-Session-Id");
            final UUID sessionId;
            if (headerSessionId != null && !headerSessionId.isBlank()) {
              try {
                sessionId = UUID.fromString(headerSessionId.trim());
              } catch (IllegalArgumentException iae) {
                SimpleHttpServer.sendErrorResponse(
                    exchange, 400, "Invalid X-Session-Id UUID: " + iae.getMessage());
                return;
              }
            } else {
              sessionId = UUID.randomUUID();
            }

            try {
              service.importSessionZip(bytes, sessionId);
            } catch (Exception e) {
              logger.error("Failed to import session zip", e);
              SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
              return;
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("sessionId", sessionId.toString()));
          } catch (IOException e) {
            logger.error("/v1/session IO error", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          } catch (Exception e) {
            logger.error("/v1/session unexpected error", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });

    // Jobs router
    server.registerAuthenticatedContext(
        "/v1/jobs",
        exchange -> {
          try {
            routeJobs(exchange);
          } catch (Exception e) {
            logger.error("/v1/jobs handler failed", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });

    // Diff endpoint
    server.registerAuthenticatedContext(
        "/v1/executor/diff",
        exchange -> {
          try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
              SimpleHttpServer.sendErrorResponse(exchange, 405, "Method Not Allowed");
              return;
            }
            var diff = service.getDiff();
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("diff", diff));
          } catch (UnsupportedOperationException uoe) {
            SimpleHttpServer.sendErrorResponse(exchange, 400, uoe.getMessage());
          } catch (Exception e) {
            logger.error("/v1/executor/diff failed", e);
            SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
          }
        });
  }

  private void routeJobs(HttpExchange exchange) throws Exception {
    var method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    var path = exchange.getRequestURI().getPath();
    var segments = splitPath(path);

    int v1Idx = -1;
    for (int i = 0; i < segments.size(); i++) {
      if ("v1".equals(segments.get(i))) {
        v1Idx = i;
        break;
      }
    }
    if (v1Idx < 0 || v1Idx + 1 >= segments.size() || !"jobs".equals(segments.get(v1Idx + 1))) {
      SimpleHttpServer.sendErrorResponse(exchange, 404, "Not Found");
      return;
    }

    var rest = new ArrayList<String>();
    for (int i = v1Idx + 2; i < segments.size(); i++) {
      rest.add(segments.get(i));
    }

    if (rest.isEmpty()) {
      if ("POST".equals(method)) {
        handleCreateJob(exchange);
        return;
      }
      SimpleHttpServer.sendErrorResponse(exchange, 405, "Method Not Allowed");
      return;
    }

    if (rest.size() == 1) {
      var jobId = rest.get(0);
      if ("GET".equals(method)) {
        handleGetJob(exchange, jobId);
        return;
      }
      SimpleHttpServer.sendErrorResponse(exchange, 405, "Method Not Allowed");
      return;
    }

    if (rest.size() == 2) {
      var jobId = rest.get(0);
      var action = rest.get(1);
      if ("events".equals(action) && "GET".equals(method)) {
        handleGetJobEvents(exchange, jobId);
        return;
      }
      if ("cancel".equals(action) && "POST".equals(method)) {
        handleCancelJob(exchange, jobId);
        return;
      }
      SimpleHttpServer.sendErrorResponse(exchange, 404, "Not Found");
      return;
    }

    SimpleHttpServer.sendErrorResponse(exchange, 404, "Not Found");
  }

  private void handleCreateJob(HttpExchange exchange) {
    try {
      var spec = SimpleHttpServer.parseJsonRequest(exchange, JobSpec.class);
      var key = firstNonBlank(
          getHeaderCaseInsensitive(exchange, "Idempotency-Key"),
          getHeaderCaseInsensitive(exchange, "X-Idempotency-Key"));
      if (key == null || key.isBlank()) {
        key = UUID.randomUUID().toString();
      }

      var result = service.createJob(key, spec);
      var response = new HashMap<String, Object>();
      response.put("jobId", result.jobId());
      response.put("isNew", result.isNewJob());
      SimpleHttpServer.sendJsonResponse(exchange, 201, response);
    } catch (IllegalArgumentException iae) {
      SimpleHttpServer.sendErrorResponse(exchange, 400, iae.getMessage());
    } catch (Exception e) {
      logger.error("Failed to create job", e);
      SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
    }
  }

  private void handleGetJob(HttpExchange exchange, String jobId) {
    try {
      var status = service.getJobStatus(jobId);
      if (status == null) {
        SimpleHttpServer.sendErrorResponse(exchange, 404, "Job not found: " + jobId);
        return;
      }
      SimpleHttpServer.sendJsonResponse(exchange, status);
    } catch (Exception e) {
      logger.error("Failed to get job status for {}", jobId, e);
      SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
    }
  }

  private void handleGetJobEvents(HttpExchange exchange, String jobId) {
    try {
      var query = parseQuery(exchange.getRequestURI());
      long after = parseLong(query, "after", 0L);
      int limit = (int) parseLong(query, "limit", 100L);

      var result = service.getJobEvents(jobId, after, limit);
      var response = new HashMap<String, Object>();
      response.put("events", result.events());
      response.put("nextAfter", result.nextAfter());
      SimpleHttpServer.sendJsonResponse(exchange, response);
    } catch (NumberFormatException nfe) {
      SimpleHttpServer.sendErrorResponse(exchange, 400, nfe.getMessage());
    } catch (Exception e) {
      logger.error("Failed to get job events for {}", jobId, e);
      SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
    }
  }

  private void handleCancelJob(HttpExchange exchange, String jobId) {
    try {
      service.cancelJob(jobId);
      SimpleHttpServer.sendJsonResponse(exchange, 202, Map.of("cancelled", true));
    } catch (Exception e) {
      logger.error("Failed to cancel job {}", jobId, e);
      SimpleHttpServer.sendErrorResponse(exchange, 500, e.getMessage());
    }
  }

  public void start() {
    server.start();
    logger.info("HeadlessExecutorHost started: execId={}, port={}", service.getExecId(), server.getPort());
  }

  public void stop(int delaySeconds) {
    server.stop(delaySeconds);
    logger.info("HeadlessExecutorHost stopped: execId={}", service.getExecId());
  }

  public int getPort() {
    return server.getPort();
  }

  // Helpers
  private static List<String> splitPath(String path) {
    var raw = path == null ? "" : path;
    var parts = raw.split("/");
    var result = new ArrayList<String>();
    for (var p : parts) {
      if (!p.isEmpty()) {
        result.add(p);
      }
    }
    return result;
  }

  private static long parseLong(Map<String, List<String>> query, String name, long defaultValue) {
    var vals = query.get(name);
    if (vals == null || vals.isEmpty()) return defaultValue;
    return Long.parseLong(vals.get(0));
  }

  private static Map<String, List<String>> parseQuery(URI uri) {
    var result = new HashMap<String, List<String>>();
    var q = uri.getQuery();
    if (q == null || q.isBlank()) return result;
    for (var pair : q.split("&")) {
      if (pair.isEmpty()) continue;
      var idx = pair.indexOf('=');
      final String key;
      final String val;
      if (idx >= 0) {
        key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
        val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
      } else {
        key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
        val = "";
      }
      result.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
    }
    return result;
  }

  private static @Nullable String getHeaderCaseInsensitive(HttpExchange ex, String... names) {
    var headers = ex.getRequestHeaders();
    if (headers == null) return null;
    for (var entry : headers.entrySet()) {
      var key = entry.getKey();
      for (var n : names) {
        if (key != null && key.equalsIgnoreCase(n)) {
          var vals = entry.getValue();
          if (vals != null && !vals.isEmpty()) {
            return vals.get(0);
          }
        }
      }
    }
    return null;
  }

  private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return null;
  }
}
