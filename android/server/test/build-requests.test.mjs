import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildRequests } from '../src/build-requests.mjs';
import { buildStatus } from '../src/build-status.mjs';
import { Store } from '../src/store.mjs';

const requestId = '754345e1-bae9-48af-a697-ecd10e57764f';
const head = 'a'.repeat(40), older = 'b'.repeat(40);
const when = '2026-11-26T09:00:00Z';
const token = 'test-action-token';
const fixture = (reply, { comparison = 'ahead' } = {}) => {
  const run = { id: 765, run_number: 19, head_sha: head, display_title: `云端工作台 CI · ${older} · ${requestId}`,
    updated_at: when, status: 'completed', conclusion: 'success' };
  let dispatches = 0;
  const fetcher = async (url, init) => {
    const parsed = new URL(url);
    const path = parsed.pathname.replace('/repos/locy016/CloudWorkbench', '');
    if (path === '/branches') return Response.json([{ name: 'topic/new', commit: { sha: head } }]);
    if (path === '/commits') {
      assert.equal(parsed.searchParams.get('sha'), 'topic/new');
      return Response.json([{ sha: head, commit: { message: '当前提交\nDetails' } }]);
    }
    if (path === `/compare/${older}...${head}`) return Response.json({ base_commit: { sha: older }, status: comparison });
    if (path.endsWith('/dispatches')) {
      dispatches++;
      assert.equal(init.headers.Authorization, `Bearer ${token}`);
      assert.deepEqual(JSON.parse(init.body), { ref: 'main', inputs: { source_sha: older, request_id: requestId } });
      return reply();
    }
    if (path.endsWith('/cloud-workbench-ci.yml/runs')) return Response.json({ workflow_runs: [
      { ...run, id: 999, display_title: `云端工作台 CI · ${head} · ${requestId}` }, run,
    ] });
    if (path === '/actions/runs/765') return Response.json(run);
    if (path === '/actions/runs/765/jobs') return Response.json({ jobs: [{ id: 89, name: '网关测试', status: 'completed',
      conclusion: 'success', steps: [{ name: '运行网关测试', status: 'completed', conclusion: 'success' }] }] });
    if (path === '/actions/runs/765/artifacts') return Response.json({ artifacts: [{ id: 44, name: 'android-unsigned-apk',
      expires_at: '2026-11-30T00:00:00Z', expired: false }] });
    if (path === '/actions/jobs/89/logs') return Response.redirect('https://log.actions.githubusercontent.com/text');
    if (parsed.origin === 'https://log.actions.githubusercontent.com') {
      assert.equal(init.headers?.Authorization, undefined);
      return new Response('log data\n'.repeat(4000) + 'final result');
    }
    throw new Error('unexpected route ' + path);
  };
  const service = store => {
    const readToken = async () => token, clock = () => Date.parse(when);
    return buildRequests({ store, status: buildStatus({ fetcher, readToken, clock }), readToken, clock, fetcher });
  };
  return { service, dispatches: () => dispatches };
};
const input = { requestId, branch: 'topic/new', commit: older };

test('same request is dispatched once across concurrent submissions, with exact-run status, artifacts and bounded logs', async () => {
  const store = new Store(':memory:');
  const f = fixture(() => Response.json({ workflow_run_id: 765 }));
  const service = f.service(store);
  const selected = await service.sources('topic/new');
  assert.equal(selected.commits[0].sha, head);
  const entries = await Promise.all([service.start(input), service.start(input)]);
  assert(entries.every(row => row.source === older));
  assert.equal(f.dispatches(), 1);
  await assert.rejects(service.start({ ...input, commit: head }), { status: 409 });
  const result = await service.get(requestId);
  assert.equal(result.runId, 765);
  assert.equal(result.snapshot.runs[0].source, older);
  assert.deepEqual(result.snapshot.checks, [{ id: 'gateway', state: 'success' }]);
  assert.equal(result.snapshot.outputs[0].id, 44);
  const log = await service.log(requestId, 89);
  assert(log.truncated && log.text.endsWith('final result'));
  await assert.rejects(service.log(requestId, 90), { status: 404 });
});

test('lost dispatch response survives gateway restart and reconciles the matching GitHub run without another POST', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'workbench-build-test-'));
  const db = join(dir, 'builds.db');
  t.after(() => rm(dir, { recursive: true, force: true }));
  const f = fixture(() => { throw new Error('connection lost after GitHub accepted request'); });
  const store = new Store(db);
  assert.equal((await f.service(store).start(input)).state, 'pending');
  store.db.close();
  const restarted = new Store(db);
  try {
    const service = f.service(restarted);
    assert.equal((await service.start(input)).state, 'pending');
    assert.equal((await service.get(requestId)).runId, 765);
    assert.equal((await service.start(input)).runId, 765);
    assert.equal(f.dispatches(), 1);
  } finally {
    restarted.db.close();
  }
});

test('unrelated commits are rejected and definite provider rejection is preserved', async () => {
  const unrelated = fixture(() => Response.json({}), { comparison: 'diverged' });
  await assert.rejects(unrelated.service(new Store(':memory:')).start(input), { status: 422 });
  assert.equal(unrelated.dispatches(), 0);
  const f = fixture(() => new Response(null, { status: 403 }));
  const service = f.service(new Store(':memory:'));
  assert.equal((await service.start(input)).state, 'rejected');
  assert.equal((await service.start(input)).rejection, 403);
  assert.equal(f.dispatches(), 1);
});
