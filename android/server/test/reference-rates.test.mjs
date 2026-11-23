import { test } from 'node:test';
import assert from 'node:assert/strict';
import { referenceRates } from '../src/reference-rates.mjs';

const daily = (entries, date = '2026-11-20') => `<?xml version="1.0" encoding="UTF-8"?>
<gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
  xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
  <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
  <Cube><Cube time='${date}'>${entries}</Cube></Cube>
</gesmes:Envelope>`;
const valid = daily("<Cube currency='USD' rate='1.0990'/><Cube currency='CNY' rate='7.3388'/>");
const xmlResponse = xml => new Response(xml, { headers: { 'content-type': 'text/xml; charset=UTF-8' } });

test('reference rates stay on the fixed ECB feed and share requests and the warm cache', async () => {
  let time = 1_000, requests = 0, respond;
  const read = referenceRates({ now: () => time, request: (url, options) => {
    assert.equal(url, 'https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    requests++;
    return new Promise(resolve => { respond = resolve; });
  } });
  const first = read(), second = read();
  respond(xmlResponse(valid));
  const sample = await first;
  assert.deepEqual(await second, sample);
  assert.deepEqual(sample, { date: '2026-11-20', fetchedAt: time, stale: false,
    rates: [{ currency: 'USD', rate: '1.0990' }, { currency: 'CNY', rate: '7.3388' }] });
  time += 899_999;
  assert.deepEqual(await read(), sample);
  assert.equal(requests, 1);
});

test('reference rates mark the last verified sample stale and retry after a bounded delay', async () => {
  let time = 0, requests = 0;
  const read = referenceRates({ now: () => time, request: async () => {
    requests++;
    if (requests === 2) throw new Error('upstream disconnected');
    return xmlResponse(requests === 1 ? valid : daily("<Cube currency='USD' rate='1.09'/>", '2026-11-23'));
  } });
  const original = await read();
  time += 900_000;
  assert.deepEqual(await read(), { ...original, stale: true });
  time += 59_999;
  assert.equal((await read()).stale, true);
  assert.equal(requests, 2);
  time++;
  assert.equal((await read()).date, '2026-11-23');
  assert.equal((await read()).stale, false);
  assert.equal(requests, 3);
});

test('invalid upstream results are rejected without exposing or returning unverified data', async () => {
  for (const response of [
    Response.redirect('https://example.invalid'),
    new Response('<html>unavailable</html>', { headers: { 'content-type': 'text/html' } }),
    xmlResponse(daily("<Cube currency='USD' rate='0.00'/>")),
    xmlResponse(daily("<Cube currency='USD' rate='1.2'/>", '2026-02-30')),
    xmlResponse(daily("<Cube currency='USD' rate='1.2'/><Cube currency='USD' rate='2.1'/>")),
    xmlResponse('<!DOCTYPE Cube [<!ENTITY invalid "ignored">]>' + valid),
    xmlResponse(' '.repeat(32 * 1024) + valid)
  ]) {
    let calls = 0;
    const read = referenceRates({ request: async () => { calls++; return response; } });
    await assert.rejects(read(), { status: 503, message: '暂时无法取得欧洲央行参考汇率' });
    await assert.rejects(read(), { status: 503 });
    assert.equal(calls, 1);
  }
});
