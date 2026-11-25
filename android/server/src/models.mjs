export function codexModels(codex) {
  let cached, expires = 0, inFlight;
  return async function list() {
    if (cached && Date.now() < expires) return cached;
    if (inFlight) return inFlight;
    inFlight = (async () => {
      await codex.start();
      const models = new Map(), cursors = new Set();
      let cursor;
      do {
        const page = await codex.rpc('model/list', { limit: 100, includeHidden: false, ...(cursor ? { cursor } : {}) });
        if (!Array.isArray(page.data)) throw new Error('Invalid model catalog');
        for (const item of page.data) {
          if (item.hidden || typeof item.model !== 'string' || !item.model.trim() || item.model.length > 120) continue;
          models.set(item.model, { id: item.model, name: String(item.displayName || item.model).slice(0, 120),
            default: item.isDefault === true });
        }
        cursor = page.nextCursor;
        if (cursor && (cursors.has(cursor) || cursors.size >= 4)) throw new Error('Invalid model catalog cursor');
        cursors.add(cursor);
      } while (cursor);
      cached = [...models.values()].sort((a, b) => Number(b.default) - Number(a.default));
      expires = Date.now() + 60_000;
      return cached;
    })().finally(() => { inFlight = undefined; });
    return inFlight;
  };
}
