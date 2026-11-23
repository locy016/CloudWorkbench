const API = 'https://api.worldbank.org/v2';
const INDICATORS = {
  'NE.TRD.GNFS.ZS': 'tradePercent',
  'NE.EXP.GNFS.CD': 'exportsUsd',
  'NE.IMP.GNFS.CD': 'importsUsd',
};
const DAY = 86_400_000;
const unavailable = () => Object.assign(new Error('暂时无法读取世界银行数据'), { status: 503 });
const badQuery = () => Object.assign(new Error('请选择有效的国家或经济体和统计年份'), { status: 400 });

function cachedLookup(read, now, maxEntries, ttl) {
  const entries = new Map();
  return async key => {
    let entry = entries.get(key) ?? {};
    entries.delete(key);
    entries.set(key, entry);
    if (entries.size > maxEntries) entries.delete(entries.keys().next().value);

    if (entry.pending) return entry.pending;
    if (now() >= (entry.until ?? 0)) {
      entry.pending = (async () => {
        try {
          entry.value = await read(key);
          entry.stale = false;
          entry.until = now() + ttl;
        } catch {
          entry.stale = true;
          entry.until = now() + 30_000;
        } finally {
          entry.pending = null;
        }
        if (!entry.value) throw unavailable();
        return { ...entry.value, stale: entry.stale };
      })();
      return entry.pending;
    }
    if (!entry.value) throw unavailable();
    return { ...entry.value, stale: entry.stale };
  };
}

async function readJson(request, path, params) {
  const url = new URL(API + path);
  url.search = new URLSearchParams({ ...params, format: 'json' }).toString();
  const response = await request(url.href, {
    redirect: 'error', signal: AbortSignal.timeout(10_000), headers: { Accept: 'application/json' }
  });
  if (!response.ok || !response.body ||
      !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') || '')) {
    await response.body?.cancel();
    throw new Error('Unexpected World Bank response');
  }
  let size = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > 256 * 1024) throw new Error('World Bank response is too large');
    chunks.push(chunk);
  }
  const [meta, rows] = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (meta?.page === 0 && meta.pages === 0 && meta.total === 0 && rows === null) return { meta, rows: [] };
  if (meta?.page !== 1 || meta.pages !== 1 || !Array.isArray(rows) || meta.total !== rows.length) {
    throw new Error('Invalid or incomplete World Bank response');
  }
  return { meta, rows };
}

async function getCountries(request) {
  const { rows } = await readJson(request, '/country', { per_page: '400' });
  const countries = rows.filter(row => row.region?.id && row.region.id !== 'NA').map(row => {
    if (!/^[A-Z]{3}$/.test(row.id) || !/^[A-Z]{2}$/.test(row.iso2Code) ||
        typeof row.name !== 'string' || !row.name.trim() || row.name.length > 100) {
      throw new Error('Invalid country directory');
    }
    return { code: row.id, iso2: row.iso2Code, name: row.name };
  });
  if (!countries.length || new Set(countries.map(({ code }) => code)).size !== countries.length) {
    throw new Error('Invalid country directory');
  }
  return { countries };
}

async function getTrade(request, key) {
  const [country, year] = key.split(':');
  const series = Object.keys(INDICATORS).join(';');
  const { meta, rows } = await readJson(request, `/country/${country}/indicator/${series}`,
    { source: '2', date: year, per_page: '10' });
  if (!rows.length) {
    return { country, year: Number(year), updatedAt: null, tradePercent: null, exportsUsd: null, importsUsd: null };
  }
  const values = {};
  for (const row of rows) {
    const field = INDICATORS[row.indicator?.id];
    if (!Object.hasOwn(INDICATORS, row.indicator?.id) || Object.hasOwn(values, field) ||
        row.countryiso3code !== country || row.date !== year ||
        !(row.value === null || Number.isFinite(row.value))) {
      throw new Error('Invalid World Bank observation');
    }
    values[field] = row.value;
  }
  if (Object.keys(values).length !== Object.keys(INDICATORS).length ||
      typeof meta.lastupdated !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(meta.lastupdated) ||
      new Date(meta.lastupdated).toISOString().slice(0, 10) !== meta.lastupdated) {
    throw new Error('Missing World Bank statistic or dataset date');
  }
  return { country, year: Number(year), updatedAt: meta.lastupdated, ...values };
}

export function worldBank({ request = fetch, now = Date.now } = {}) {
  const loadCountries = cachedLookup(() => getCountries(request), now, 1, DAY);
  const loadTrade = cachedLookup(key => getTrade(request, key), now, 128, DAY / 2);
  return {
    countries: () => loadCountries('countries'),
    async trade(country, year) {
      if (!/^[A-Z]{3}$/.test(country ?? '') || !/^\d{4}$/.test(year ?? '') ||
          Number(year) < 1960 || Number(year) > new Date(now()).getUTCFullYear()) throw badQuery();
      const { countries } = await loadCountries('countries');
      if (!countries.some(option => option.code === country)) throw badQuery();
      return loadTrade(`${country}:${year}`);
    }
  };
}
