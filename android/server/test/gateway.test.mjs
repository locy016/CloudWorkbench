import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Gateway } from '../src/gateway.mjs';
import { Store } from '../src/store.mjs';
class Fake extends EventEmitter {
  calls = []; replies = []; process = {}; count = 0;
  async start() {}
  async rpc(method, params) {
    this.calls.push({ method, params });
    if (method === 'thread/start') return { thread: { id: 'thread-' + ++this.count } };
    if (method === 'turn/start') return { turn: { id: 'turn-1' } };
    if (method === 'turn/interrupt') this.emit('notification', { method: 'turn/completed', params: { threadId: params.threadId, turn: { status: 'interrupted' } } });
    return {};
  }
  reply(id, result) { this.replies.push({ id, result }); }
  send(m) { this.replies.push(m); }
  close() {}
}
async function fixture(t, options = {}) {
  const codex = new Fake(); const store = new Store(':memory:');
  const g = new Gateway({ codex, store, token: 'test-token-123456789012345678901234567890', cwd: '/test/work', ...options });
  await new Promise(r => g.http.listen(0, '127.0.0.1', r)); t.after(() => g.close());
  const call = async (path, body, extra = {}, method = body === undefined ? 'GET' : 'POST') => {
    const r = await fetch(`http://127.0.0.1:${g.http.address().port}${path}`, { method, headers: { Authorization: 'Bearer ' + g.token, ...extra }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: r.status, body: await r.json() };
  };
  return { g, codex, store, call };
}
test('cleanup is project-scoped and deletion respects live tasks and removes history', async t => {
  const { call, store, codex } = await fixture(t, {
    projects: [{ id: 'a', name: 'A', path: '/test/a' }, { id: 'b', name: 'B', path: '/test/b' }]
  });
  const empty = (await call('/v1/threads', { projectId: 'a' })).body.thread.id;
  const other = (await call('/v1/threads', { projectId: 'b' })).body.thread.id;
  const chat = (await call('/v1/threads', { projectId: 'a' })).body.thread.id;
  await call(`/v1/threads/${chat}/messages`, { text: '检查', requestId: 'request-delete-1' });
  assert.deepEqual((await call('/v1/threads')).body.projectsWithEmptyThreads, ['a', 'b']);
  assert.deepEqual((await call('/v1/threads/cleanup', { projectId: 'a' })).body.deleted, [empty]);
  assert.deepEqual((await call('/v1/threads')).body.projectsWithEmptyThreads, ['b']);
  assert.equal((await call(`/v1/threads/${other}`)).status, 200);
  const remove = () => call(`/v1/threads/${chat}`, undefined, {}, 'DELETE');
  assert.equal((await remove()).status, 409);
  const remoteId = store.thread(chat).codexId;
  codex.emit('notification', { method: 'turn/completed', params: { threadId: remoteId, turn: { status: 'completed' } } });
  assert.equal((await remove()).status, 200);
  assert.equal((await remove()).status, 200);
  assert.equal((await call(`/v1/threads/${chat}`)).status, 404);
  assert.equal(store.local(remoteId), undefined);
  assert.equal(store.messages(chat).length, 0);
  assert.equal(store.request('request-delete-1'), undefined);
});
test('authentication, origin and unknown thread are rejected', async t => {
  const { call } = await fixture(t);
  assert.equal((await call('/v1/threads', undefined, { Authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await call('/v1/threads', undefined, { Origin: 'https://evil.test' })).status, 403);
  assert.equal((await call('/v1/threads/not-ours')).status, 404);
});
test('reference rates are read-only and require SSH mobile authorization and no client-supplied destination', async t => {
  const route = '/v1/server-data/ecb-rates';
  const sample = { date: '2026-11-20', rates: [{ currency: 'USD', rate: '1.0990' }], stale: false };
  let fetched = 0;
  const { call } = await fixture(t, {
    sshMobile: req => req.headers.authorization === 'SSH test' ? 'test-device' : null,
    readReferenceRates: async () => { fetched++; return sample; }
  });
  const auth = { Authorization: 'SSH test' };
  assert.equal((await call(route)).status, 401);
  assert.equal((await call(route, undefined, { ...auth, Origin: 'https://example.test' })).status, 403);
  assert.equal((await call(route, undefined, { ...auth, 'x-workbench-ingress': 'web' })).status, 404);
  assert.equal((await call(route + '?url=https://example.test', undefined, auth)).status, 400);
  assert.equal((await call(route, {}, auth)).status, 404);
  assert.equal(fetched, 0);
  assert.deepEqual(await call(route, undefined, auth), { status: 200, body: sample });
  assert.equal(fetched, 1);
});
test('trade directory and observations accept only SSH and the country-year query', async t => {
  const routes = '/v1/server-data/world-bank';
  const auth = { Authorization: 'SSH test' };
  const sample = { country: 'CHN', year: 2023, updatedAt: '2026-11-11', tradePercent: 36.7391 };
  let reads = 0;
  const { call } = await fixture(t, {
    sshMobile: req => req.headers.authorization === auth.Authorization ? 'test-device' : null,
    tradeData: {
      countries: async () => { reads++; return { countries: [{ code: 'CHN' }] }; },
      trade: async (country, year) => { reads++; assert.deepEqual([country, year], ['CHN', '2023']); return sample; }
    }
  });
  assert.equal((await call(routes + '/countries')).status, 401);
  assert.equal((await call(routes + '/trade?country=CHN&year=2023', undefined, { ...auth, 'x-workbench-ingress': 'web' })).status, 404);
  assert.equal((await call(routes + '/countries?url=https://example.test', undefined, auth)).status, 400);
  assert.equal((await call(routes + '/trade?country=CHN&country=USA', undefined, auth)).status, 400);
  assert.equal((await call(routes + '/trade?country=CHN&year=2023&url=https://example.test', undefined, auth)).status, 400);
  assert.equal(reads, 0);
  assert.deepEqual((await call(routes + '/countries', undefined, auth)).body.countries, [{ code: 'CHN' }]);
  assert.deepEqual(await call(routes + '/trade?year=2023&country=CHN', undefined, auth), { status: 200, body: sample });
  assert.equal(reads, 2);
});
test('resource catalog and fetch routes require SSH and reject input URLs', async t => {
  const path = '/v1/server-data/resources';
  const auth = { Authorization: 'SSH test' };
  const { call } = await fixture(t, { sshMobile: req => req.headers.authorization === auth.Authorization ? 'test-device' : null });
  assert.equal((await call(path)).status, 401);
  assert.equal((await call(path + '/usgs-earthquakes?period=day', undefined, { ...auth, 'x-workbench-ingress': 'web' })).status, 404);
  assert.equal((await call(path + '/usgs-earthquakes?url=https://example.test', undefined, auth)).status, 400);
  assert.equal((await call(path + '/no-such-source', undefined, auth)).status, 404);
  const { status, body } = await call(path, undefined, auth);
  assert.equal(status, 200);
  assert.ok(body.sources.some(entry => entry.id === 'github-project-search'));
});
test('manual overview refresh bypasses the cache and shares an in-flight collection', async t => {
  const { g, call } = await fixture(t);
  let collections = 0;
  let pending;
  let markEntered;
  g.collectStatus = async () => {
    collections++;
    if (pending) { markEntered(); await pending; }
    return g.statusCache = { timestamp: Date.now(), collections };
  };
  assert.equal((await call('/v1/status')).body.collections, 1);
  assert.equal((await call('/v1/status')).body.collections, 1);
  let complete;
  pending = new Promise(resolve => { complete = resolve; });
  const entered = new Promise(resolve => { markEntered = resolve; });
  const first = call('/v1/status?refresh=1');
  await entered;
  const second = g.status(true);
  complete();
  assert.deepEqual((await first).body, await second);
  assert.equal(g.metrics.snapshot().counters.statusCoalesced, 1);
  assert.equal(collections, 2);
  assert.equal((await call('/v1/status?refresh=1', undefined, { Authorization: 'Bearer wrong' })).status, 401);
});
test('overview keeps bounded recent state changes, including a recovered service', async t => {
  const { store } = await fixture(t);
  const now = Date.now();
  const sample = (state, time) => store.observeOverviewServices([{ name: 'ssh', state }], time);
  assert.deepEqual(sample('active', now), []);
  assert.deepEqual(sample('failed', now + 1).map(row => ({ ...row })), [{ name: 'ssh', state: 'failed', observedAt: now + 1 }]);
  assert.equal(sample('failed', now + 2).length, 1);
  assert.equal(sample('active', now + 3).length, 1);
  assert.deepEqual(sample('inactive', now + 4).map(row => row.state), ['inactive', 'failed']);
  for (let i = 0; i < 105; i++) {
    sample('active', now + 5 + i * 2);
    sample('failed', now + 6 + i * 2);
  }
  assert.equal(sample('active', now + 220).length, 6);
  assert.equal(store.prepare('SELECT COUNT(*) AS total FROM overview_incidents').get().total, 100);
  assert.deepEqual(sample('active', now + 86_400_500), []);
});
test('the workbench API accepts the local SSH path and rejects requests from web ingress', async t => {
  const sshMobile = req => req.headers.authorization === 'Bearer mobile-fixture' ? 'mobile' : null;
  const { call } = await fixture(t, { sshMobile });
  const mobile = { Authorization: 'Bearer mobile-fixture' };
  assert.equal((await call('/v1/threads', undefined, mobile)).status, 200);
  assert.equal((await call('/v1/threads', undefined, { ...mobile, 'x-workbench-ingress': 'web' })).status, 404);
  assert.equal((await call('/internal/workstation-devices/codes', {}, mobile)).status, 404);
});
test('history filters across unloaded pages and scopes pagination to the same search', async t => {
  const { call, store } = await fixture(t, { projects: [
    { id: 'default', name: '常规', path: '/test/default' },
    { id: 'archive', name: '历史', path: '/test/old', archived: true },
  ] });
  for (const id of ['old-a', 'old-b', 'wrong-status']) {
    store.create(id, 'ÉTAT 磁盘 50%_&');
    if (id !== 'wrong-status') store.state(id, 'failed');
  }
  store.create('wrong-project', 'ÉTAT 磁盘 50%_&');
  store.project('wrong-project', 'archive');
  store.state('wrong-project', 'failed');
  store.prepare("UPDATE threads SET updated=1 WHERE title LIKE '%磁盘%'").run();
  for (let i = 0; i < 45; i++) store.create(`new-${i}`, `较新的会话 ${i}`);
  const initial = (await call('/v1/threads?limit=40')).body;
  assert.equal(initial.threads.length, 40);
  assert.equal(initial.threads.some(({ id }) => id.startsWith('old-')), false);
  const search = '/v1/threads?limit=1&q=' + encodeURIComponent('état 磁盘 50%_&') + '&projectId=default&status=failed';
  const first = (await call(search)).body;
  assert.equal(first.searchSupported, true);
  assert.deepEqual(first.threads.map(({ id }) => id), ['old-b']);
  assert.equal(first.hasMore, true);
  const second = (await call(search + '&before=' + first.nextBefore)).body;
  assert.deepEqual(second.threads.map(({ id }) => id), ['old-a']);
  assert.equal(second.hasMore, false);
  assert.equal((await call(search.replace('default', 'archive') + '&before=' + first.nextBefore)).status, 400);
  assert.deepEqual((await call(search.replace('default', 'archive'))).body.threads.map(({ id }) => id), ['wrong-project']);
  assert.deepEqual((await call('/v1/threads?q=' + encodeURIComponent('找不到的标题'))).body.threads, []);
  assert.equal((await call('/v1/threads?status=invalid')).status, 400);
});
test('archived workspaces preserve history and are skipped for new conversations', async t => {
  const { call, store } = await fixture(t, {
    projects: [{ id: 'default', name: 'Archived', path: '/test/old', archived: true },
      { id: 'ops', name: 'Operations', path: '/test/ops' }]
  });
  store.create('legacy-chat', '旧会话');
  store.message('legacy-chat', 'old-message', 'user', '旧消息');
  assert.equal((await call('/v1/threads/legacy-chat')).body.messages[0].text, '旧消息');
  assert.equal((await call('/v1/threads/legacy-chat/messages', { text: '继续', requestId: 'request-archived' })).status, 409);
  assert.equal((await call('/v1/threads', { projectId: 'default' })).status, 400);
  assert.equal((await call('/v1/threads', {})).body.thread.projectId, 'ops');
  assert.deepEqual((await call('/v1/threads')).body.projectsWithEmptyThreads, ['ops']);
});
test('single task limit, idempotency, stream persistence and cancellation', async t => {
  const { call, codex } = await fixture(t);
  const a = (await call('/v1/threads', { title: '检查' })).body.thread.id;
  const b = (await call('/v1/threads', {})).body.thread.id;
  const msg = { text: '只读检查', requestId: 'request-12345678' };
  assert.equal((await call(`/v1/threads/${a}/messages`, msg)).status, 200);
  assert.equal((await call(`/v1/threads/${a}/messages`, msg)).body.duplicate, true);
  assert.equal((await call(`/v1/threads/${b}/messages`, { ...msg, requestId: 'request-87654321' })).status, 409);
  assert.equal(codex.calls.filter(c => c.method === 'turn/start').length, 1);
  codex.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: a, itemId: 'assistant-1', delta: '检查完成' } });
  assert.equal((await call(`/v1/threads/${a}`)).body.messages.at(-1).text, '检查完成');
  await call(`/v1/threads/${a}/cancel`, {});
  assert.equal((await call(`/v1/threads/${a}`)).body.thread.status, 'interrupted');
});
test('approvals remain pending until explicit per-request answer; cannot replay', async t => {
  const { call, codex } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  await call(`/v1/threads/${id}/messages`, { text: '检查', requestId: 'request-12345678' });
  codex.emit('request', { id: 5, method: 'item/commandExecution/requestApproval', params: { threadId: id, command: 'example', reason: 'test' } });
  const s = (await call(`/v1/threads/${id}`)).body;
  assert.equal(s.thread.status, 'approval'); assert.equal(codex.replies.length, 0);
  assert.deepEqual((await call('/v1/threads?include=approvals')).body.approvals, s.approvals);
  assert.deepEqual((await call('/v1/threads')).body.approvals, s.approvals);
  assert.equal(Object.hasOwn((await call('/v1/threads?limit=1&include=approvals')).body, 'approvals'), false);
  assert.equal((await call('/v1/approvals/' + s.approvals[0].id, { decision: 'acceptForSession' })).status, 400);
  assert.equal((await call('/v1/approvals/' + s.approvals[0].id, { decision: 'decline' })).status, 200);
  assert.deepEqual(codex.replies[0], { id: 5, result: { decision: 'decline' } });
  assert.equal((await call('/v1/approvals/' + s.approvals[0].id, { decision: 'accept' })).status, 409);
});
test('a submission receipt is scoped to its thread and survives failed execution and message pagination', async t => {
  const { call, codex, store } = await fixture(t);
  const id = (await call('/v1/threads', { title: '检查服务' })).body.thread.id;
  const other = (await call('/v1/threads', {})).body.thread.id;
  const requestId = 'request-receipt-01';
  const receiptUrl = thread => `/v1/threads/${thread}/updates?limit=1&requestId=${requestId}`;
  assert.equal((await call(receiptUrl(id))).body.receipt.state, 'missing');
  assert.equal((await call(`/v1/threads/${id}/messages`, { text: '检查', requestId })).body.receipt.state, 'accepted');
  const remoteId = store.thread(id).codexId;
  store.message(id, 'latest-output', 'assistant', '检查失败');
  codex.emit('notification', { method: 'turn/completed', params: { threadId: remoteId, turn: { status: 'failed' } } });
  const update = (await call(receiptUrl(id))).body;
  assert.deepEqual(update.receipt, { requestId, state: 'accepted' });
  assert.equal(update.thread.status, 'failed');
  assert.deepEqual(update.messages.map(message => message.id), ['latest-output']);
  assert.equal((await call(receiptUrl(other))).body.receipt.state, 'missing');
  assert.equal((await call(`/v1/threads/${id}/updates?requestId=invalid`)).status, 400);
  assert.deepEqual((await call('/v1/threads')).body.recentResults.map(thread => [thread.id, thread.status]), [[id, 'failed']]);
  assert.equal((await call(`/v1/threads/${id}/messages`, { text: '检查', requestId })).body.duplicate, true);
  assert.equal(codex.calls.filter(call => call.method === 'turn/start').length, 1);
});
test('a duplicate cannot clear a draft when the original start has no confirmed receipt', async t => {
  const { call, g, store } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  const requestId = 'request-pending-01';
  store.beginRequest(requestId, id);
  g.startingRequestId = requestId;
  const path = `/v1/threads/${id}/messages`;
  assert.equal((await call(path, { text: '检查', requestId })).body.receipt.state, 'starting');
  g.startingRequestId = null;
  assert.equal((await call(path, { text: '检查', requestId })).body.receipt.state, 'unknown');
});
test('user questions require each answer; permission grants require an explicit decision', async t => {
  const { call, codex } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  codex.emit('request', { id: 8, method: 'item/tool/requestUserInput', params: { threadId: id, questions: [{ id: 'choice', question: '选择？' }] } });
  const a = (await call(`/v1/threads/${id}`)).body.approvals[0];
  assert.equal((await call('/v1/approvals/' + a.id, { answers: {} })).status, 400);
  await call('/v1/approvals/' + a.id, { answers: { choice: '保留' } });
  assert.deepEqual(codex.replies[0].result, { answers: { choice: { answers: ['保留'] } } });
  codex.emit('request', { id: 9, method: 'item/permissions/requestApproval', params: { threadId: id, permissions: { network: { enabled: true }, fileSystem: null } } });
  assert.equal(codex.replies.length, 1);
  const p = (await call(`/v1/threads/${id}`)).body.approvals[0];
  await call('/v1/approvals/' + p.id, { decision: 'accept' });
  assert.deepEqual(codex.replies[1].result, { permissions: { network: { enabled: true } }, scope: 'turn' });
  codex.emit('request', { id: 10, method: 'unknown/request', params: { threadId: id } });
  assert.equal(codex.replies[2].error.code, -32601);
});
test('process loss clears approvals and marks task interrupted', async t => {
  const { call, codex } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  await call(`/v1/threads/${id}/messages`, { text: '测试', requestId: 'request-12345678' });
  codex.emit('disconnected');
  assert.equal((await call(`/v1/threads/${id}`)).body.thread.status, 'interrupted');
});
test('empty conversations are local until first send, and remote events map to local history', async t => {
  const { call, codex, g, store } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  assert.equal(codex.calls.length, 0);
  g.loaded.clear(); // Simulate no loaded Codex session after a gateway restart.
  await call(`/v1/threads/${id}/messages`, { text: 'hello', requestId: 'request-persistence' });
  const remoteId = store.thread(id).codexId;
  assert.notEqual(id, remoteId);
  codex.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: remoteId, itemId: 'msg-1', delta: 'persisted reply' } });
  codex.emit('notification', { method: 'turn/completed', params: { threadId: remoteId, turn: { status: 'completed' } } });
  const snapshot = (await call(`/v1/threads/${id}`)).body;
  assert.equal(snapshot.thread.status, 'idle');
  assert.equal(snapshot.messages.at(-1).text, 'persisted reply');
  assert.equal(codex.calls.find(c => c.method === 'turn/start').params.threadId, remoteId);
});
test('a rejected submission can retry its request ID without duplicating the user message', async t => {
  const { call, codex } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  const rpc = codex.rpc.bind(codex); let first = true;
  codex.rpc = async (method, params) => { if (method === 'turn/start' && first) { first = false; throw new Error('model unavailable'); } return rpc(method, params); };
  const message = { text: 'hello', requestId: 'request-retry-0001' };
  assert.equal((await call(`/v1/threads/${id}/messages`, message)).status, 500);
  assert.equal((await call(`/v1/threads/${id}/messages`, message)).status, 200);
  assert.equal((await call(`/v1/threads/${id}`)).body.messages.length, 1);
});
test('decline maps to cancel when Codex only offers accept/cancel', async t => {
  const { call, codex } = await fixture(t);
  const id = (await call('/v1/threads', {})).body.thread.id;
  codex.emit('request', { id: 20, method: 'item/commandExecution/requestApproval', params: { threadId: id, availableDecisions: ['accept', 'cancel'] } });
  const a = (await call(`/v1/threads/${id}`)).body.approvals[0];
  assert.equal((await call('/v1/approvals/' + a.id, { decision: 'decline' })).status, 200);
  assert.deepEqual(codex.replies[0].result, { decision: 'cancel' });
});


test('project allowlist is enforced and conversation keeps its original cwd', async t => {
  const { call, codex, g } = await fixture(t, {
    projects: [{ id: 'one', name: 'One', path: '/work/one' }, { id: 'two', name: 'Two', path: '/work/two' }],
    sharedWritableRoots: ['/work/two', '/work/shared'],
  });
  assert.equal((await call('/v1/threads', { projectId: '../../etc' })).status, 400);
  const id = (await call('/v1/threads', { projectId: 'two', cwd: '/untrusted' })).body.thread.id;
  await call(`/v1/threads/${id}/messages`, { text: 'inspect', requestId: 'project-request-123', writableRoots: ['/etc'] });
  assert.equal(codex.calls.find(c => c.method === 'thread/start').params.cwd, '/work/two');
  const turn = codex.calls.find(c => c.method === 'turn/start').params;
  assert.deepEqual(turn.sandboxPolicy, { type: 'workspaceWrite', writableRoots: ['/work/two', '/work/shared'], networkAccess: true });
  assert.equal(turn.approvalPolicy, 'on-request');
  assert.equal(turn.approvalsReviewer, 'user');
  codex.emit('notification', { method: 'turn/completed', params: { threadId: g.store.thread(id).codexId, turn: { status: 'completed' } } });
  g.projects = g.projects.filter(p => p.id !== 'two');
  assert.equal((await call(`/v1/threads/${id}/messages`, { text: 'again', requestId: 'project-request-456' })).status, 409);
});

test('retired host and proxy routes are unavailable', async t => {
  const { call } = await fixture(t);
  assert.equal((await call('/v1/hosts')).status, 404);
  assert.equal((await call('/v1/hosts/windows/v1/status')).status, 404);
  assert.equal((await call('/v1/hosts/windows/v1/threads', {})).status, 404);
  assert.equal((await call('/v1/threads')).status, 200);
});

test('server-selected full access applies to new and resumed conversations', async t => {
  const { call, codex, g, store } = await fixture(t, { codexAccess: 'danger-full-access' });
  const id = (await call('/v1/threads', {})).body.thread.id;
  const path = `/v1/threads/${id}/messages`;
  assert.equal((await call(path, { text: 'first', requestId: 'full-access-first' })).status, 200);
  const remoteId = store.thread(id).codexId;
  codex.emit('notification', { method: 'turn/completed', params: { threadId: remoteId, turn: { status: 'completed' } } });
  g.loaded.clear();
  assert.equal((await call(path, { text: 'resume', requestId: 'full-access-resume' })).status, 200);
  for (const method of ['thread/start', 'thread/resume']) {
    const { params } = codex.calls.find(call => call.method === method);
    assert.equal(params.sandbox, 'danger-full-access');
    assert.equal(params.approvalPolicy, 'never');
  }
  const turns = codex.calls.filter(call => call.method === 'turn/start');
  assert.equal(turns.length, 2);
  for (const { params } of turns) {
    assert.deepEqual(params.sandboxPolicy, { type: 'dangerFullAccess' });
    assert.equal(params.approvalPolicy, 'never');
    assert.equal(params.cwd, '/test/work');
  }
});
