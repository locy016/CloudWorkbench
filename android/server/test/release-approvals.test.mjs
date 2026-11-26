import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { releaseApprovals } from '../src/release-approvals.mjs';

test('release endpoint stages a proof only for an enrolled key and the current candidate', async t => {
  const root = await mkdtemp(join(tmpdir(), 'wb-release-approvals-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const receipts = join(root, 'receipts');
  const input = join(root, 'input');
  await Promise.all([mkdir(receipts), mkdir(join(input, 'approvers'), { recursive: true }),
    mkdir(join(input, 'decisions'), { recursive: true })]);
  const api = releaseApprovals({ receipts, input });
  const publicKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey.export({ type: 'spki', format: 'der' });
  const { fingerprint } = await api.register({ publicKey: publicKey.toString('base64') });
  const candidate = { runId: '42', digestVersion: 2, approvalSha256: 'a'.repeat(64) };
  const request = { fingerprint, approvalSha256: candidate.approvalSha256, decision: 'reject', signature: 'Y'.repeat(96) };
  await writeFile(join(receipts, '42.json'), JSON.stringify({ state: 'awaiting-approval', candidate }));
  await assert.rejects(api.decide('42', request), { status: 403 });
  await writeFile(join(receipts, 'approver.json'), JSON.stringify({ fingerprint }));
  await assert.rejects(api.decide('42', { ...request, approvalSha256: 'b'.repeat(64) }), { status: 409 });
  assert.deepEqual(await api.decide('42', request), { queued: true });
  assert.deepEqual(await api.decide('42', request), { queued: true });
  await assert.rejects(api.decide('42', { ...request, decision: 'approve' }), { status: 409 });
  const [row] = (await api.list()).releases;
  assert.equal(row.state, 'awaiting-approval');
  assert.equal(row.pending, true);
});
