import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { requestPattern, shaPattern } from '../src/build-status.mjs';

async function run(args) {
  const [action, first, commit, requestId = randomUUID()] = args;
  const starting = action === 'start' && args.length >= 3 && args.length <= 4 &&
    first.length > 0 && shaPattern.test(commit) && requestPattern.test(requestId);
  const reading = action === 'status' && args.length === 2 && requestPattern.test(first);
  if (!starting && !reading) {
    throw new Error('用法: cloud-build.mjs start <分支> <完整提交SHA> [请求编号] | status <请求编号>');
  }
  const helper = join(homedir(), '.local/lib/cloud-workbench/ops/ssh-mobile-token.mjs');
  const { token } = JSON.parse(execFileSync(process.execPath, [helper], {
    env: { ...process.env, SSH_ORIGINAL_COMMAND: 'workbench-ssh-session-v1' }, encoding: 'utf8',
  }));
  const route = starting ? '/v1/builds' : '/v1/builds/requests/' + first;
  const headers = { Authorization: 'Bearer ' + token };
  if (starting) console.error('构建请求编号 ' + requestId);
  const response = await fetch('http://127.0.0.1:8766' + route, {
    headers: { ...headers, ...(starting && { 'Content-Type': 'application/json' }) },
    signal: AbortSignal.timeout(35_000),
    ...(starting && { method: 'POST', body: JSON.stringify({ requestId, branch: first, commit }) }),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || '构建网关返回 HTTP ' + response.status);
  console.log(JSON.stringify(result, null, 2));
  if (result.state === 'rejected') process.exitCode = 1;
}

run(process.argv.slice(2)).catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
