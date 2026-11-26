import { createHash, createPublicKey, randomUUID } from 'node:crypto';
import { chmod, link, readFile, readdir, stat, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const digest = /^[0-9a-f]{64}$/;
export const releaseRunId = /^[1-9][0-9]{0,17}$/;
const fail = (status, message) => Object.assign(new Error(message), { status });

async function json(path) {
  try { return JSON.parse(await readFile(path, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}

async function deliver(directory, filename, body) {
  const target = join(directory, filename);
  const staging = join(directory, '.' + randomUUID());
  await writeFile(staging, body, { flag: 'wx', mode: 0o640 });
  try {
    // The gateway's umask masks group access. The service consumes the proof as a different user.
    await chmod(staging, 0o640);
    await link(staging, target);
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    const prior = await readFile(target).catch(err => { if (err.code !== 'ENOENT') throw err; });
    if (prior && !prior.equals(body)) throw fail(409, '这项发布已有待复核的审批');
  } finally { await unlink(staging); }
}

export function releaseApprovals({ receipts, input }) {
  const decisions = join(input, 'decisions');
  const approvers = join(input, 'approvers');
  const approver = () => json(join(receipts, 'approver.json'));
  const receipt = run => json(join(receipts, run + '.json'));
  const pending = async run => stat(join(decisions, run + '.json')).then(() => true, error => {
    if (error.code === 'ENOENT') return false;
    throw error;
  });

  return {
    async list() {
      const names = await readdir(receipts);
      const rows = [];
      for (const filename of names.filter(name => name.endsWith('.json') && releaseRunId.test(name.slice(0, -5)))
        .sort((a, b) => b.length - a.length || b.localeCompare(a)).slice(0, 30)) {
        const run = filename.slice(0, -5);
        const row = await receipt(run);
        if (row?.candidate?.digestVersion !== 2 || row.candidate.runId !== run) continue;
        rows.push({ ...row, pending: row.state === 'awaiting-approval' && await pending(run) });
      }
      return { fingerprint: (await approver())?.fingerprint ?? null, releases: rows.slice(0, 12) };
    },

    async register(body) {
      const value = body?.publicKey;
      if (typeof value !== 'string' || !/^[A-Za-z0-9+/=]{80,344}$/.test(value)) throw fail(400, '审批公钥格式无效');
      const der = Buffer.from(value, 'base64');
      try {
        const key = createPublicKey({ key: der, type: 'spki', format: 'der' });
        if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1' ||
            !key.export({ type: 'spki', format: 'der' }).equals(der)) throw new Error();
      } catch { throw fail(400, '审批公钥必须是 P-256'); }
      const fingerprint = createHash('sha256').update(der).digest('hex');
      await deliver(approvers, fingerprint + '.der', der);
      return { fingerprint };
    },

    async decide(run, body) {
      const { approvalSha256, fingerprint, decision, signature } = body ?? {};
      if (typeof approvalSha256 !== 'string' || !digest.test(approvalSha256) || typeof fingerprint !== 'string' ||
          !digest.test(fingerprint) || !['approve', 'reject'].includes(decision) || typeof signature !== 'string' ||
          !/^[A-Za-z0-9+/=]{84,112}$/.test(signature)) throw fail(400, '无效审批签名');
      const row = await receipt(run);
      if (row?.candidate?.digestVersion !== 2 || row.candidate.approvalSha256 !== approvalSha256 ||
          row.state !== 'awaiting-approval') throw fail(409, '候选状态已变更，请刷新');
      if ((await approver())?.fingerprint !== fingerprint) throw fail(403, '此设备尚未绑定发布审批');
      const proof = Buffer.from(JSON.stringify({ approvalSha256, fingerprint, decision, signature }));
      await deliver(decisions, run + '.json', proof);
      return { queued: true };
    },
  };
}
