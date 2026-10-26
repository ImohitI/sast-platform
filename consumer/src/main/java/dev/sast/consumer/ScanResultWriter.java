package dev.sast.consumer;

import dev.sast.contracts.AiTriage;
import dev.sast.contracts.ScanResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.ZoneOffset;

/**
 * Stores one scan in ONE transaction: the run and all its findings, or nothing.
 *
 * Idempotency: (repo_id, build_id) is the primary key of scan_runs. A redelivered message hits ON CONFLICT DO NOTHING,
 * inserts 0 rows, and we stop. That early stop is only correct because the write is atomic: if scan_runs has the row,
 * all its findings were committed with it.
 */
@Repository
public class ScanResultWriter {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    public ScanResultWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this build was stored now, false if it was already there (a duplicate delivery). */
    @Transactional
    public boolean store(ScanResult scan) {
        int inserted = jdbc.update("""
                INSERT INTO scan_runs (repo_id, build_id, commit_sha, scanned_at, finding_count)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (repo_id, build_id) DO NOTHING
                """,
                scan.repoId(), scan.buildId(), scan.commitSha(),
                scan.scannedAt().atOffset(ZoneOffset.UTC), scan.findings().size());
        if (inserted == 0) {
            return false;
        }

        jdbc.batchUpdate("""
                INSERT INTO findings (repo_id, build_id, fingerprint, cwe, rule_id, found_by, severity, file_path, line,
                                      message, ai_verdict, ai_confidence, ai_reason, ai_suggested_fix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (repo_id, build_id, fingerprint) DO NOTHING
                """,
                scan.findings(), BATCH_SIZE, (ps, finding) -> {
                    AiTriage ai = finding.ai();
                    ps.setString(1, scan.repoId());
                    ps.setLong(2, scan.buildId());
                    ps.setString(3, finding.fingerprint());
                    ps.setString(4, finding.cwe());
                    ps.setString(5, finding.ruleId());
                    ps.setArray(6, ps.getConnection().createArrayOf("text", finding.foundBy().toArray()));
                    ps.setString(7, finding.severity().name());
                    ps.setString(8, finding.file());
                    ps.setInt(9, finding.line());
                    ps.setString(10, finding.message());
                    ps.setString(11, ai == null ? null : ai.verdict().name());
                    ps.setObject(12, ai == null ? null : ai.confidence(), Types.REAL);
                    ps.setString(13, ai == null ? null : ai.reason());
                    ps.setString(14, ai == null ? null : ai.suggestedFix());
                });
        return true;
    }
}
