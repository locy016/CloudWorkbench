const root = 'https://api.github.com/repos/locy016/CloudWorkbench';
const workflow = '/actions/workflows/cloud-workbench-ci.yml';
const steps = {
  gateway: '运行网关测试',
  lint: 'Android Lint',
  apk: '构建并检查未签名 APK',
};
const outputNames = {
  'android-unsigned-apk': 'apk',
  'android-lint-report': 'lint',
};
const shaPattern = /^[0-9a-f]{40}$/;
const isId = value => Number.isSafeInteger(value) && value > 0;

function statusOf(status, conclusion) {
  if (status === 'in_progress') return 'running';
  if (status !== 'completed') return 'queued';
  if (conclusion === 'success') return 'success';
  if (conclusion === 'cancelled' || conclusion === 'stale') return 'cancelled';
  if (conclusion === 'skipped' || conclusion === 'neutral') return 'skipped';
  return 'failed';
}

function parseRun(run) {
  const source = /^云端工作台 CI · ([0-9a-f]{40})$/.exec(run?.display_title ?? '')?.[1] ?? run?.head_sha;
  const updatedAt = Date.parse(run?.updated_at);
  if (!isId(run?.id) || !isId(run.run_number) || !shaPattern.test(source ?? '') || !Number.isFinite(updatedAt)) return null;
  return {
    id: run.id, number: run.run_number, attempt: isId(run.run_attempt) ? run.run_attempt : 1,
    source, updatedAt, state: statusOf(run.status, run.conclusion), completed: run.status === 'completed',
  };
}

export function buildStatus({ fetcher = fetch, clock = Date.now } = {}) {
  let cached, detail, pending;
  let refreshAt = 0;

  async function items(path, key) {
    const response = await fetcher(root + path, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'CloudWorkbench' },
      redirect: 'error', signal: AbortSignal.timeout(8_000),
    });
    if (!response.ok) throw new Error('GitHub Actions response unavailable');
    const data = (await response.json())?.[key];
    if (!Array.isArray(data)) throw new Error('Invalid GitHub Actions response');
    return data;
  }

  async function latestDetails(run) {
    const key = run.id + ':' + run.attempt;
    if (detail?.key === key) return detail;
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
    const result = { key, checks, outputs, complete };
    if (run.completed && complete) detail = result;
    return result;
  }

  function snapshot(stale) {
    if (!cached) throw Object.assign(new Error('暂时无法读取 GitHub Actions 构建'), { status: 503 });
    return { ...cached, stale };
  }

  return async function readBuildStatus() {
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
  };
}
