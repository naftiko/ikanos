// A constant request rate on one endpoint of the benchmark capability (issue #737).
// RATE requests per second for DURATION, whatever the latency: an open model, as real clients are.
import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 50);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: Math.max(10, rate),
      maxVUs: Math.max(50, rate * 4),
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`http://ikanos:8080${__ENV.ENDPOINT || '/ships'}`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
