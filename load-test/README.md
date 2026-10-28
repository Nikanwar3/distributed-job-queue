# Load testing

`k6-script.js` drives the platform end-to-end: it submits jobs over HTTP and polls each one until
it reaches a terminal state, so the numbers it reports cover the whole pipeline (API ingestion,
queueing, worker execution) rather than just the HTTP layer.

## Running it

```bash
docker compose up -d --build
docker compose up -d --scale worker=3

k6 run -e BASE_URL=http://localhost:8080 -e VUS=20 -e DURATION=60s load-test/k6-script.js
```

It reports, as custom metrics alongside k6's standard HTTP metrics:

- `jobqueue_queue_wait_ms` — time between submission and a worker picking the job up
- `jobqueue_execution_ms` — time a worker spent actually running the handler
- `jobqueue_completed_total` / `jobqueue_failed_total` — outcome counts

## Recovery behavior

Recovery timing is best measured by hand rather than scripted, since it requires killing a worker
mid-execution:

```bash
# submit a long-running job, note its id
curl -s -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' \
  -d '{"type":"slow","payload":{"sleepMs":30000},"timeoutSeconds":60,"maxRetries":1}'

# find and kill the worker container that's running it
docker compose ps
docker kill <worker-container-id>

# poll the job and note how long after the kill it flips back to QUEUED/FAILED
# with an error message containing "crashed"
watch -n1 "curl -s localhost:8080/api/v1/jobs/<job-id>"
```

The expected delay is approximately `jobqueue.recovery-min-idle-ms` (default 60s) plus up to
`jobqueue.recovery-interval-ms` (default 15s) — i.e. recovery isn't instant by design, since a
message that's merely idle for a few seconds is far more likely to be a legitimately slow job than
a crash. Lower both for a tighter (but noisier) bound.

## No pre-recorded numbers

This repository intentionally does not ship fabricated throughput/latency numbers. Results depend
entirely on the host running them (CPU, disk, network to Postgres/Redis, JVM warm-up). Run the
script against your own environment and record what you see — see the root `README.md`
"Measured results" section for one concrete run's numbers and the exact conditions they were taken
under.
