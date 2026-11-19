import { issueMobileSession, mobileCommand, mobileKeyPath, readMobileKey } from '../src/mobile-session.mjs';

if (process.env.SSH_ORIGINAL_COMMAND !== mobileCommand || process.argv.length !== 2) {
  process.exitCode = 1;
} else {
  try {
    const key = readMobileKey(mobileKeyPath);
    try { process.stdout.write(JSON.stringify(issueMobileSession(key)) + '\n'); }
    finally { key.fill(0); }
  } catch {
    process.stderr.write('SSH mobile session unavailable\n');
    process.exitCode = 1;
  }
}
