// Load test of the agent gateway: thousands of operations a second, each a reserve and then a settle or a release.
// The agent's hard limit must hold however hard it is hit: k6 fails the run if more was settled than the limit, and
// tests/load/load.py check reads the budget in Redis afterwards. See tests/load/README.md.
//
//   k6 run -e SECRET=... -e LIMIT=500000 -e RATE=2000 -e DURATION=60s tests/load/spend.js
import http from 'k6/http';
import { check } from 'k6';
import { randomBytes } from 'k6/crypto';
import { Counter, Rate } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8081';
const SECRET = __ENV.SECRET;
const LIMIT = Number(__ENV.LIMIT || 500000);
const AMOUNT = Number(__ENV.AMOUNT || 10);
const RATE = Number(__ENV.RATE || 2000);
const DURATION = __ENV.DURATION || '60s';
const RELEASE_SHARE = Number(__ENV.RELEASE_SHARE || 0.1);

const authorized = new Counter('authorized');
const refused = new Counter('refused_budget');
const settledAmount = new Counter('settled_amount');
const unexpected = new Counter('unexpected');
const accepted = new Rate('accepted_or_refused');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    spend: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.ceil(RATE / 10)),
      maxVUs: Math.max(200, RATE),
    },
  },
  thresholds: {
    // the hard limit: what was charged can never be more than it
    settled_amount: [`count<=${LIMIT}`],
    // every answer is a reserve (200) or a budget refusal (402); anything else is a bug or an overload
    unexpected: ['count==0'],
    accepted_or_refused: ['rate==1'],
    'http_req_duration{step:authorize}': ['p(99)<250'],
  },
};

function uuid() {
  const b = new Uint8Array(randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function setup() {
  if (!SECRET) {
    throw new Error('SECRET is not set: run tests/load/load.py seed first');
  }
}

export default function () {
  const id = uuid();
  const headers = { Authorization: `Bearer ${SECRET}`, 'Content-Type': 'application/json', 'Idempotency-Key': id };
  const r = http.post(`${GATEWAY}/v1/spend/authorize`,
    JSON.stringify({ vendor: 'Нагрузка', kind: 'paid_api', amount: AMOUNT, taskId: 'load' }),
    { headers, tags: { step: 'authorize' }, responseCallback: http.expectedStatuses(200, 402) });
  const known = r.status === 200 || r.status === 402;
  accepted.add(known);
  if (!known) {
    unexpected.add(1, { status: String(r.status) });
    return;
  }
  if (r.status === 402) {
    refused.add(1);
    return;
  }
  authorized.add(1);
  const body = { Authorization: `Bearer ${SECRET}`, 'Content-Type': 'application/json' };
  if (Math.random() < RELEASE_SHARE) {
    const released = http.post(`${GATEWAY}/v1/spend/release`, JSON.stringify({ authorizationId: id }),
      { headers: body, tags: { step: 'release' } });
    check(released, { 'release 200': (x) => x.status === 200 });
    return;
  }
  const actual = Math.round(AMOUNT * (0.5 + Math.random() / 2) * 100) / 100;  // never more than reserved
  const settled = http.post(`${GATEWAY}/v1/spend/settle`, JSON.stringify({ authorizationId: id, amount: actual }),
    { headers: body, tags: { step: 'settle' } });
  if (check(settled, { 'settle 200': (x) => x.status === 200 })) {
    settledAmount.add(actual);
  } else {
    unexpected.add(1, { status: String(settled.status), step: 'settle' });
  }
}
