import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import { Devices } from '../src/devices.mjs';

const [action, id, scopes, ...extra] = process.argv.slice(2);
if (extra.length || !(action === 'list' && !id || action === 'revoke' && id && !scopes ||
    action === 'scopes' && id && scopes || action === 'provision-browser' && id?.trim() && !scopes)) {
  console.error('Usage: node ops/workbench-devices.mjs list | revoke <device-id> | scopes <device-id> <api,events,browser> | provision-browser <label>');
  process.exit(2);
}
process.umask(0o077);
const data = process.env.DATA_DIR || join(homedir(), '.local/share/cloud-workbench');
const devices = new Devices(join(data, 'devices.db'));
try {
  if (action === 'list') console.log(JSON.stringify(devices.list(), null, 2));
  else if (action === 'provision-browser') {
    const deviceId = randomUUID();
    const renewalToken = randomBytes(32).toString('base64url');
    devices.pair({ id: deviceId, label: id, renewalToken, scopes: ['browser'] });
    console.log(JSON.stringify({ version: 1, deviceId, renewalToken }));
  }
  else {
    const updated = action === 'revoke' ? devices.revoke(id) : devices.setScopes(id, scopes.split(','));
    if (!updated) {
      console.error('No active device with this ID');
      process.exitCode = 1;
    } else console.log(action === 'revoke' ? 'Device revoked' : 'Device scopes updated');
  }
} finally {
  devices.close();
}
