import { performance } from 'node:perf_hooks';

// Fixed buckets and fixed call-site names: never retain URLs, hosts or credentials.
const bucketsMs = [1, 5, 10, 25, 50, 100, 250, 500, 1000, 3000, 10000, 30000];

function percentileUpperBound(timing, fraction) {
  const rank = Math.ceil(timing.count * fraction);
  let count = 0;
  for (let i = 0; i < timing.buckets.length; i++) {
    count += timing.buckets[i];
    if (count >= rank) return bucketsMs[i] ?? timing.maxMs;
  }
}

export class Metrics {
  #started = performance.now();
  #counters = new Map();
  #timings = new Map();

  increment(name, amount = 1) {
    this.#counters.set(name, (this.#counters.get(name) || 0) + amount);
  }

  observe(name, ms) {
    if (!Number.isFinite(ms) || ms < 0) return;
    let timing = this.#timings.get(name);
    if (!timing) {
      timing = { count: 0, totalMs: 0, maxMs: 0, buckets: Array(bucketsMs.length + 1).fill(0) };
      this.#timings.set(name, timing);
    }
    timing.count++;
    timing.totalMs += ms;
    timing.maxMs = Math.max(timing.maxMs, ms);
    const bucket = bucketsMs.findIndex(bound => ms <= bound);
    timing.buckets[bucket < 0 ? bucketsMs.length : bucket]++;
  }

  snapshot() {
    return {
      uptimeMs: performance.now() - this.#started,
      counters: Object.fromEntries(this.#counters),
      timings: Object.fromEntries([...this.#timings].map(([name, timing]) => [name, {
        count: timing.count,
        meanMs: timing.totalMs / timing.count,
        maxMs: timing.maxMs,
        p50UpperBoundMs: percentileUpperBound(timing, 0.5),
        p95UpperBoundMs: percentileUpperBound(timing, 0.95),
        // Non-cumulative histogram; last bucket means > 30000 ms.
        bucketUpperBoundsMs: [...bucketsMs, null],
        bucketCounts: [...timing.buckets],
      }])),
    };
  }
}
