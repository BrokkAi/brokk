package ai.brokk.ctl;

import java.util.List;
import java.util.Objects;

/**
 * Simple DTO representing a registry entry for a running Brokk instance.
 *
 * Fields (canonical JSON schema):
 * - instanceId (string)
 * - pid (number|null)
 * - listenAddr (string)
 * - projects (array of strings) - may be empty or null
 * - brokkctlVersion (string)
 * - startedAt (number epoch ms)
 * - lastSeenMs (number epoch ms)
 */
public final class InstanceRecord {
    public final String instanceId;
    public final Integer pid; // nullable
    public final String listenAddr;
    public final List<String> projects;
    public final String brokkctlVersion;
    public final long startedAt;
    public final long lastSeenMs;

    public InstanceRecord(String instanceId,
                          Integer pid,
                          String listenAddr,
                          List<String> projects,
                          String brokkctlVersion,
                          long startedAt,
                          long lastSeenMs) {
        this.instanceId = Objects.requireNonNull(instanceId);
        this.pid = pid;
        this.listenAddr = Objects.requireNonNull(listenAddr);
        this.projects = projects;
        this.brokkctlVersion = Objects.requireNonNull(brokkctlVersion);
        this.startedAt = startedAt;
        this.lastSeenMs = lastSeenMs;
    }

    /**
     * Basic JSON serialization. This method intentionally keeps dependencies minimal.
     * It is suitable for writing registry files in the canonical shape required by brokkctl.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "instanceId", instanceId); sb.append(",");
        if (pid == null) {
            appendJsonNullField(sb, "pid");
        } else {
            appendJsonField(sb, "pid", pid.toString());
        }
        sb.append(",");
        appendJsonField(sb, "listenAddr", listenAddr); sb.append(",");
        // projects array
        sb.append("\"projects\":");
        if (projects == null) {
            sb.append("null");
        } else {
            sb.append("[");
            for (int i = 0; i < projects.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(projects.get(i))).append("\"");
            }
            sb.append("]");
        }
        sb.append(",");
        appendJsonField(sb, "brokkctlVersion", brokkctlVersion); sb.append(",");
        appendJsonField(sb, "startedAt", Long.toString(startedAt)); sb.append(",");
        appendJsonField(sb, "lastSeenMs", Long.toString(lastSeenMs));
        sb.append("}");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String name, String value) {
        sb.append("\"").append(escapeJson(name)).append("\":");
        // determine if value should be quoted (if it's not a pure number)
        boolean isNumber = value.matches("-?\\d+");
        if (isNumber) {
            sb.append(value);
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
    }

    private static void appendJsonNullField(StringBuilder sb, String name) {
        sb.append("\"").append(escapeJson(name)).append("\":null");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
