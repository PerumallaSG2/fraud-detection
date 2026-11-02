// k6 load test for POST /api/transactions. Run against the app started normally (mvn spring-boot:run), see README.
//
//   k6 run -e PROFILE=steps load-test/fraud-load.js       # PROFILE: smoke | steps | sustain (RATE=... for sustain)
//
// Open model (arrival-rate executors): a slow server does not slow the load down; if k6 can't start iterations on
// time it reports dropped_iterations. Two scenarios run side by side:
//   load    - the traffic. POST only; measures POST latency and throughput (202 = Kafka acked the record).
//   sampler - 2 transactions/s that are each polled with GET until scored, as a client-side check of end-to-end time.
//             The main end-to-end measurement uses Kafka record timestamps instead (load-test/e2e-latency.py), so it
//             adds no load at all.
//
// The data is built so that rules only fire where intended: card ids are reused round-robin over CARDS cards (a card
// comes back every CARDS/rate seconds, so velocity never fires), each card has a fixed city (no geo mismatch) and a
// base amount varied by +-25% (never 3x its own average, so no amount spike), and timestamps are "now" written in an
// offset where the local time is midday (no odd hours). Every 100 transactions, 2 are REVIEW (GAMBLING at 02:xx local
// = 30 + 20) and 1 is BLOCK (the same plus 9000.00, an amount spike = 90).

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'smoke';
const CARDS = Number(__ENV.CARDS || 100000);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SAMPLER_RATE = Number(__ENV.SAMPLER_RATE || 2);

const RAMP_S = 15;
const HOLD_S = 60;

// Each stage: ramp to `rate` over RAMP_S, then hold it for `hold` seconds. Stage names are the target rate.
function stagesFor(profile) {
    switch (profile) {
        case 'smoke':
            return [{ rate: 10, hold: 20 }];
        case 'steps':
            return (__ENV.STEPS || '25,50,100,200,400,800,1600').split(',').map(Number)
                .map((rate) => ({ rate, hold: HOLD_S }));
        case 'sustain': {
            const rate = Number(__ENV.RATE);
            if (!rate) throw new Error('PROFILE=sustain needs RATE=<transactions per second>');
            const hold = Number(__ENV.HOLD_S || 300);
            // Stages of a quarter of the target on the way up, then one long hold.
            return [0.25, 0.5, 0.75].map((f) => ({ rate: Math.round(rate * f), hold: 30 }))
                .concat([{ rate, hold }]);
        }
        default:
            throw new Error(`unknown PROFILE ${profile}`);
    }
}

const STAGES = stagesFor(PROFILE);
const TOTAL_S = STAGES.reduce((s, st) => s + RAMP_S + st.hold, 0);

// Per-stage submetrics only show in the summary when a threshold names them; these thresholds never fail.
const thresholds = { dropped_iterations: ['count>=0'] };
for (const st of STAGES) {
    thresholds[`http_req_duration{name:post,scenario:load,phase:hold-${st.rate}}`] = ['max>=0'];
    thresholds[`posts{scenario:load,phase:hold-${st.rate}}`] = ['count>=0'];
    thresholds[`post_errors{scenario:load,phase:hold-${st.rate}}`] = ['count>=0'];
    thresholds[`e2e_sampled_ms{phase:hold-${st.rate}}`] = ['max>=0'];
}
for (const status of ['0', '400', '500', '503']) {
    thresholds[`http_reqs{name:post,status:${status}}`] = ['count>=0'];
}

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
    thresholds,
    scenarios: {
        load: {
            executor: 'ramping-arrival-rate',
            exec: 'load',
            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: Number(__ENV.MAX_VUS || 1000),
            stages: STAGES.flatMap((st) => [
                { target: st.rate, duration: `${RAMP_S}s` },
                { target: st.rate, duration: `${st.hold}s` },
            ]),
        },
        sampler: {
            executor: 'constant-arrival-rate',
            exec: 'sampler',
            rate: SAMPLER_RATE,
            timeUnit: '1s',
            duration: `${TOTAL_S}s`,
            preAllocatedVUs: 20,
            maxVUs: 200,
        },
    },
};

const posts = new Counter('posts');
const postErrors = new Counter('post_errors');
const wrongDecisions = new Counter('wrong_decisions');
const samplerTimeouts = new Counter('sampler_timeouts');
const e2eSampled = new Trend('e2e_sampled_ms', true);

const CITIES = [
    ['Austin', 30.27, -97.74], ['Denver', 39.74, -104.99], ['Seattle', 47.61, -122.33],
    ['Chicago', 41.88, -87.63], ['Boston', 42.36, -71.06], ['Miami', 25.76, -80.19],
    ['Phoenix', 33.45, -112.07], ['Atlanta', 33.75, -84.39],
];
const EVERYDAY = [
    ['Corner Grocery', 'GROCERY'], ['Main St Diner', 'RESTAURANT'], ['Quick Fuel', 'FUEL'],
    ['City Pharmacy', 'PHARMACY'], ['Book Nook', 'RETAIL'], ['Metro Transit', 'TRANSPORT'],
];

export function setup() {
    return { runId: RUN_ID, profile: PROFILE, start: Date.now(), rampS: RAMP_S, stages: STAGES };
}

/** The stage this moment belongs to, e.g. "hold-400" or "ramp-400", from the time since the test started. */
function phase(startMs) {
    let t = (Date.now() - startMs) / 1000;
    for (const st of STAGES) {
        if (t < RAMP_S) return `ramp-${st.rate}`;
        t -= RAMP_S;
        if (t < st.hold) return `hold-${st.rate}`;
        t -= st.hold;
    }
    return 'after';
}

/** ISO-8601 for now, written in the whole-hour offset whose local hour is `localHour` (offsets -12..+14 cover all). */
function nowAtLocalHour(localHour) {
    const now = new Date();
    let offset = ((localHour - now.getUTCHours()) % 24 + 24) % 24;
    if (offset > 14) offset -= 24;
    const local = new Date(now.getTime() + offset * 3600 * 1000).toISOString().slice(0, 23);
    const sign = offset < 0 ? '-' : '+';
    return `${local}${sign}${String(Math.abs(offset)).padStart(2, '0')}:00`;
}

/** Transaction number n on card `card`, with the decision the rules should give it. */
function transaction(prefix, card, n) {
    const [city, latitude, longitude] = CITIES[card % CITIES.length];
    const base = 10 + (card * 7919) % 291;
    const kind = n % 100 === 0 ? 'BLOCK' : (n % 100 === 50 || n % 100 === 51) ? 'REVIEW' : 'APPROVE';
    const body = {
        cardId: `${prefix}-${card}`,
        amount: (base * (0.75 + Math.random() * 0.5)).toFixed(2),
        merchant: EVERYDAY[n % EVERYDAY.length][0],
        merchantCategory: EVERYDAY[n % EVERYDAY.length][1],
        city, latitude, longitude,
        timestamp: nowAtLocalHour(12),
    };
    if (kind !== 'APPROVE') {
        body.merchant = 'Lucky Star Casino';
        body.merchantCategory = 'GAMBLING';
        body.timestamp = nowAtLocalHour(2);
    }
    if (kind === 'BLOCK') {
        body.amount = '9000.00';
    }
    return { body: JSON.stringify(body), expected: kind };
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(tx, tags) {
    const res = http.post(`${BASE_URL}/api/transactions`, tx.body,
        { headers: JSON_HEADERS, tags: Object.assign({ name: 'post' }, tags), timeout: '10s' });
    const ok = check(res, { 'POST 202 with id': (r) => r.status === 202 && !!r.json('id') });
    posts.add(1, tags);
    if (!ok) postErrors.add(1, Object.assign({ status: String(res.status) }, tags));
    return ok ? res.json('id') : null;
}

export function load(data) {
    const n = exec.scenario.iterationInTest;
    post(transaction(`lt-${data.runId}`, n % CARDS, n), { phase: phase(data.start), expected: '' });
}

/** Poll every 25 ms for the first second, then every 250 ms, for up to 30 s. */
export function sampler(data) {
    const n = exec.scenario.iterationInTest;
    // Own cards, few transactions each: the sampler's transactions never affect the load's rules or vice versa.
    const tx = transaction(`lt-${data.runId}-s`, n % 1000, n);
    const tags = { phase: phase(data.start) };
    const started = Date.now();
    const id = post(tx, Object.assign({ expected: tx.expected }, tags));
    if (!id) return;
    while (Date.now() - started < 30000) {
        sleep(Date.now() - started < 1000 ? 0.025 : 0.25);
        const res = http.get(`${BASE_URL}/api/transactions/${id}`, { tags: { name: 'get' }, timeout: '10s' });
        if (res.status === 200) {
            e2eSampled.add(Date.now() - started, tags);
            const decision = res.json('decision');
            if (!check(decision, { 'decision as expected': (d) => d === tx.expected })) {
                wrongDecisions.add(1, { expected: tx.expected, got: String(decision) });
            }
            return;
        }
    }
    samplerTimeouts.add(1, tags);
}

export function handleSummary(data) {
    const out = `load-test/results/${RUN_ID}-summary.json`;
    return {
        stdout: textSummary(data),
        [out]: JSON.stringify({ setup: data.setup_data, metrics: data.metrics }, null, 1),
    };
}

/** A compact per-stage table (the default end-of-test summary does not group by stage). */
function textSummary(data) {
    const m = data.metrics;
    const val = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : null);
    const fmt = (v, d = 1) => (v === null ? '-' : v.toFixed(d));
    const lines = [`run ${RUN_ID} profile ${PROFILE}`,
        'stage(req/s)  posts  achieved/s  errors  POST p50  p95   p99   max (ms)  sampled e2e p50  p95  max (ms)'];
    for (const st of STAGES) {
        const k = `scenario:load,phase:hold-${st.rate}`;
        const count = val(`posts{${k}}`, 'count');
        const d = `http_req_duration{name:post,${k}}`;
        const e = `e2e_sampled_ms{phase:hold-${st.rate}}`;
        lines.push([String(st.rate).padStart(12), String(count ?? '-').padStart(6),
            fmt(count === null ? null : count / st.hold).padStart(11), String(val(`post_errors{${k}}`, 'count') ?? 0).padStart(7),
            fmt(val(d, 'med')).padStart(9), fmt(val(d, 'p(95)')).padStart(5), fmt(val(d, 'p(99)')).padStart(5),
            fmt(val(d, 'max')).padStart(5), fmt(val(e, 'med'), 0).padStart(17), fmt(val(e, 'p(95)'), 0).padStart(5),
            fmt(val(e, 'max'), 0).padStart(5)].join(' '));
    }
    const statuses = ['0', '400', '500', '503'].map((s) => `${s}:${val(`http_reqs{name:post,status:${s}}`, 'count') ?? 0}`);
    lines.push(`POST non-202 by status ${statuses.join(' ')}  dropped_iterations ${val('dropped_iterations', 'count') ?? 0}`
        + `  wrong_decisions ${val('wrong_decisions', 'count') ?? 0}  sampler_timeouts ${val('sampler_timeouts', 'count') ?? 0}`
        + `  total posts ${val('posts', 'count') ?? 0}`);
    return lines.join('\n') + '\n';
}
