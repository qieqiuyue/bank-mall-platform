import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ============================================================================
// payment-load.js — k6 压测脚本（JMeter 不可用时备用）
// 用法: k6 run --vus 50 --duration 180s payment-load.js
// 需要: k6 二进制 (https://k6.io/)
// ============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://10.0.0.31:30080';
const ACCOUNTS = [
  'USER001', 'USER002', 'USER003', 'USER004', 'USER005',
  'USER006', 'USER007', 'USER008', 'USER009', 'USER010'
];

export const options = {
  stages: [
    { duration: '10s', target: 50 },    // ramp-up
    { duration: '180s', target: 50 },   // steady state
    { duration: '10s', target: 0 },     // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(99)<3000'],   // P99 < 3s
    http_req_failed: ['rate<0.1'],      // 错误率 < 0.1%
  },
};

export default function () {
  const account = ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
  const amount = Math.floor(Math.random() * 5) + 1;
  const idempotencyKey = `load-${uuidv4()}`;

  const payload = JSON.stringify({
    payerAccount: account,
    payeeAccount: 'MALL-SETTLEMENT',
    amount: amount,
    idempotencyKey: idempotencyKey,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(`${BASE_URL}/api/payments`, payload, params);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });

  // think time: 100-300ms
  sleep(Math.random() * 0.2 + 0.1);
}
