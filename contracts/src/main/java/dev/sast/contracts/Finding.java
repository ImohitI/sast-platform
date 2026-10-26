package dev.sast.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * fingerprint = sha256(cwe|file|line). Together with (repoId, buildId) it is the idempotency key in Postgres.
 * ai is null when triage was off. The AI only annotates a finding, it never removes one.
 */
public record Finding(
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
        @Pattern(regexp = "CWE-[0-9]+") String cwe,
        @NotBlank String ruleId,
        @NotEmpty List<@NotBlank String> foundBy,
        @NotNull Severity severity,
        @NotBlank String file,
        @NotNull @PositiveOrZero Integer line,
        String message,
        @Valid AiTriage ai) {
}
