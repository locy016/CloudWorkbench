import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { stripVTControlCharacters } from 'node:util';
import { githubRequest, isId, parseRun, requestPattern, shaPattern, workflow } from './build-status.mjs';

const failure = (status, message) => Object.assign(new Error(message), { status });
const branchIsValid = name => typeof name === 'string' && name.length > 0 && name.length <= 255 && !/[\s\x00-\x1f]/.test(name);
const query = params => '?' + new URLSearchParams(params);

export async function readActionsToken(path) {
  if (!path) return null;
  try {
    const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const stat = await file.stat();
      if (!stat.isFile() || stat.size > 1024 || (stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()) return null;
      const token = (await file.readFile('utf8')).trim();
      return /^github_pat_[A-Za-z0-9_]+$/.test(token) ? token : null;
    } finally {
      await file.close();
    }
  } catch {
    return null;
  }
}

export function buildRequests({ store, status, readToken = async () => null, fetcher = fetch, clock = Date.now }) {
  const catalog = new Map();
  const reconciliations = new Map();

  async function read(path, authenticated = false) {
    try {
      const response = await githubRequest(fetcher, path, { token: authenticated ? await readToken() : null });
      if (response.status === 404 || response.status === 422) throw failure(422, '分支或提交不存在');
      if (!response.ok) throw new Error();
      return await response.json();
    } catch (error) {
      if (error.status === 422) throw error;
      throw failure(503, '暂时无法查询 GitHub，请刷新后重试');
    }
  }

  function remember(cache, key, task, ttl) {
    const previous = cache.get(key);
    if (previous && previous.until > clock()) return previous.value;
    if (cache.size >= 64) cache.delete(cache.keys().next().value);
    const value = task().catch(error => {
      if (cache.get(key)?.value === value) cache.delete(key);
      throw error;
    });
    cache.set(key, { value, until: clock() + ttl });
    return value;
  }

  const branches = () => remember(catalog, 'branches', async () => {
    const items = await read('/branches?per_page=100');
    return items.filter(item => branchIsValid(item.name) && shaPattern.test(item.commit?.sha))
      .map(item => ({ name: item.name, sha: item.commit.sha }));
  }, 60_000);

  async function commits(branch, fresh = false) {
    if (!(await branches()).some(item => item.name === branch)) throw failure(422, '请选择仓库中可用的分支');
    const load = async () => {
      const list = await read('/commits' + query({ sha: branch, per_page: 30 }));
      return list.filter(item => shaPattern.test(item.sha)).map(item => ({ sha: item.sha,
        title: String(item.commit?.message ?? '').split('\n')[0].slice(0, 160),
        date: Date.parse(item.commit?.committer?.date) || 0 }));
    };
    return fresh ? load() : remember(catalog, 'commits:' + branch, load, 30_000);
  }

  async function sources(branch) {
    if (branch !== null && !branchIsValid(branch)) throw failure(400, '无效分支');
    const [enabled, available, recent] = await Promise.all([
      readToken(), branches(), branch === null ? Promise.resolve([]) : commits(branch),
    ]);
    return { enabled: Boolean(enabled), branches: available, commits: recent };
  }

  function publicRequest(row) {
    const { selected, ...visible } = row;
    return visible;
  }

  async function start(input) {
    const { requestId, branch, commit = null } = input ?? {};
    if (typeof requestId !== 'string' || !requestPattern.test(requestId) || !branchIsValid(branch) ||
        (commit !== null && (typeof commit !== 'string' || !shaPattern.test(commit)))) throw failure(400, '无效构建请求');
    const duplicate = row => {
      if (row.branch !== branch || row.selected !== commit) throw failure(409, '构建编号已绑定到其他源码');
      return publicRequest(row);
    };
    const previous = store.getBuildRequest(requestId);
    if (previous) return duplicate(previous);
    const token = await readToken();
    if (!token) throw failure(412, '服务器尚未配置 GitHub Actions 授权');
    const rows = await commits(branch, true);
    let source = commit === null ? rows[0]?.sha : rows.find(item => item.sha === commit)?.sha;
    if (!source && commit && rows[0]) {
      // Compare against the immutable head of the selected branch, never against an arbitrary fork ref.
      const comparison = await read(`/compare/${commit}...${rows[0].sha}?per_page=1`);
      if (comparison.base_commit?.sha === commit && ['ahead', 'identical'].includes(comparison.status)) source = commit;
    }
    if (!source) throw failure(422, '提交不在所选分支的历史中，请重新选择');
    if (!store.insertBuildRequest(requestId, branch, commit, source, clock())) return duplicate(store.getBuildRequest(requestId));
    try {
      const response = await githubRequest(fetcher, workflow + '/dispatches', {
        token, body: { ref: 'main', inputs: { source_sha: source, request_id: requestId } },
      });
      if ([400, 401, 403, 404, 410, 422, 429].includes(response.status)) {
        store.rejectBuildRequest(requestId, response.status);
        await response.body?.cancel();
      } else if (response.status === 200) {
        const id = (await response.json())?.workflow_run_id;
        if (isId(id)) store.bindBuildRun(requestId, id);
      } else {
        await response.body?.cancel();
      }
    } catch {
      // GitHub can accept a request whose response never reaches the gateway.
    }
    return publicRequest(store.getBuildRequest(requestId));
  }

  async function get(requestId) {
    const row = store.getBuildRequest(requestId);
    if (!row) throw failure(404, '构建请求尚未到达服务器');
    if (row.state === 'pending') {
      try {
        const id = await remember(reconciliations, requestId, async () => {
          const runs = await read(workflow + '/runs' + query({ event: 'workflow_dispatch', per_page: 100,
            created: '>=' + new Date(row.createdAt - 60_000).toISOString() }), true);
          return runs.workflow_runs.map(parseRun).find(run => run?.requestId === requestId && run.source === row.source)?.id;
        }, 15_000);
        if (id) store.bindBuildRun(requestId, id);
      } catch {
        // Leave the durable intent available for the next reconciliation.
      }
    }
    const current = store.getBuildRequest(requestId);
    const result = current.runId ? await status.forRun(current.runId) : null;
    const run = result?.runs[0];
    const snapshot = run?.source === current.source && run.requestId === requestId ? result : null;
    return { ...publicRequest(current), snapshot };
  }

  async function log(requestId, jobId) {
    const { snapshot } = await get(requestId);
    if (!snapshot?.jobs.some(job => job.id === jobId)) throw failure(404, '此构建没有该作业');
    const response = await githubRequest(fetcher, '/actions/jobs/' + jobId + '/logs', {
      token: await readToken(), redirect: 'manual',
    });
    if (response.status !== 302 || !response.headers.get('location')) throw failure(409, '该作业的日志尚未生成或已过期');
    const location = new URL(response.headers.get('location'));
    const allowedHost = /^(?:[a-z0-9.-]+\.actions\.githubusercontent\.com|productionresultssa[0-9]+\.blob\.core\.windows\.net)$/;
    if (location.protocol !== 'https:' || location.username || location.password || location.port || !allowedHost.test(location.hostname)) {
      throw failure(502, 'GitHub 日志地址无效');
    }
    const logResponse = await fetcher(location, { redirect: 'error', signal: AbortSignal.timeout(7_000) });
    if (!logResponse.ok || !logResponse.body) throw failure(503, '暂时无法读取 GitHub 日志');
    const reader = logResponse.body.getReader();
    const limit = 28 * 1024;
    let tail = Buffer.alloc(0), total = 0;
    try {
      while (total <= 4 * 1024 * 1024) {
        const { value, done } = await reader.read();
        if (done) break;
        total += value.length;
        tail = Buffer.concat([tail, value.subarray(-limit)]).subarray(-limit);
      }
    } finally {
      await reader.cancel();
    }
    let text = tail.toString('utf8');
    if (total > limit && text.includes('\n')) text = text.slice(text.indexOf('\n') + 1);
    return { text: stripVTControlCharacters(text), truncated: total > limit };
  }

  return { sources, start, get, log };
}
