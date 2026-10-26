CREATE TABLE scan_runs (
  repo_id       TEXT        NOT NULL,
  build_id      BIGINT      NOT NULL,
  commit_sha    TEXT,
  scanned_at    TIMESTAMPTZ NOT NULL,
  finding_count INT         NOT NULL,
  PRIMARY KEY (repo_id, build_id)                 -- the idempotency key
);

CREATE TABLE findings (
  repo_id       TEXT   NOT NULL,
  build_id      BIGINT NOT NULL,
  fingerprint   TEXT   NOT NULL,
  cwe           TEXT,
  rule_id       TEXT   NOT NULL,
  found_by      TEXT[] NOT NULL,
  severity      TEXT   NOT NULL,
  file_path     TEXT   NOT NULL,
  line          INT    NOT NULL,
  message       TEXT,
  ai_verdict    TEXT,                             -- null when triage was off
  ai_confidence REAL,
  ai_reason     TEXT,
  PRIMARY KEY (repo_id, build_id, fingerprint),
  FOREIGN KEY (repo_id, build_id) REFERENCES scan_runs (repo_id, build_id)
);
