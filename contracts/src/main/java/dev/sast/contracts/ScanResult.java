package dev.sast.contracts;

import java.time.Instant;
import java.util.List;

/**
 * One scan of one build: the os-sast output, the POST body and the Kafka message are all this shape.
 * Kafka key = repoId, so every build of a repo lands on the same partition, in order.
 */
public record ScanResult(
        String repoId,
        long buildId,
        String commitSha,
        Instant scannedAt,
        List<Tool> tools,
        List<Finding> findings) {
}
