import net from 'node:net';
import { lookup as systemLookup } from 'node:dns/promises';
import { performance } from 'node:perf_hooks';
import { Metrics } from './metrics.mjs';

// Preserve the existing destination policy and pin the checked IP on connection.
export function publicIPv4(ip) {
  if (net.isIP(ip) !== 4) return false;
  const [a, b, c] = ip.split('.').map(Number);
  return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 192 && b === 0 && [0, 2].includes(c)) || (a === 100 && b >= 64 && b <= 127) || (a === 198 && [18, 19].includes(b)) || (a === 198 && b === 51 && c === 100) || (a === 203 && b === 0 && c === 113));
}

export function createTargetResolver({
  lookup = systemLookup, now = () => performance.now(), metrics = new Metrics(),
  ttlMs = 30_000, maxEntries = 256, maxPending = 64,
} = {}) {
  if (!Number.isFinite(ttlMs) || ttlMs <= 0 || !Number.isInteger(maxEntries) || maxEntries < 1 || !Number.isInteger(maxPending) || maxPending < 1) {
    throw new Error('Invalid DNS cache limits');
  }
  const cache = new Map();
  const pending = new Map();
  let closed = false;

  async function resolve(host, port) {
    if (closed) throw new Error('Resolver closed');
    if (![80, 443].includes(Number(port)) || typeof host !== 'string' || !host || host.length > 253) {
      metrics.increment('dnsInvalidTargets');
      throw new Error('Only public HTTP(S) destinations are allowed');
    }
    const key = host.toLowerCase();
    const hit = cache.get(key);
    if (hit && now() < hit.expires) {
      cache.delete(key); cache.set(key, hit);
      metrics.increment('dnsCacheHits');
      return hit.address;
    }
    cache.delete(key);
    if (pending.has(key)) {
      metrics.increment('dnsCoalesced');
      return pending.get(key);
    }
    if (pending.size >= maxPending) {
      metrics.increment('dnsBusy');
      throw new Error('DNS resolver busy');
    }
    metrics.increment('dnsLookups');
    const started = now();
    // Defer lookup until pending.set() so even synchronous failures are cleaned up.
    const work = Promise.resolve().then(() => lookup(key, { all: true, family: 4 })).then(addresses => {
      if (!addresses.length || addresses.some(a => !publicIPv4(a.address))) {
        metrics.increment('dnsBlocked');
        throw new Error('Private destinations are blocked');
      }
      const address = addresses[0].address;
      if (!closed) {
        if (cache.size >= maxEntries) cache.delete(cache.keys().next().value);
        cache.set(key, { address, expires: now() + ttlMs });
      }
      return address;
    }).catch(error => {
      metrics.increment('dnsFailures');
      throw error;
    }).finally(() => {
      pending.delete(key);
      metrics.observe('dnsLookupMs', now() - started);
    });
    pending.set(key, work);
    return work;
  }

  return {
    resolve,
    snapshot: () => ({ cacheEntries: cache.size, pendingLookups: pending.size, maxEntries, maxPending, ttlMs }),
    close() { closed = true; cache.clear(); },
  };
}
