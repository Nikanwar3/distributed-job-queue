// Reproducible load test for the Background Job Processing Platform.
//
// Measures:
//   - submission throughput (HTTP layer, k6's own http_req metrics)
//   - end-to-end job completion rate, by polling each submitted job until terminal
//   - queue wait time and execution duration, computed from server timestamps
//     (started_at - created_at, completed_at - started_at) returned by GET /jobs/{id}
//
// Usage:
//   k6 run -e BASE_URL=http://localhost:8080 -e VUS=20 -e DURATION=30s load-test/k6-script.js
//
// Requires k6 (https://k6.io). Results depend entirely on the machine and configuration you run
// it against — this repository does not ship pre-recorded numbers; run it yourself and record
// what you observe (see load-test/README.md).

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const queueWaitTrend = new Trend('jobqueue_queue_wait_ms');
const executionTrend = new Trend('jobqueue_execution_ms');
const completedCounter = new Counter('jobqueue_completed_total');
const failedCounter = new Counter('jobqueue_failed_total');

export const options = {
    vus: Number(__ENV.VUS || 10),
    duration: __ENV.DURATION || '30s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
    },
};

export default function () {
    const payload = JSON.stringify({
        type: 'fibonacci',
        payload: { n: 20 },
        timeoutSeconds: 10,
        maxRetries: 2,
    });

    const submitRes = http.post(`${BASE_URL}/api/v1/jobs`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(submitRes, { 'submit returned 201': (r) => r.status === 201 });
    if (submitRes.status !== 201) {
        return;
    }

    const jobId = submitRes.json('id');

    // Poll until terminal, up to 10s.
    let job;
    for (let i = 0; i < 20; i++) {
        sleep(0.5);
        const getRes = http.get(`${BASE_URL}/api/v1/jobs/${jobId}`);
        job = getRes.json();
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
            break;
        }
    }

    if (!job) {
        return;
    }

    if (job.status === 'COMPLETED') {
        completedCounter.add(1);
        if (job.startedAt && job.createdAt) {
            queueWaitTrend.add(new Date(job.startedAt) - new Date(job.createdAt));
        }
        if (job.completedAt && job.startedAt) {
            executionTrend.add(new Date(job.completedAt) - new Date(job.startedAt));
        }
    } else if (job.status === 'FAILED') {
        failedCounter.add(1);
    }
}
