#!/usr/bin/env bash
# Submits one job of each sample type against a running instance of the platform.
# Usage: ./submit-jobs.sh [base-url]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}/api/v1/jobs"

submit() {
  local description="$1"
  local body="$2"
  echo "--- ${description} ---"
  curl -sS -X POST "${BASE_URL}" \
    -H 'Content-Type: application/json' \
    -d "${body}" | tee /dev/stderr | jq -r '.id'
  echo
}

submit "echo: returns its payload unchanged" \
  '{"type":"echo","payload":{"message":"hello from sample workload"}}'

submit "fibonacci: CPU-bound sample workload" \
  '{"type":"fibonacci","payload":{"n":50}}'

submit "flaky: fails ~70% of the time, exercises retry/backoff" \
  '{"type":"flaky","payload":{"failureRate":0.7},"maxRetries":5}'

submit "slow: sleeps longer than its timeout, exercises timeout handling" \
  '{"type":"slow","payload":{"sleepMs":5000},"timeoutSeconds":2,"maxRetries":1}'

submit "idempotent resubmission: second call with the same key returns the same job" \
  '{"type":"echo","payload":{"note":"idempotent"},"idempotencyKey":"sample-fixed-key-001"}'
submit "idempotent resubmission (duplicate)" \
  '{"type":"echo","payload":{"note":"idempotent"},"idempotencyKey":"sample-fixed-key-001"}'

echo "Done. Check job status with:"
echo "  curl ${BASE_URL}/<job-id>"
