export const workflow = '/actions/workflows/cloud-workbench-ci.yml';
export const shaPattern = /^[0-9a-f]{40}$/;
export const requestPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const isId = value => Number.isSafeInteger(value) && value > 0;

export function githubRequest(fetcher, path, { token, body, redirect = 'error', version = '2026-03-10' } = {}) {
  return fetcher('https://api.github.com/repos/locy016/CloudWorkbench' + path, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'CloudWorkbench',
      'X-GitHub-Api-Version': version, ...(token && { Authorization: 'Bearer ' + token }),
      ...(body && { 'Content-Type': 'application/json' }) },
    redirect, signal: AbortSignal.timeout(7_000),
    ...(body && { method: 'POST', body: JSON.stringify(body) }),
  });
}
const steps = {
  gateway: '运行网关测试',
  lint: 'Android Lint',
  apk: '构建并检查未签名 APK',
};
const outputNames = {
  'android-unsigned-apk': 'apk',
  'android-lint-report': 'lint',
};

function statusOf(status, conclusion) {
  if (status === 'in_progress') return 'running';
  if (status !== 'completed') return 'queued';
  if (conclusion === 'success') return 'success';
  if (conclusion === 'cancelled' || conclusion === 'stale') return 'cancelled';
  if (conclusion === 'skipped' || conclusion === 'neutral') return 'skipped';
  return 'failed';
}

export function parseRun(run) {
  const title = /^云端工作台 CI · ([0-9a-f]{40})(?: · ([0-9a-f-]{36}))?$/.exec(run?.display_title ?? '');
  const source = title?.[1] ?? run?.head_sha;
  const updatedAt = Date.parse(run?.updated_at);
  if (!isId(run?.id) || !isId(run.run_number) || !shaPattern.test(source ?? '') || !Number.isFinite(updatedAt)) return null;
  return {
    id: run.id, number: run.run_number, attempt: isId(run.run_attempt) ? run.run_attempt : 1,
    source, updatedAt, state: statusOf(run.status, run.conclusion), completed: run.status === 'completed',
    ...(title?.[2] && { requestId: title[2] }),
  };
}

export function buildStatus({ fetcher = fetch, clock = Date.now, readToken = async () => null } = {}) {
  let cached, pending;
  const details = new Map();
  const tracked = new Map();
  let refreshAt = 0;

  async function get(path) {
    const token = await readToken();
    const response = await githubRequest(fetcher, path, { token });
    if (token && response.status === 401) {
      await response.body?.cancel();
      return githubRequest(fetcher, path);
    }
    return response;
  }

  async function items(path, key) {
    const response = await get(path);
    if (!response.ok) throw new Error('GitHub Actions response unavailable');
    const data = (await response.json())?.[key];
    if (!Array.isArray(data)) throw new Error('Invalid GitHub Actions response');
    return data;
  }

  async function latestDetails(run) {
    const key = run.id + ':' + run.attempt;
    if (details.has(key)) return details.get(key);
    const path = '/actions/runs/' + run.id;
    const [jobs, artifacts] = await Promise.allSettled([
      items(path + '/jobs?per_page=20', 'jobs'),
      run.completed ? items(path + '/artifacts?per_page=20', 'artifacts') : Promise.resolve([]),
    ]);
    const records = jobs.status === 'fulfilled' ? jobs.value.flatMap(job => job?.steps ?? []) : [];
    const checks = Object.entries(steps).flatMap(([id, name]) => {
      const step = records.find(item => item?.name === name);
      return step ? [{ id, state: statusOf(step.status, step.conclusion) }] : [];
    });
    const outputs = artifacts.status === 'fulfilled' ? artifacts.value.flatMap(artifact => {
      const kind = Object.hasOwn(outputNames, artifact?.name) ? outputNames[artifact.name] : null;
      const expiresAt = Date.parse(artifact?.expires_at);
      return kind && isId(artifact.id) && !artifact.expired && Number.isFinite(expiresAt)
        ? [{ id: artifact.id, kind, expiresAt }] : [];
    }) : [];
    const complete = jobs.status === 'fulfilled' && artifacts.status === 'fulfilled';
    const buildJobs = jobs.status === 'fulfilled' ? jobs.value.filter(job => isId(job.id) && typeof job.name === 'string')
      .map(job => ({ id: job.id, name: job.name, state: statusOf(job.status, job.conclusion) })) : [];
    const result = { checks, outputs, jobs: buildJobs, complete };
    if (run.completed && complete) {
      if (details.size >= 30) details.delete(details.keys().next().value);
      details.set(key, result);
    }
    return result;
  }

  async function forRun(id) {
    const saved = tracked.get(id);
    if (saved && clock() < saved.next) return saved.result;
    const request = (async () => {
      try {
        const response = await get('/actions/runs/' + id);
        const run = response.ok ? parseRun(await response.json()) : null;
        if (run?.id !== id) throw new Error('Invalid tracked run');
        const { checks, outputs, jobs, complete: detailAvailable } = await latestDetails(run);
        return { runs: [run], checks, outputs, jobs, detailAvailable, fetchedAt: clock(), stale: false };
      } catch {
        const previous = await saved?.result;
        return previous ? { ...previous, stale: true } : null;
      }
    })();
    if (tracked.size >= 30) tracked.delete(tracked.keys().next().value);
    tracked.set(id, { next: clock() + 15_000, result: request });
    return request;
  }

  function snapshot(stale) {
    if (!cached) throw Object.assign(new Error('暂时无法读取 GitHub Actions 构建'), { status: 503 });
    return { ...cached, stale };
  }

  async function readBuildStatus() {
    if (pending) return pending;
    if (clock() < refreshAt) return snapshot(cached?.stale ?? true);
    pending = (async () => {
      try {
        const runs = (await items(workflow + '/runs?per_page=5', 'workflow_runs')).map(parseRun).filter(Boolean);
        let checks = [], outputs = [], detailAvailable = true;
        if (runs.length) {
          const result = await latestDetails(runs[0]);
          ({ checks, outputs } = result);
          detailAvailable = result.complete;
        }
        const fetchedAt = clock();
        cached = { runs, checks, outputs, detailAvailable, fetchedAt, stale: false };
        refreshAt = fetchedAt + 150_000;
        return snapshot(false);
      } catch {
        if (cached) cached = { ...cached, stale: true };
        refreshAt = clock() + 150_000;
        return snapshot(true);
      } finally {
        pending = null;
      }
    })();
    return pending;
  }
  readBuildStatus.forRun = forRun;
  return readBuildStatus;
}
