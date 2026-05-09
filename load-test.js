import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const latency = new Trend('latency');
const throughput = new Counter('throughput');
const activeConnections = new Gauge('active_connections');

// Test configuration
export const options = {
    stages: [
        { duration: '1m', target: 100 },    // Ramp-up to 100 VUs
        { duration: '2m', target: 200 },    // Ramp-up to 200 VUs
        { duration: '5m', target: 250 },    // Stress test at 250 VUs (500+ TPS)
        { duration: '2m', target: 100 },    // Ramp-down
        { duration: '1m', target: 0 }       // Cool down
    ],
    thresholds: {
        'latency{quantile:"0.99"}': ['p(99)<200'], // p99 latency < 200ms
        'latency{quantile:"0.95"}': ['p(95)<100'], // p95 latency < 100ms
        'errors': ['rate<0.01'],                    // Error rate < 1%
        'http_req_duration': ['p(99)<200'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const ACCOUNTS = [
    'acc001', 'acc002', 'acc003', 'acc004', 'acc005',
    'acc006', 'acc007', 'acc008', 'acc009', 'acc010'
];

export function setup() {
    console.log('Setting up test data...');

    // Create test accounts
    for (let i = 0; i < ACCOUNTS.length; i++) {
        const response = http.post(
            `${BASE_URL}/api/v1/accounts?accountId=${ACCOUNTS[i]}&initialBalance=1000000`,
            {}
        );
        check(response, {
            'Account creation successful': (r) => r.status === 200,
        });
    }

    return { accounts: ACCOUNTS };
}

export default function (data) {
    const accounts = data.accounts;

    // Generate random transfer
    const fromIdx = Math.floor(Math.random() * accounts.length);
    let toIdx = Math.floor(Math.random() * accounts.length);
    while (toIdx === fromIdx) {
        toIdx = Math.floor(Math.random() * accounts.length);
    }

    const fromAccount = accounts[fromIdx];
    const toAccount = accounts[toIdx];
    const amount = 100 + Math.floor(Math.random() * 900);
    const transactionId = `txn-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

    // Execute transfer
    const startTime = Date.now();
    const payload = JSON.stringify({
        transactionId,
        fromAccountId: fromAccount,
        toAccountId: toAccount,
        amount: amount.toString(),
        metadata: 'Load test transfer'
    });

    const response = http.post(`${BASE_URL}/api/v1/transfer`, payload, {
        headers: {
            'Content-Type': 'application/json',
        },
        timeout: '30s',
    });

    const endTime = Date.now();
    const duration = endTime - startTime;

    // Record metrics
    latency.add(duration);
    throughput.add(1);
    activeConnections.add(1);

    const isSuccess = response.status === 200;
    errorRate.add(!isSuccess);

    check(response, {
        'Transfer successful': (r) => r.status === 200,
        'Response has transaction ID': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.transactionId === transactionId;
            } catch {
                return false;
            }
        },
        'Latency < 200ms': (r) => duration < 200,
        'Latency < 500ms': (r) => duration < 500,
    });

    if (!isSuccess) {
        console.error(`Transfer failed: ${response.status} - ${response.body}`);
    }

    // Check account balances
    if (Math.random() < 0.1) { // 10% of time
        const balanceResponse = http.get(`${BASE_URL}/api/v1/accounts/${fromAccount}/balance`);
        check(balanceResponse, {
            'Balance check successful': (r) => r.status === 200,
        });
    }

    activeConnections.add(-1);
    sleep(0.1); // Small delay between requests
}

export function teardown(data) {
    console.log('Tearing down test...');
    // Verify final balances
    for (let i = 0; i < data.accounts.length; i++) {
        const response = http.get(`${BASE_URL}/api/v1/accounts/${data.accounts[i]}/balance`);
        check(response, {
            'Final balance check successful': (r) => r.status === 200,
        });
    }
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        'summary.json': JSON.stringify(data),
    };
}

function textSummary(data, options) {
    const { indent = '', enableColors = true } = options;
    let summary = '\n📊 Load Test Summary\n';
    summary += '═'.repeat(50) + '\n';

    // Extract key metrics
    const metrics = data.metrics;

    for (const [key, metric] of Object.entries(metrics)) {
        if (metric.values) {
            const values = metric.values;
            summary += `\n${key}:\n`;
            summary += `${indent}min: ${Math.min(...Object.values(values)).toFixed(2)}\n`;
            summary += `${indent}max: ${Math.max(...Object.values(values)).toFixed(2)}\n`;
            summary += `${indent}avg: ${(Object.values(values).reduce((a,b) => a+b) / Object.values(values).length).toFixed(2)}\n`;
        }
    }

    summary += '\n' + '═'.repeat(50) + '\n';
    return summary;
}
