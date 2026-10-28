# API reference

Interactive docs are also served at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`) once the
application is running.

Base path: `/api/v1/jobs`

## Submit a job

```
POST /api/v1/jobs
Content-Type: application/json

{
  "type": "fibonacci",
  "payload": { "n": 30 },
  "idempotencyKey": "optional-client-supplied-key",
  "maxRetries": 3,
  "timeoutSeconds": 30
}
```

- `type` — selects the registered handler (`echo`, `fibonacci`, `flaky`, `slow` ship as samples)
- `payload` — arbitrary JSON, opaque to the platform, passed to the handler verbatim
- `idempotencyKey` — optional; resubmitting the same key returns the original job instead of
  creating a duplicate (`200 OK` instead of `201 Created`)
- `maxRetries`, `timeoutSeconds` — optional, fall back to server defaults if omitted

Returns `201 Created` (or `200 OK` for an idempotent repeat) with the job representation and a
`Location` header pointing at `GET /api/v1/jobs/{id}`.

## Get job status

```
GET /api/v1/jobs/{id}
```

```json
{
  "id": "…",
  "idempotencyKey": null,
  "type": "fibonacci",
  "status": "RUNNING",
  "retryCount": 0,
  "maxRetries": 3,
  "timeoutSeconds": 30,
  "nextRetryAt": null,
  "createdAt": "2026-01-01T00:00:00Z",
  "updatedAt": "2026-01-01T00:00:01Z",
  "startedAt": "2026-01-01T00:00:01Z",
  "completedAt": null,
  "errorMessage": null
}
```

`status` is one of `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.

## Get a job's result

```
GET /api/v1/jobs/{id}/result
```

Returns `{ "id", "status", "result", "errorMessage" }`. `result` is `null` until the job completes
successfully; `errorMessage` is populated on failure (and holds the most recent attempt's error
while retries are still in flight).

## List jobs

```
GET /api/v1/jobs?status=FAILED&page=0&size=20&sort=createdAt,desc
```

Standard Spring Data pagination parameters apply. `status` is optional.

## Execution history

```
GET /api/v1/jobs/{id}/executions
```

Returns every attempt made against the job, oldest first:

```json
[
  { "attemptNumber": 1, "workerId": "worker-a1b2c3d4", "status": "FAILED", "startedAt": "…", "finishedAt": "…", "errorMessage": "…" },
  { "attemptNumber": 2, "workerId": "worker-e5f6a7b8", "status": "SUCCEEDED", "startedAt": "…", "finishedAt": "…", "errorMessage": null }
]
```

## Cancel a job

```
POST /api/v1/jobs/{id}/cancel
```

| Current status | Outcome | HTTP |
|---|---|---|
| `QUEUED` | Cancelled immediately, never runs | `200`, `outcome: CANCELLED_IMMEDIATELY` |
| `RUNNING` | Best-effort interrupt broadcast | `200`, `outcome: CANCELLATION_REQUESTED` |
| `COMPLETED` / `FAILED` / `CANCELLED` | No-op | `409 Conflict` |

See the root README's "Cancellation semantics" section for why `RUNNING` cancellation isn't
instantaneous.

## Errors

All error responses share one shape:

```json
{
  "timestamp": "2026-01-01T00:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Request failed validation",
  "details": ["type: type is required"]
}
```

## Operational endpoints

- `GET /actuator/health` — liveness/readiness, including Postgres and Redis connectivity
- `GET /actuator/prometheus` — Prometheus-format metrics (see root README "Metrics")
