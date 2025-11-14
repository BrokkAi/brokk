package ai.brokk.executor.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Durable, file-backed job store with idempotency and monotonic event sequencing.
 *
 * <p>Layout:
 * <ul>
 *   <li>{storeDir}/jobs/{jobId}/meta.json - immutable job specification</li>
 *   <li>{storeDir}/jobs/{jobId}/status.json - mutable job status</li>
 *   <li>{storeDir}/jobs/{jobId}/events.jsonl - append-only event log</li>
 *   <li>{storeDir}/jobs/{jobId}/artifacts/ - diff.txt and other artifacts</li>
 *   <li>{storeDir}/idempotency/{hash}.json - maps idempotency key to jobId</li>
 * </ul>
 *
 * <p>Thread-safe: multiple threads can concurrently append events, update status, etc.
 * Each job maintains an AtomicLong for monotonic sequence assignment.
 */
public final class JobStore {
    private static final Logger logger = LogManager.getLogger(JobStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Path jobsDir;
    private final Path idempotencyDir;
    private final Map<String, AtomicLong> jobSequenceCounters;

    /**
     * Create a new JobStore rooted at the given directory.
     *
     * @param storeDir The root directory for job storage
     * @throws IOException If directories cannot be created
     */
    public JobStore(Path storeDir) throws IOException {
        this.jobsDir = storeDir.resolve("jobs");
        this.idempotencyDir = storeDir.resolve("idempotency");
        this.jobSequenceCounters = new HashMap<>();

        Files.createDirectories(this.jobsDir);
        Files.createDirectories(this.idempotencyDir);

        logger.info("JobStore initialized at {}", storeDir);
    }

    /**
     * Create a new job or retrieve an existing one based on idempotency key.
     * Idempotency is based on the hash of the key; clients should use a stable key (e.g., session ID + timestamp).
     *
     * @param idempKey The idempotency key (arbitrary string)
     * @param spec The job specification
     * @return A pair of (jobId, isNewJob) where isNewJob is true if a new job was created
     * @throws IOException If I/O fails
     */
    public synchronized JobCreateResult createOrGetJob(String idempKey, JobSpec spec) throws IOException {
        var hash = hashIdempKey(idempKey);
        var idempFile = idempotencyDir.resolve(hash + ".json");

        // Check if this idempotency key has been seen before
        if (Files.exists(idempFile)) {
            var existingEntry = objectMapper.readValue(idempFile.toFile(), IdempotencyEntry.class);
            logger.info("Idempotency hit for key {}: returning existing job {}", idempKey, existingEntry.jobId);
            return new JobCreateResult(existingEntry.jobId, false);
        }

        // Create new job
        var jobId = UUID.randomUUID().toString();
        var jobDir = jobsDir.resolve(jobId);
        Files.createDirectories(jobDir);
        Files.createDirectories(jobDir.resolve("artifacts"));

        // Write meta.json (immutable)
        var metaFile = jobDir.resolve("meta.json");
        var tempMetaFile = jobDir.resolve(".meta.json.tmp");
        objectMapper.writeValue(tempMetaFile.toFile(), spec);
        Files.move(tempMetaFile, metaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Write initial status.json
        var initialStatus = JobStatus.queued(jobId);
        var statusFile = jobDir.resolve("status.json");
        var tempStatusFile = jobDir.resolve(".status.json.tmp");
        objectMapper.writeValue(tempStatusFile.toFile(), initialStatus);
        Files.move(tempStatusFile, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Write idempotency entry
        var idempEntry = new IdempotencyEntry(jobId, System.currentTimeMillis());
        var tempIdempFile = idempotencyDir.resolve("." + hash + ".json.tmp");
        objectMapper.writeValue(tempIdempFile.toFile(), idempEntry);
        Files.move(tempIdempFile, idempFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Initialize sequence counter for this job
        jobSequenceCounters.put(jobId, new AtomicLong(0));

        logger.info("Created new job {} for idempotency key {}", jobId, idempKey);
        return new JobCreateResult(jobId, true);
    }

    /**
     * Append an event to a job's event stream.
     * Assigns a monotonically increasing sequence number.
     *
     * @param jobId The job ID
     * @param event The event to append
     * @return The assigned sequence number
     * @throws IOException If I/O fails
     */
    public long appendEvent(String jobId, JobEvent event) throws IOException {
        var counter = jobSequenceCounters.computeIfAbsent(jobId, k -> loadSequenceCounter(jobId));
        var seq = counter.incrementAndGet();

        var eventWithSeq = new JobEvent(seq, event.timestamp(), event.type(), event.data());
        var jobDir = jobsDir.resolve(jobId);
        var eventsFile = jobDir.resolve("events.jsonl");

        // Append as JSONL (one JSON object per line)
        var eventLine = objectMapper.writeValueAsString(eventWithSeq) + "\n";
        Files.createDirectories(jobDir);
        Files.write(
                eventsFile,
                eventLine.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        logger.debug("Appended event {} to job {} with seq {}", event.type(), jobId, seq);
        return seq;
    }

    /**
     * Update the status of a job. Writes are atomic (write-then-rename).
     *
     * @param jobId The job ID
     * @param status The new status
     * @throws IOException If I/O fails
     */
    public void updateStatus(String jobId, JobStatus status) throws IOException {
        var jobDir = jobsDir.resolve(jobId);
        var statusFile = jobDir.resolve("status.json");
        var tempStatusFile = jobDir.resolve(".status.json.tmp");

        Files.createDirectories(jobDir);
        objectMapper.writeValue(tempStatusFile.toFile(), status);
        Files.move(tempStatusFile, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        logger.debug("Updated status for job {} to {}", jobId, status.state());
    }

    /**
     * Load the current status of a job.
     *
     * @param jobId The job ID
     * @return The job status, or null if the job does not exist
     * @throws IOException If I/O fails
     */
    public @Nullable JobStatus loadStatus(String jobId) throws IOException {
        var statusFile = jobsDir.resolve(jobId).resolve("status.json");
        if (!Files.exists(statusFile)) {
            return null;
        }
        return objectMapper.readValue(statusFile.toFile(), JobStatus.class);
    }

    /**
     * Read events from a job's event stream.
     *
     * @param jobId The job ID
     * @param afterSeq Return only events with seq > afterSeq (pass -1 to get all events)
     * @param limit Maximum number of events to return (0 = no limit)
     * @return List of events in sequence order
     * @throws IOException If I/O fails
     */
    public List<JobEvent> readEvents(String jobId, long afterSeq, int limit) throws IOException {
        var eventsFile = jobsDir.resolve(jobId).resolve("events.jsonl");
        if (!Files.exists(eventsFile)) {
            return List.of();
        }

        var result = new ArrayList<JobEvent>();
        var lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);

        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var event = objectMapper.readValue(line, JobEvent.class);
            if (event.seq() > afterSeq) {
                result.add(event);
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Load the job specification (meta.json).
     *
     * @param jobId The job ID
     * @return The job spec, or null if the job does not exist
     * @throws IOException If I/O fails
     */
    public @Nullable JobSpec loadSpec(String jobId) throws IOException {
        var metaFile = jobsDir.resolve(jobId).resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return null;
        }
        return objectMapper.readValue(metaFile.toFile(), JobSpec.class);
    }

    /**
     * Write an artifact file (e.g., diff.txt) for a job.
     *
     * @param jobId The job ID
     * @param artifactName The artifact name (e.g., "diff.txt")
     * @param content The artifact content as bytes
     * @throws IOException If I/O fails
     */
    public void writeArtifact(String jobId, String artifactName, byte[] content) throws IOException {
        var artifactFile = jobsDir.resolve(jobId).resolve("artifacts").resolve(artifactName);
        Files.createDirectories(artifactFile.getParent());

        var tempFile = artifactFile.resolveSibling("." + artifactName + ".tmp");
        Files.write(tempFile, content);
        Files.move(tempFile, artifactFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        logger.debug("Wrote artifact {} for job {}", artifactName, jobId);
    }

    /**
     * Read an artifact file.
     *
     * @param jobId The job ID
     * @param artifactName The artifact name
     * @return The artifact content, or null if not found
     * @throws IOException If I/O fails
     */
    public @Nullable byte[] readArtifact(String jobId, String artifactName) throws IOException {
        var artifactFile = jobsDir.resolve(jobId).resolve("artifacts").resolve(artifactName);
        if (!Files.exists(artifactFile)) {
            return null;
        }
        return Files.readAllBytes(artifactFile);
    }

    /**
     * Get the root directory of a job (for direct filesystem access if needed).
     *
     * @param jobId The job ID
     * @return The job directory path
     */
    public Path getJobDir(String jobId) {
        return jobsDir.resolve(jobId);
    }

    // ============================================================================
    // Private helpers
    // ============================================================================

    private String hashIdempKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private AtomicLong loadSequenceCounter(String jobId) {
        try {
            var eventsFile = jobsDir.resolve(jobId).resolve("events.jsonl");
            if (!Files.exists(eventsFile)) {
                return new AtomicLong(0);
            }

            var lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new AtomicLong(0);
            }

            // Last line should have the highest seq
            var lastLine = lines.get(lines.size() - 1);
            if (lastLine.isBlank()) {
                return new AtomicLong(0);
            }

            var lastEvent = objectMapper.readValue(lastLine, JobEvent.class);
            return new AtomicLong(lastEvent.seq());
        } catch (IOException e) {
            logger.warn("Failed to load sequence counter for job {}", jobId, e);
            return new AtomicLong(0);
        }
    }

    // ============================================================================
    // DTOs
    // ============================================================================

    /**
     * Result of creating or getting a job.
     */
    public record JobCreateResult(String jobId, boolean isNewJob) {}

    /**
     * Internal record for idempotency tracking.
     */
    private record IdempotencyEntry(String jobId, long createdAt) {}
}
