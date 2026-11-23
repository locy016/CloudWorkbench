const ECB_FEED = 'https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml';
const CACHE_MS = 15 * 60_000;
const RETRY_MS = 60_000;
const MAX_XML_BYTES = 32 * 1024;
const unavailable = () => Object.assign(new Error('暂时无法取得欧洲央行参考汇率'), { status: 503 });

function parseDailyRates(xml) {
  const days = [...xml.matchAll(/<Cube\s+time=(['"])(\d{4}-\d{2}-\d{2})\1\s*>([\s\S]*?)<\/Cube>/g)];
  if (days.length !== 1 || /<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('Invalid ECB daily feed');
  const [, , date, entries] = days[0];
  if (new Date(date).toISOString().slice(0, 10) !== date) throw new Error('Invalid ECB reference date');
  const entry = /<Cube\s+currency=(['"])([A-Z]{3})\1\s+rate=(['"])(\d{1,12}(?:\.\d{1,12})?)\3\s*\/>/g;
  const rates = [...entries.matchAll(entry)].map(([, , currency, , rate]) => ({ currency, rate }));
  if (!rates.length || rates.length > 100 || entries.replace(entry, '').trim() ||
      new Set(rates.map(({ currency }) => currency)).size !== rates.length ||
      rates.some(({ currency, rate }) => currency === 'EUR' || Number(rate) <= 0)) {
    throw new Error('Invalid ECB reference rates');
  }
  return { date, rates };
}

async function getDailyRates(request) {
  const response = await request(ECB_FEED, { redirect: 'error', signal: AbortSignal.timeout(8000) });
  if (!response.ok || !response.body ||
      !/^(?:application|text)\/xml(?:\s*;|$)/i.test(response.headers.get('content-type') || '')) {
    await response.body?.cancel();
    throw new Error('Unexpected ECB response');
  }
  let bytes = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > MAX_XML_BYTES) throw new Error('ECB response is too large');
    chunks.push(chunk);
  }
  return parseDailyRates(Buffer.concat(chunks).toString('utf8'));
}

export function referenceRates({ request = fetch, now = Date.now } = {}) {
  let latest, inFlight, nextFetch = 0, stale = false;
  return async () => {
    if (inFlight) return inFlight;
    if (now() < nextFetch) {
      if (!latest) throw unavailable();
      return { ...latest, stale };
    }
    inFlight = (async () => {
      try {
        const result = await getDailyRates(request);
        const fetchedAt = now();
        latest = { ...result, fetchedAt };
        nextFetch = fetchedAt + CACHE_MS;
        stale = false;
      } catch {
        nextFetch = now() + RETRY_MS;
        stale = true;
        if (!latest) throw unavailable();
      }
      return { ...latest, stale };
    })().finally(() => { inFlight = null; });
    return inFlight;
  };
}
