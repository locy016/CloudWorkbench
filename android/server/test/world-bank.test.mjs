import { test } from 'node:test';
import assert from 'node:assert/strict';
import { worldBank } from '../src/world-bank.mjs';

const CHINA = { id: 'CHN', iso2Code: 'CN', name: 'China', region: { id: 'EAS' } };
const JAPAN = { id: 'JPN', iso2Code: 'JP', name: 'Japan', region: { id: 'EAS' } };
const WORLD = { id: 'WLD', iso2Code: '1W', name: 'World', region: { id: 'NA' } };
const json = (rows, metadata = {}) => Response.json([{ page: 1, pages: 1, total: rows.length, ...metadata }, rows]);
const observation = (country, year, values = [36.7391, 3544871083631.02, 3167502597276.28]) =>
  ['NE.TRD.GNFS.ZS', 'NE.EXP.GNFS.CD', 'NE.IMP.GNFS.CD'].map((id, index) => ({
    indicator: { id }, countryiso3code: country, date: year, value: values[index]
  }));

test('World Bank lookup fixes the destination and indicators, excludes aggregates, and caches by country and year', async () => {
  const calls = [];
  const bank = worldBank({ request: async (path, options) => {
    const url = new URL(path);
    assert.equal(url.origin, 'https://api.worldbank.org');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(url.searchParams.get('format'), 'json');
    calls.push(url.pathname + url.search);
    if (url.pathname === '/v2/country') return json([CHINA, JAPAN, WORLD]);
    const [, , , country, , indicators] = url.pathname.split('/');
    assert.equal(indicators, 'NE.TRD.GNFS.ZS;NE.EXP.GNFS.CD;NE.IMP.GNFS.CD');
    assert.equal(url.searchParams.get('source'), '2');
    const date = url.searchParams.get('date');
    return json(observation(country, date, country === 'JPN' ? [null, null, null] : undefined),
      { lastupdated: '2026-11-11' });
  } });
  assert.deepEqual((await bank.countries()).countries.map(({ code }) => code), ['CHN', 'JPN']);
  for (const [country, year] of [['WLD','2023'], ['CHN','1959'], ['CHN','9999'], ['../','2024']]) {
    await assert.rejects(bank.trade(country, year), { status: 400 });
  }
  const [first, shared] = await Promise.all([bank.trade('CHN', '2023'), bank.trade('CHN', '2023')]);
  assert.deepEqual(shared, first);
  assert.deepEqual(first, { country: 'CHN', year: 2023, updatedAt: '2026-11-11', stale: false,
    tradePercent: 36.7391, exportsUsd: 3544871083631.02, importsUsd: 3167502597276.28 });
  assert.equal((await bank.trade('CHN', '2024')).year, 2024);
  assert.deepEqual(await bank.trade('JPN', '2023'), { country: 'JPN', year: 2023, updatedAt: '2026-11-11',
    stale: false, tradePercent: null, exportsUsd: null, importsUsd: null });
  assert.equal(calls.length, 4);
});

test('World Bank timeout, malformed observations and partial pages keep only the matching last verified data', async () => {
  let time = Date.UTC(2026, 10, 23), tradeRequests = 0, directoryRequests = 0;
  let outage = false;
  const bank = worldBank({ now: () => time, request: async path => {
    const url = new URL(path);
    if (url.pathname === '/v2/country') {
      directoryRequests++;
      return json([CHINA, JAPAN], outage ? { pages: 2 } : {});
    }
    tradeRequests++;
    if (outage && url.searchParams.get('date') === '2024') throw new Error('upstream timed out');
    return json(observation('CHN', outage ? '2022' : '2023'), { lastupdated: '2026-11-11' });
  } });
  const first = await bank.trade('CHN', '2023');
  outage = true;
  time += 86_400_001;
  assert.equal((await bank.countries()).stale, true);
  assert.deepEqual(await bank.trade('CHN', '2023'), { ...first, stale: true });
  await assert.rejects(bank.trade('CHN', '2024'), { status: 503 });
  await assert.rejects(bank.trade('CHN', '2024'), { status: 503 });
  assert.equal(tradeRequests, 3);
  assert.equal(directoryRequests, 2);
  outage = false;
  time += 30_000;
  assert.deepEqual(await bank.trade('CHN', '2023'), first);
});

test('World Bank zero-row annual envelopes represent unavailable observations without inventing a date', async () => {
  const bank = worldBank({ now: () => Date.UTC(2026, 10, 23), request: async path =>
    new URL(path).pathname === '/v2/country' ? json([JAPAN]) :
      Response.json([{ page: 0, pages: 0, total: 0, lastupdated: null }, null])
  });
  assert.deepEqual(await bank.trade('JPN', '2026'), { country: 'JPN', year: 2026, updatedAt: null,
    stale: false, tradePercent: null, exportsUsd: null, importsUsd: null });
});
