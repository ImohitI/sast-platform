// E4: "500 builds at 6 PM". 50 POSTs per second for 10 seconds against ingest-api.
//   docker compose --profile load run --rm k6
//   (or, with k6 installed: INGEST_URL=http://localhost:8080 k6 run load-test/scan-results.js)
// Watch Kafka UI (consumer lag) while it runs: ingest-api answers fast, the consumer drains at its own pace.
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { check } from 'k6';

const INGEST_URL = __ENV.INGEST_URL || 'http://localhost:8080';
// Several repos, so both partitions get traffic. With only "samples", every message lands on one partition.
const REPOS = (__ENV.REPOS || 'samples,repo-a,repo-b,repo-c').split(',');
const FINDINGS_PER_SCAN = Number(__ENV.FINDINGS || 20);

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<1000'],
  },
};

// Runs once: every run gets fresh buildIds, otherwise the consumer would (correctly) skip them as duplicates.
export function setup() {
  return { buildBase: Math.floor(Date.now() / 1000) * 1000 };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const repoId = REPOS[n % REPOS.length];
  const findings = [];
  for (let i = 0; i < FINDINGS_PER_SCAN; i++) {
    const file = `src/module${i % 5}/file${i}.py`;
    findings.push({
      fingerprint: crypto.sha256(`CWE-89|${file}|${i + 1}`, 'hex'),
      cwe: 'CWE-89',
      ruleId: 'load.test.rule',
      foundBy: ['semgrep'],
      severity: ['HIGH', 'MEDIUM', 'LOW'][i % 3],
      file,
      line: i + 1,
      message: 'synthetic finding from k6',
      ai: null,
    });
  }
  const body = JSON.stringify({
    repoId,
    buildId: data.buildBase + n,
    commitSha: `k6${n}`,
    scannedAt: new Date().toISOString(),
    tools: [{ name: 'k6', version: '1' }],
    findings,
  });

  const res = http.post(`${INGEST_URL}/api/scan-results`, body, { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'status is 202': (r) => r.status === 202 });
}
