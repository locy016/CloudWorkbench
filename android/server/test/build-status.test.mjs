import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildStatus } from '../src/build-status.mjs';

test('build status uses the checked-out source, shares refreshes, and preserves stale results', async () => {
  const sha = 'a'.repeat(40);
  const createdAt = '2026-11-26T01:00:00Z';
  let now = Date.parse(createdAt);
  const calls = [];
  let unavailable = false;
  const read = buildStatus({
    clock: () => now,
    fetcher: async (address, options) => {
      assert.match(address, /^https:\/\/api\.github\.com\/repos\/locy016\/CloudWorkbench\/actions\//);
      assert.equal(options.redirect, 'error');
      calls.push(new URL(address).pathname);
      if (unavailable) return new Response('unavailable', { status: 502 });
      if (address.includes('/workflows/')) return Response.json({ workflow_runs: [{
        id: 7000000001, run_number: 12, run_attempt: 1, display_title: '云端工作台 CI · ' + sha,
        head_sha: 'b'.repeat(40), updated_at: createdAt, status: 'completed', conclusion: 'success',
      }] });
      if (address.includes('/jobs?')) return Response.json({ jobs: [{ steps: [
        { name: '运行网关测试', status: 'completed', conclusion: 'success' },
        { name: 'Android Lint', status: 'completed', conclusion: 'failure' },
      ] }] });
      return Response.json({ artifacts: [
        { id: 71, name: 'android-unsigned-apk', expires_at: '2026-11-27T01:00:00Z', expired: false },
        { id: 72, name: 'android-lint-report', expires_at: createdAt, expired: true },
        { id: 73, name: 'other', expires_at: '2026-11-27T01:00:00Z', expired: false },
      ] });
    },
  });
  const [first, simultaneous] = await Promise.all([read(), read()]);
  assert.deepEqual(first, simultaneous);
  assert.equal(first.runs[0].source, sha);
  assert.deepEqual(first.checks, [{ id: 'gateway', state: 'success' }, { id: 'lint', state: 'failed' }]);
  assert.deepEqual(first.outputs, [{ id: 71, kind: 'apk', expiresAt: Date.parse('2026-11-27T01:00:00Z') }]);
  assert.equal(first.stale, false);
  assert.equal(calls.length, 3);
  await read();
  assert.equal(calls.length, 3);
  now += 150001;
  await read();
  assert.equal(calls.length, 4);
  unavailable = true;
  now += 150001;
  const stale = await read();
  assert.equal(stale.stale, true);
  assert.deepEqual(stale.runs, first.runs);
  assert.ok(stale.fetchedAt < now);
  await read();
  assert.equal(calls.length, 5);
});
