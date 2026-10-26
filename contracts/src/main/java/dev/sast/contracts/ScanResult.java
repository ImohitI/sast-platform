package dev.sast.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * One scan of one build: the os-sast output, the POST body and the Kafka message are all this shape.
 * Kafka key = repoId, so every build of a repo lands on the same partition, in order.
 * (repoId, buildId) is the idempotency key in Postgres.
 *
 * buildId is a boxed Long on purpose: a primitive would turn a missing value into 0, and every such build
 * would then collide on (repoId, 0) and be dropped as a "duplicate".
 */
public record ScanResult(
        @NotBlank String repoId,
        @NotNull @Positive Long buildId,
        String commitSha,
        @NotNull Instant scannedAt,
        @NotNull List<@NotNull @Valid Tool> tools,
        @NotNull List<@NotNull @Valid Finding> findings) {
}
