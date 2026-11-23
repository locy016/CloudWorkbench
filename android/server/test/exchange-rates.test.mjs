import assert from 'node:assert/strict';
import { test } from 'node:test';
import { exchangeRates } from '../src/exchange-rates.mjs';

const rates = { USD: '1', CNY: '7.12', EUR: '0.86', JPY: '150.123', GBP: '0.74',
  AUD: '1.5', CAD: '1.4', HKD: '7.8', CHF: '0.8' };

test('current fiat rates use the fixed USD feed and share bounded refreshes', async () => {
  let time = Date.UTC(2026, 10, 23);
  let requests = 0;
  let fail = false;
  const read = exchangeRates({ now: () => time, request: async (url, options) => {
    requests++;
    assert.equal(url, 'https://api.coinbase.com/v2/exchange-rates?currency=USD');
    assert.equal(options.redirect, 'error');
    if (fail) return new Response(null, { status: 500 });
    return Response.json({ data: { currency: 'USD', rates: { ...rates, BTC: '0.01', SGD: '0', KRW: 'invalid' } } });
  } });
  const expected = { base: 'USD', provider: 'Coinbase', fetchedAt: new Date(time).toISOString(), rates, stale: false };
  assert.deepEqual(await Promise.all([read(), read({ refresh: true })]), [expected, expected]);
  assert.deepEqual(await read({ refresh: true }), expected);
  assert.equal(requests, 1);
  time += 5000;
  assert.deepEqual(await read(), expected);
  fail = true;
  assert.deepEqual(await read({ refresh: true }), { ...expected, stale: true });
  assert.deepEqual(await read({ refresh: true }), { ...expected, stale: true });
  assert.equal(requests, 2);
  time += 15_000;
  fail = false;
  assert.deepEqual(await read(), { ...expected, fetchedAt: new Date(time).toISOString() });
  assert.equal(requests, 3);
});

test('absent or unusable current quotes fail without inventing a snapshot', async () => {
  let requests = 0;
  const read = exchangeRates({ request: async () => {
    requests++;
    return Response.json({ data: { currency: 'USD', rates: { ...rates, USD: '2' } } });
  } });
  await assert.rejects(read(), { status: 503 });
  await assert.rejects(read({ refresh: true }), { status: 503 });
  assert.equal(requests, 1);
});
