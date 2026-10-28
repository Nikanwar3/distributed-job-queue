# Distributed Job Queue

A background job processing platform: submit a job over HTTP, it's queued durably, a pool of
workers picks it up and runs it with bounded concurrency, retries, and per-job timeouts, and you
can poll its status, fetch its result, cancel it, or inspect its full execution history.

Built with Java 21, Spring Boot 3, PostgreSQL, Redis (Streams), Maven, and Docker Compose.

```
┌────────────┐   POST /jobs    ┌──────────────┐   XADD    ┌───────────────┐
│   Client   │ ───────────────▶│  API node(s) │ ─────────▶│ Redis Stream  │
└────────────┘                 │ (stateless)  │           │ "jobqueue:…"  │
       ▲                       └──────┬───────┘           └──────┬────────┘
       │ GET /jobs/{id}                │ writes job row            │ XREADGROUP
       │                               ▼                           ▼
       │                        ┌──────────────┐           ┌───────────────┐
       └────────────────────────│  PostgreSQL  │◀──────────│ Worker node(s)│
                                 │ (source of   │  updates  │ (N concurrent │
                                 │  truth for   │           │  handlers per │
                                 │  job state)  │           │  instance)    │
                                 └──────────────┘           └───────┬───────┘
                                        ▲                           │
                                        │        ZADD on failure    │
                                        └─────── Redis ZSET ◀───────┘
                                          "jobqueue:delayed"
                                          (scheduled retries)
```

## Why Redis Streams, not a plain list or pub/sub

A `LIST` (`LPUSH`/`BRPOP`) gives you a queue but no memory of who has what — if a worker dies
mid-job, the message is simply gone. Pub/Sub has no persistence or replay at all. Redis Streams
with a **consumer group** give three things this platform leans on directly:

1. **Delivery tracking** — every read is recorded in a per-group Pending Entries List (PEL) with
   the consumer that owns it and how long it's been idle, until it's acknowledged.
2. **Crash recovery primitives** — `XPENDING` + `XCLAIM` let another process reclaim a message
   whose owner has gone quiet, without any extra bookkeeping.
3. **At-least-once delivery semantics that are explicit, not implied** — nothing here claims
   exactly-once; the platform is built around at-least-once delivery plus idempotent state
   transitions (see "Delivery guarantees" below).

PostgreSQL remains the **source of truth** for job state throughout — the stream only ever carries
a job id as a wake-up signal. This split matters: Redis data is coordination/ephemeral (safe to
lose and rebuild), Postgres data is the durable record a client's `GET /jobs/{id}` depends on.

Every Redis key this platform touches (the stream, its consumer group, the delayed-retry set, the
cancellation channel) is prefixed by `jobqueue.namespace` (default `jobqueue`). Beyond letting
multiple independent deployments share one Redis instance without colliding, this is what keeps
the integration test suite honest: a handful of tests need a differently-configured Spring context
(disabled worker, a smaller concurrency cap), and each of those gets its own namespace so its
worker/scheduler isn't competing with every other test class's live worker over one shared stream.

## Concurrency decisions

- **Per-instance bounded concurrency**: each instance runs one thread blocked on `XREADGROUP` and
  a fixed-size thread pool (`jobqueue.worker-concurrency`) that actually executes handlers. That
  pool size is the hard cap on how many jobs one instance runs at once — it does not grow under
  load. Horizontal scale comes from running more instances (`docker compose up --scale worker=N`),
  each a separate consumer in the same group, so Redis fans work out across them automatically.
- **Compare-and-swap state transitions**: a job only starts executing if a transaction can flip it
  from `QUEUED` to `RUNNING` while its JPA `@Version` is unchanged (`WorkerJobLifecycle.tryStartRunning`).
  If two deliveries of the same job id race — a normal delivery racing a recovery-scheduler
  redelivery, for instance — exactly one wins the optimistic-lock check; the other sees the failure
  and backs off without ever calling the handler. This is the mechanism that makes duplicate
  delivery safe, not a deduplication cache.
- **Losing a CAS race gets its own transaction, deliberately**: the claim attempt above runs inside
  a `REQUIRES_NEW` transaction (`TransactionTemplate`), not the enclosing `@Transactional` method
  boundary. A failed optimistic-lock check leaves the underlying database transaction aborted —
  catching the exception and continuing in that *same* transaction doesn't work, because the next
  statement (or the commit Spring attempts when the method returns normally) fails anyway
  (`UnexpectedRollbackException` / Postgres `25P02`, "current transaction is aborted"). The same
  pattern protects idempotent submission's unique-constraint race in `JobService.submit`. Both were
  real bugs caught by the integration suite running in CI against a real Postgres, not something
  caught by inspection — exactly the case for running them there rather than trusting that the code
  "looks correct."
- **Pooled Redis connections, deliberately**: Lettuce's default is to share one native connection
  across every operation on a `RedisTemplate`. The worker's `XREADGROUP` call blocks that
  connection for up to `consumer-block-ms` while waiting for new messages — on a shared connection,
  that would periodically stall unrelated Redis calls application-wide (API status reads,
  cancellation broadcasts, metrics). `spring.data.redis.lettuce.pool` is enabled specifically so the
  blocking consumer loop checks out its own connection instead of starving everything else.
- **Timeouts via thread interruption**: a per-job watchdog (`ScheduledExecutorService`) interrupts
  the executing thread if the handler hasn't returned within `timeoutSeconds`. This only works
  cleanly for interruptible handlers (blocking I/O, `Thread.sleep`, anything that checks
  `Thread.interrupted()` in a loop) — a handler in a tight uninterruptible native call will not be
  stopped early. All four sample handlers are interruptible; document this requirement for any
  handler you add.

## Job lifecycle

```
QUEUED ──────▶ RUNNING ──────▶ COMPLETED
  │               │
  │               ├──────────▶ QUEUED        (retry scheduled, backoff applied)
  │               └──────────▶ FAILED         (retries exhausted, or non-retryable)
  │
  └──────────────────────────▶ CANCELLED      (cancelled while still queued)
                  RUNNING ───▶ CANCELLED      (best-effort, cooperative — see below)
```

Retries use exponential backoff with jitter: `base * 2^(attempt-1)`, capped, ±25% jitter to avoid
synchronized retry storms (`BackoffCalculator`). A scheduled retry is held in a Redis sorted set
(`jobqueue:delayed`, scored by due-time) and promoted back onto the stream by
`DelayedRetryScheduler` once due — `ZREM`'s atomic return value ensures that if multiple instances
poll the same due retry concurrently, only one of them republishes it.

## Delivery guarantees and consistency limitations

**This platform provides at-least-once execution, not exactly-once.** Concretely:

- A job can be executed more than once if a handler's side effect (e.g. calling an external API)
  completes but the process crashes before the platform records that success. The CAS transition
  prevents the *platform* from double-running a job it knows is in progress, but it cannot prevent
  a crash in the narrow window between "handler returned" and "transaction committed."
- Handlers should be idempotent with respect to their own side effects wherever the underlying
  operation allows it (e.g. use the job id as an idempotency key against downstream systems). This
  is a property of the handler, not something the platform can enforce generically — it's called
  out explicitly in `JobHandler`'s Javadoc.
- Recovery of abandoned jobs (`AbandonedJobRecoveryScheduler`) only reclaims a message after it has
  been idle longer than `jobqueue.recovery-min-idle-ms` (default 60s, deliberately well above any
  sane per-job timeout). A job that is merely slow — not crashed — will never be touched by
  recovery as long as its own timeout is smaller than that threshold; **this invariant
  (`timeoutSeconds` < `recovery-min-idle-ms` for every job type) is an operational requirement**,
  not something enforced in code today.
- Submission idempotency (`idempotencyKey`) is enforced by a unique database constraint, not a
  distributed lock — two concurrent submissions with the same key will have one succeed and the
  other receive the winner's job back after catching the constraint violation. This is correct, but
  it means the loser's request briefly touches the database before backing off.

## Cancellation semantics

- A **queued** job is cancelled synchronously under a pessimistic row lock — it is guaranteed to
  never execute, and the API returns `CANCELLED_IMMEDIATELY`.
- A **running** job can only be cancelled on a best-effort basis: the platform sets a
  `cancelRequested` flag and broadcasts the job id over a Redis Pub/Sub channel
  (`jobqueue:cancel`). Whichever instance is actually running it (if any — Pub/Sub has no delivery
  guarantee, but the running instance is, by definition, up and subscribed) looks the job id up in
  its local in-memory registry and interrupts the thread. The API returns `CANCELLATION_REQUESTED`,
  not confirmation that it stopped.
- A genuinely stronger guarantee (e.g. "cancel completes within N seconds or returns an error")
  would require either a synchronous RPC to the owning instance or polling for the cancellation to
  take effect — both are reasonable extensions, deliberately left out here to keep the mechanism
  simple and honest about what it actually guarantees.

## Observability

- **Structured logs**: JSON via `logstash-logback-encoder` in every profile except `local`, which
  uses a human-readable pattern for development. `jobId` and `workerId` are attached via MDC
  wherever a job is being processed.
- **Metrics** (`/actuator/prometheus`): `jobqueue.jobs.{submitted,completed,failed,cancelled,retried,recovered,duplicate_delivery_skipped}`
  counters, plus `jobqueue.queue.wait_time` and `jobqueue.execution.duration` timers.
- **Health** (`/actuator/health`): liveness/readiness probes, plus Spring Boot's auto-configured
  Postgres and Redis health indicators.

## Running it

```bash
docker compose up -d --build
docker compose up -d --scale worker=3   # add more worker capacity

curl -s localhost:8080/actuator/health
./sample-workloads/submit-jobs.sh
```

Swagger UI: http://localhost:8080/swagger-ui.html — full endpoint reference in
[`docs/API.md`](docs/API.md).

### Local development without Docker

```bash
# requires a local Postgres (db/user/password: jobqueue) and Redis on default ports
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Running the tests

```bash
mvn test                 # unit tests only, no external dependencies
mvn verify                # includes Testcontainers-backed integration tests (needs Docker)
```

Integration tests (`src/test/java/com/jobqueue/integration`) spin up real Postgres and Redis
containers and cover: concurrent idempotent submission (20 racing requests collapse to one job),
duplicate stream delivery (direct CAS race on `WorkerJobLifecycle`), retry/backoff to both success
and exhaustion, per-job timeout enforcement, queued- and running-job cancellation, bounded worker
concurrency (asserts the pool reaches *and never exceeds* its configured limit), and abandoned-job
recovery for both a job that crashed mid-execution and one whose consumer died before claiming it.

## Load testing

See [`load-test/README.md`](load-test/README.md) for the k6 script and methodology.

### Measured results

Not run against this specific build. The load test needs the full stack up (Postgres, Redis, API,
workers) — the development environment this project was built in doesn't have Docker available and
a from-source Postgres build wasn't practical to wait on, so no throughput/latency numbers are
published here. The script in `load-test/k6-script.js` is complete and ready to run; `docker compose
up -d --build && k6 run load-test/k6-script.js` against your own machine will give you real numbers
for whatever hardware you're deploying to — which is also the only way they'd actually mean anything
for your deployment target anyway. What *is* verified in this repository: all 12 unit tests pass
(`mvn test`), the full test suite (unit + Testcontainers integration) compiles cleanly, and the
application builds and packages successfully with `mvn package`.

## Project layout

```
src/main/java/com/jobqueue/
  domain/      Job, JobExecution entities and their status enums
  repository/  Spring Data JPA repositories
  service/     client-facing operations (submit, cancel, list, history)
  worker/      consumer loop, handler registry, lifecycle CAS transitions, sample handlers
  queue/       Redis Streams producer, delayed-retry ZSET, cancellation pub/sub
  recovery/    abandoned-message recovery, delayed-retry promotion (scheduled tasks)
  web/         REST controller, DTOs, centralized exception handling
  config/      configuration properties, Redis/OpenAPI wiring
  metrics/     Micrometer instrumentation
```

## Scope notes

This is a portfolio-scoped implementation of a real architectural pattern, not a production
deployment. Deliberately out of scope: multi-tenant auth/authz, job priorities, payload size
limits beyond basic validation, dead-letter inspection tooling, and horizontal Postgres scaling
(a single instance is the bottleneck here, as it is the source of truth for every state
transition — sharding or read replicas would be the next step for a higher-throughput deployment).
