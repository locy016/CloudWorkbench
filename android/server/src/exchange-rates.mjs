const endpoint = 'https://api.coinbase.com/v2/exchange-rates?currency=USD';
const currencies = ['USD', 'CNY', 'EUR', 'HKD', 'JPY', 'GBP', 'AUD', 'CAD', 'CHF', 'SGD', 'KRW', 'NZD',
  'INR', 'BRL', 'ZAR', 'AED', 'MXN', 'SEK', 'NOK', 'DKK', 'THB', 'TWD', 'VND', 'IDR'];
const ratePattern = /^\d{1,15}(?:\.\d{1,32})?$/;
const unavailable = () => Object.assign(new Error('暂时无法取得当前汇率'), { status: 503 });

async function readRates(request) {
  const response = await request(endpoint, {
    redirect: 'error', signal: AbortSignal.timeout(6000), headers: { Accept: 'application/json' }
  });
  if (!response.ok || !response.body || !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') || '')) {
    await response.body?.cancel();
    throw unavailable();
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > 256 * 1024) throw unavailable();
    chunks.push(chunk);
  }
  const { data } = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (data?.currency !== 'USD' || !data.rates || Number(data.rates.USD) !== 1) throw unavailable();
  const rates = {};
  for (const code of currencies) {
    const value = data.rates[code];
    if (typeof value === 'string' && ratePattern.test(value) && Number(value) > 0) rates[code] = value;
  }
  if (!rates.USD || !rates.EUR || !rates.CNY || Object.keys(rates).length < 8) throw unavailable();
  return rates;
}

export function exchangeRates({ request = fetch, now = Date.now } = {}) {
  let snapshot, pending;
  let stale = false;
  let lastAttempt = -Infinity;
  return async function currentRates({ refresh = false } = {}) {
    if (pending) return pending;
    const elapsed = now() - lastAttempt;
    const wait = stale ? 15_000 : refresh ? 5_000 : 30_000;
    if (elapsed >= wait) {
      lastAttempt = now();
      pending = (async () => {
        try {
          const rates = await readRates(request);
          snapshot = { base: 'USD', provider: 'Coinbase', fetchedAt: new Date(now()).toISOString(), rates };
          stale = false;
        } catch {
          stale = true;
        } finally {
          pending = undefined;
        }
        if (!snapshot) throw unavailable();
        return { ...snapshot, stale };
      })();
      return pending;
    }
    if (!snapshot) throw unavailable();
    return { ...snapshot, stale };
  };
}
