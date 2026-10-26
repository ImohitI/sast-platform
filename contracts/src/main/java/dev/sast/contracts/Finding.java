package dev.sast.contracts;

import java.util.List;

/**
 * fingerprint = sha256(cwe|file|line). Together with (repoId, buildId) it is the idempotency key in Postgres.
 * ai is null when triage was off. The AI only annotates a finding, it never removes one.
 */
public record Finding(
        String fingerprint,
        String cwe,
        String ruleId,
        List<String> foundBy,
        Severity severity,
        String file,
        int line,
        String message,
        AiTriage ai) {
}
